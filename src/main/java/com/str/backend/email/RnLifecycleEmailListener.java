package com.str.backend.email;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.common.Strings;
import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.document.ZupContextFactory;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.egop.EgopAktiBezSifre;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.eseal.PdfSealer;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.unit.DataSize;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Šalje iznajmljivaču obavijest o promjeni statusa registracijskog broja.
 *
 * <p>Radi AFTER_COMMIT, pa vidi i podatke koje pozivatelj postavi nakon prijelaza — npr.
 * {@code suspensionDeadline}, koji {@code RnService#suspend} upisuje tek nakon
 * {@code transition(...)}.
 *
 * <p>Mail <b>nije dostava</b> (čl. 94. ZUP-a); akt se dostavlja u korisnički pretinac i od
 * te dostave teku rokovi. Zato se akt prilaže kao preslika, a svaki predložak nosi klauzulu
 * koja to izrijekom kaže.
 *
 * <p>Neuspjelo slanje ide u {@link MailRetryStore}, a ponavlja ga {@link MailRetryJob} preko
 * {@link #retry}. Trajni razlozi (nema adrese, nema RB-a) se ne ponavljaju.
 */
@Component
public class RnLifecycleEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RnLifecycleEmailListener.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy.");

    private final RnLifecycleLookup lookup;
    private final StrDocumentService documentService;
    private final DocumentLabels labels;
    private final EmailService emailService;
    private final MailRetryStore retryStore;
    private final PdfSealer sealer;
    private final EgopPismenoRepository pismenoRepository;
    private final PrilogService prilogService;
    private final EgopAktiBezSifre bezSifre;
    private final long maxPoruka;

    public RnLifecycleEmailListener(RnLifecycleLookup lookup,
                                    StrDocumentService documentService,
                                    DocumentLabels labels,
                                    EmailService emailService,
                                    MailRetryStore retryStore,
                                    PdfSealer sealer,
                                    EgopPismenoRepository pismenoRepository,
                                    PrilogService prilogService,
                                    EgopAktiBezSifre bezSifre,
                                    @Value("${app.mail.max-message-size:20MB}") DataSize maxPoruka) {
        this.lookup = lookup;
        this.documentService = documentService;
        this.labels = labels;
        this.emailService = emailService;
        this.retryStore = retryStore;
        this.sealer = sealer;
        this.pismenoRepository = pismenoRepository;
        this.prilogService = prilogService;
        this.bezSifre = bezSifre;
        this.maxPoruka = maxPoruka.toBytes();
    }

    /**
     * <b>Namjerno bez {@code @Transactional}.</b> Slanje ide na SMTP poslužitelj; transakcija
     * na ovoj metodi držala bi konekciju otvorenom kroz to mrežno čekanje. Čitanja idu kroz
     * {@link RnLifecycleLookup} i {@link StrDocumentService}, koji imaju vlastite kratke
     * transakcije s {@code REQUIRES_NEW} — što ujedno rješava i to da je u {@code AFTER_COMMIT}
     * fazi izvorna transakcija već dovršena.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLifecycleChange(RnLifecycleEvent event) {
        deliver(event);
    }

    /**
     * Ponovni pokušaj za {@link MailRetryJob}. Događaj se rekonstruira iz revizijskog zapisa.
     * Obavijest o prijelazu koji je u međuvremenu nadjačan se ne šalje — stranka bi nakon
     * novije obavijesti dobila staru, o stanju koje više ne vrijedi.
     */
    public void retry(UUID logId) {
        RnLifecycleEvent event = lookup.findEvent(logId).orElse(null);
        if (event == null) {
            retryStore.abandon(MailRetryEntity.Kind.RN_LIFECYCLE, logId, "missing_log");
            return;
        }
        if (!lookup.isLatestTransition(event.rn(), logId)) {
            retryStore.abandon(MailRetryEntity.Kind.RN_LIFECYCLE, logId, "superseded");
            return;
        }
        // deliver() prijelaz bez akta tiho preskače; ovdje bi to zapis ostavilo zauvijek otvorenim.
        if (StrDocumentType.forTransition(event.to(), event.trigger()).isEmpty()) {
            retryStore.abandon(MailRetryEntity.Kind.RN_LIFECYCLE, logId, "no_act");
            return;
        }
        deliver(event);
    }

    private void deliver(RnLifecycleEvent event) {
        Optional<StrDocumentType> akt = StrDocumentType.forTransition(event.to(), event.trigger());
        if (akt.isEmpty()) {
            return;
        }
        MailTemplate template = mailTemplateFor(akt.get());

        RnDetailDto detail = lookup.findDetail(event.rn()).orElse(null);
        if (detail == null) {
            log.error("rn_lifecycle_mail_skipped reason=missing_detail rn={}", event.rn());
            retryStore.abandon(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "missing_detail");
            return;
        }
        if (detail.lessorEmail() == null || detail.lessorEmail().isBlank()) {
            log.warn("rn_lifecycle_mail_skipped reason=no_email rn={} template={}",
                    event.rn(), template);
            retryStore.abandon(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "no_email");
            return;
        }

        String razlog = razlog(akt.get(), event);
        Dostava dostava = dostava(akt.get(), detail);
        byte[] pdf;
        if (dostava == Dostava.EPOSTA && sealer.isEnabled()) {
            // Non-EU iznajmljivaču je mail dostava akta, pa nosi samo pečaćeni — onaj iz eGOP-a. Dok
            // pečata nema, poruka čeka u redu ponovnog slanja (MailRetryJob), umjesto da ode bez njega.
            pdf = aktZaDostavu(event, akt.get()).orElse(null);
            if (pdf == null) {
                log.warn("rn_lifecycle_mail_deferred reason=not_sealed rn={} template={}", event.rn(), template);
                retryStore.recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "not_sealed");
                return;
            }
        } else {
            pdf = aktPdf(akt.get(), event.rn(), razlog);
        }
        PriloziAkta prilozi = priloziAkta(event, pdf);
        RnLifecycleMail mail = new RnLifecycleMail(
                template,
                detail.lessorEmail(),
                ime(detail),
                event.rn(),
                objekt(detail),
                razlog,
                rok(akt.get(), detail),
                pdf,
                dostava,
                prilozi.uPrivitku(),
                prilozi.izvanPoruke());
        IshodSlanja ishod = emailService.sendRnLifecycleNotification(mail);
        if (ishod == IshodSlanja.PREVELIKA && !mail.prilozi().isEmpty()) {
            // Procjena je prošla, a poslužitelj ima manji limit: ista poruka bi se odbijala do
            // odustajanja, pa odmah ide varijanta s popisom priloga — non-EU stranci je to dostava.
            log.warn("rn_lifecycle_mail_too_large rn={} template={} prilozi={} — šalje se s popisom priloga"
                    + " umjesto privitaka; uskladiti app.mail.max-message-size s limitom poslužitelja",
                    event.rn(), template, mail.prilozi().size());
            ishod = emailService.sendRnLifecycleNotification(mail.bezPrivitakaPriloga());
        }
        if (ishod == IshodSlanja.POSLANA) {
            retryStore.markSent(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId());
        } else {
            retryStore.recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(),
                    ishod == IshodSlanja.PREVELIKA ? "message_too_large" : "smtp_failed");
        }
    }

    /** Prilozi akta: u privitku, ili samo nazivi kad su zajedno preveliki za e-poštu. */
    private record PriloziAkta(List<MailPrilog> uPrivitku, List<String> izvanPoruke) {
        static final PriloziAkta NEMA = new PriloziAkta(List.of(), List.of());
    }

    /**
     * Procjena veličine poruke iznad privitaka: HTML tijelo, zaglavlja i MIME granice. Tijelo je
     * nekoliko KB; rezerva pokriva i dugačke nazive datoteka u zaglavljima.
     */
    static final long ZAGLAVLJA_I_TIJELO = 64 * 1024;

    /**
     * Prilozi službenika uz akt ovog prijelaza (T17). Idu u privitak dok cijela poruka — akt i prilozi
     * kodirani u base64 (~4/3 sirove veličine) uz tijelo — stane u {@code app.mail.max-message-size};
     * iznad toga poruka ih samo navodi, jer bi je poslužitelj odbio cijelu. Prilozi stranke (opoziv)
     * se ne vraćaju. Pad čitanja ne smije spriječiti obavijest: ide bez priloga.
     */
    private PriloziAkta priloziAkta(RnLifecycleEvent event, byte[] aktPdf) {
        if (event.logId() == null) {
            return PriloziAkta.NEMA;
        }
        try {
            List<PrilogSazetak> prilozi = prilogService.zaVlasnika(PrilogVlasnik.prijelaz(event.logId())).stream()
                    .filter(p -> PrilogService.pripadaPismenu(p, true))
                    .toList();
            if (prilozi.isEmpty()) {
                return PriloziAkta.NEMA;
            }
            long procjena = procjenaPoruke(aktPdf == null ? 0 : aktPdf.length,
                    prilozi.stream().mapToLong(PrilogSazetak::getVelicina).sum());
            if (procjena > maxPoruka) {
                log.info("rn_lifecycle_mail_attachments_listed rn={} count={} estimated_bytes={} limit={}",
                        event.rn(), prilozi.size(), procjena, maxPoruka);
                return new PriloziAkta(List.of(), prilozi.stream().map(PrilogSazetak::getNazivDatoteke).toList());
            }
            return new PriloziAkta(prilozi.stream()
                    .map(p -> new MailPrilog(p.getNazivDatoteke(), p.getContentType(), prilogService.sadrzaj(p.getId())))
                    .toList(), List.of());
        } catch (RuntimeException e) {
            log.error("rn_lifecycle_mail_attachments_failed rn={} log={}: {} — mail ide bez priloga",
                    event.rn(), event.logId(), e.getMessage(), e);
            return PriloziAkta.NEMA;
        }
    }

    /** Kodirana veličina poruke: base64 s prijelomom retka svakih 76 znakova, plus tijelo i zaglavlja. */
    static long procjenaPoruke(long aktBajtova, long priloziBajtova) {
        return base64(aktBajtova) + base64(priloziBajtova) + ZAGLAVLJA_I_TIJELO;
    }

    private static long base64(long bajtova) {
        long znakova = (bajtova + 2) / 3 * 4;
        return znakova + znakova / 76 * 2;
    }

    /**
     * Akt ovog prijelaza za dostavu e-poštom uz uključen eSeal ({@code act_ref} = {@code log_id}):
     * pečaćeni, kad je eGOP tok stigao do pečata. Akt koji se <b>nikad</b> neće pečatirati ide
     * nepečaćen, inače bi poruka čekala do odustajanja: ulazni opoziv zapisan prije T15 (ulazna
     * pismena se ne pečate) i vrsta s popisa {@code str.egop.akti-bez-sifre} (ne urudžbira se, pa ni
     * ne pečatira).
     */
    private Optional<byte[]> aktZaDostavu(RnLifecycleEvent event, StrDocumentType type) {
        Optional<EgopPismenoEntity> pismeno = pismenoRepository.findFirstByRnAndActRefAndVrstaPismenaNaziv(
                event.rn(), event.logId().toString(), type.vrstaPismenaNaziv());
        if (pismeno.isEmpty()) {
            return Optional.empty();
        }
        EgopPismenoEntity p = pismeno.get();
        if (p.isSealed()) {
            return Optional.ofNullable(p.getPdfContent());
        }
        boolean nikadPecat = p.getSmjer() == EgopPismenoEntity.Smjer.ULAZNO || !bezSifre.urudzbiv(type);
        if (nikadPecat && p.getPdfContent() != null) {
            log.warn("rn_lifecycle_mail_unsealed_act rn={} akt={} vrsta='{}' reason={} — akt se ne pečatira,"
                            + " dostavlja se nepečaćen", event.rn(), p.getId(), p.getVrstaPismenaNaziv(),
                    p.getSmjer() == EgopPismenoEntity.Smjer.ULAZNO ? "ulazno_pismeno" : "vrsta_bez_sifre");
            return Optional.of(p.getPdfContent());
        }
        return Optional.empty();
    }

    /**
     * Non-EU iznajmljivač nema OIB pa ni pristup pretincu — njemu je e-pošta kanal dostave, a ne
     * samo obavijest. Ulazno pismeno eGOP ne otprema u korisnički pretinac i nije akt koji se
     * stranci dostavlja, pa poruka o dostavi ne bi govorila ništa; od T15 (opoziv je izlazni akt)
     * iz prijelaza takvo više ne nastaje, grana ostaje kao zaštita.
     */
    private static Dostava dostava(StrDocumentType type, RnDetailDto detail) {
        if (type.smjer() == StrDocumentType.Smjer.ULAZNO) {
            return Dostava.BEZ_KLAUZULE;
        }
        return detail.lessorOib() == null || detail.lessorOib().isBlank() ? Dostava.EPOSTA : Dostava.PRETINAC;
    }

    private static MailTemplate mailTemplateFor(StrDocumentType type) {
        return switch (type) {
            case SUSPENZIJA -> MailTemplate.SUSPENZIJA;
            case POVLACENJE -> MailTemplate.POVLACENJE;
            case OPOZIV -> MailTemplate.OPOZIV;
            case REAKTIVACIJA -> MailTemplate.REAKTIVACIJA;
            case PRIJEDLOG_SUSPENZIJE -> MailTemplate.PRIJEDLOG_SUSPENZIJE;
            case OBUSTAVA_SUSPENZIJE -> MailTemplate.OBUSTAVA_SUSPENZIJE;
            case PRIJEDLOG_POVLACENJA -> MailTemplate.PRIJEDLOG_POVLACENJA;
            case OBUSTAVA_POVLACENJA -> MailTemplate.OBUSTAVA_POVLACENJA;
            case REAKTIVACIJA_POVLACENJA -> MailTemplate.REAKTIVACIJA_POVLACENJA;
            // ZAHTJEV/DODJELA/PRIGOVOR ne nastaju iz prijelaza statusa.
            default -> throw new IllegalStateException("Nema poruke za vrstu akta " + type);
        };
    }

    /**
     * Akt uz obavijest. Pad rendera ne smije spriječiti mail — status je već promijenjen i
     * iznajmljivač o tome mora saznati, makar bez privitka.
     */
    private byte[] aktPdf(StrDocumentType type, String rn, String razlog) {
        try {
            return documentService.render(type, rn, razlog);
        } catch (RuntimeException e) {
            log.error("rn_lifecycle_pdf_failed rn={} type={}: {} — mail ide bez privitka",
                    rn, type, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Razlog po istom pravilu kao akt u privitku ({@link StrDocumentService#razlogAkta}): suspenzija i
     * obustava navode razlog s prijedloga suspenzije, ne procesnu bilješku svog prijelaza („Rok za
     * rješavanje nepravilnosti istekao.", „obustava postupka suspenzije").
     */
    private String razlog(StrDocumentType akt, RnLifecycleEvent event) {
        String razlog = documentService.razlogAkta(akt, event.rn(), Strings.blankToNull(event.reason()));
        return razlog != null && !razlog.isBlank() ? razlog.strip() : labels.trigger(event.trigger());
    }

    /** Rok za očitovanje po istom pravilu kao u aktu ({@link ZupContextFactory#rokOcitovanja}). */
    private String rok(StrDocumentType akt, RnDetailDto detail) {
        LocalDate rok = ZupContextFactory.rokOcitovanja(akt, detail);
        return rok == null
                ? labels.get("rok.default")
                : labels.format("rok.doDatuma", rok.format(DATE));
    }

    private String ime(RnDetailDto detail) {
        if (detail.lessorFirstName() != null && !detail.lessorFirstName().isBlank()) {
            return detail.lessorFirstName();
        }
        return detail.lessorLegalEntityName() == null ? "" : detail.lessorLegalEntityName();
    }

    private static String objekt(RnDetailDto detail) {
        String naziv = detail.accommodationName();
        String adresa = ((detail.street() == null ? "" : detail.street()) + " "
                + (detail.streetNumber() == null ? "" : detail.streetNumber())).strip();
        if (naziv == null || naziv.isBlank()) {
            return adresa;
        }
        return adresa.isEmpty() ? naziv : naziv + ", " + adresa;
    }
}
