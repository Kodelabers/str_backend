package com.str.backend.email;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
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
    private final long priloziMaxUkupno;

    public RnLifecycleEmailListener(RnLifecycleLookup lookup,
                                    StrDocumentService documentService,
                                    DocumentLabels labels,
                                    EmailService emailService,
                                    MailRetryStore retryStore,
                                    PdfSealer sealer,
                                    EgopPismenoRepository pismenoRepository,
                                    PrilogService prilogService,
                                    @Value("${app.mail.attachments-max-total:15MB}") DataSize priloziMaxUkupno) {
        this.lookup = lookup;
        this.documentService = documentService;
        this.labels = labels;
        this.emailService = emailService;
        this.retryStore = retryStore;
        this.sealer = sealer;
        this.pismenoRepository = pismenoRepository;
        this.prilogService = prilogService;
        this.priloziMaxUkupno = priloziMaxUkupno.toBytes();
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

        String razlog = razlog(event);
        Dostava dostava = dostava(akt.get(), detail);
        byte[] pdf;
        if (dostava == Dostava.EPOSTA && sealer.isEnabled()) {
            // Non-EU iznajmljivaču je mail dostava akta, pa nosi samo pečaćeni — onaj iz eGOP-a. Dok
            // pečata nema, poruka čeka u redu ponovnog slanja (MailRetryJob), umjesto da ode bez njega.
            pdf = pecaceniAkt(event, akt.get()).orElse(null);
            if (pdf == null) {
                log.warn("rn_lifecycle_mail_deferred reason=not_sealed rn={} template={}", event.rn(), template);
                retryStore.recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "not_sealed");
                return;
            }
        } else {
            pdf = aktPdf(akt.get(), event.rn(), razlog);
        }
        PriloziAkta prilozi = priloziAkta(event);
        boolean sent = emailService.sendRnLifecycleNotification(new RnLifecycleMail(
                template,
                detail.lessorEmail(),
                ime(detail),
                event.rn(),
                objekt(detail),
                razlog,
                rok(detail),
                pdf,
                dostava,
                prilozi.uPrivitku(),
                prilozi.izvanPoruke()));
        if (sent) {
            retryStore.markSent(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId());
        } else {
            retryStore.recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "smtp_failed");
        }
    }

    /** Prilozi akta: u privitku, ili samo nazivi kad su zajedno preveliki za e-poštu. */
    private record PriloziAkta(List<MailPrilog> uPrivitku, List<String> izvanPoruke) {
        static final PriloziAkta NEMA = new PriloziAkta(List.of(), List.of());
    }

    /**
     * Prilozi službenika uz akt ovog prijelaza (T17). Ukupno do {@code app.mail.attachments-max-total}
     * idu u privitak; iznad toga poruka ih samo navodi — 5 × 10 MB premašuje uobičajeni limit
     * poštanskog poslužitelja, pa bi poruka inače pala cijela. Prilozi stranke (opoziv) se ne
     * vraćaju. Pad čitanja ne smije spriječiti obavijest: ide bez priloga.
     */
    private PriloziAkta priloziAkta(RnLifecycleEvent event) {
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
            long ukupno = prilozi.stream().mapToLong(PrilogSazetak::getVelicina).sum();
            if (ukupno > priloziMaxUkupno) {
                log.info("rn_lifecycle_mail_attachments_listed rn={} count={} bytes={} limit={}",
                        event.rn(), prilozi.size(), ukupno, priloziMaxUkupno);
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

    /** Pečaćeni akt ovog prijelaza, kad je eGOP tok stigao do pečata ({@code act_ref} = {@code log_id}). */
    private Optional<byte[]> pecaceniAkt(RnLifecycleEvent event, StrDocumentType type) {
        return pismenoRepository.findFirstByRnAndActRefAndVrstaPismenaNaziv(event.rn(), event.logId().toString(),
                        type.vrstaPismenaNaziv())
                .filter(EgopPismenoEntity::isSealed)
                .map(EgopPismenoEntity::getPdfContent);
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

    private String razlog(RnLifecycleEvent event) {
        if (event.reason() != null && !event.reason().isBlank()) {
            return event.reason().strip();
        }
        return labels.trigger(event.trigger());
    }

    private String rok(RnDetailDto detail) {
        return detail.suspensionDeadline() == null
                ? labels.get("rok.default")
                : labels.format("rok.doDatuma", detail.suspensionDeadline().format(DATE));
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
