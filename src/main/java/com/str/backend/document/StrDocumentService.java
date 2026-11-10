package com.str.backend.document;

import com.str.backend.auth.internal.InternalUserRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.dto.RnDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Generira akte životnog ciklusa registracijskog broja po strukturi iz čl. 98. ZUP-a.
 *
 * <p>Zamjenjuje raniji {@code RnDocumentService}, koji je akt ispisivao kao naslov, pet
 * label/value redaka i jedan odlomak proze — bez zaglavlja, uvoda, izreke, obrazloženja i
 * upute o pravnom lijeku.
 *
 * <p><b>Dostava nije ovdje.</b> Servis samo renderira PDF; dostava u korisnički pretinac
 * (koja po čl. 94. st. 6 ZUP-a jedina vrijedi kao osobna dostava i od koje teku rokovi) te
 * urudžbiranje u eGOP su zasebni koraci.
 */
@Service
public class StrDocumentService {

    private static final Logger log = LoggerFactory.getLogger(StrDocumentService.class);

    /** Akter službenika u {@code registration_number_log} ({@code InternalAccessService.actorFor}). */
    private static final String INTERNAL_PREFIX = "INTERNAL:";

    private final RnRepository rnRepository;
    private final LessorRepository lessorRepository;
    private final SubmissionRepository submissionRepository;
    private final RegistrationNumberLogRepository logRepository;
    private final ZupTemplateLoader templates;
    private final ZupContextFactory contextFactory;
    private final ZupDocumentRenderer renderer;
    private final DocumentLabels labels;
    private final InternalUserRepository internalUsers;

    public StrDocumentService(RnRepository rnRepository,
                              LessorRepository lessorRepository,
                              SubmissionRepository submissionRepository,
                              RegistrationNumberLogRepository logRepository,
                              ZupTemplateLoader templates,
                              ZupContextFactory contextFactory,
                              ZupDocumentRenderer renderer,
                              DocumentLabels labels,
                              InternalUserRepository internalUsers) {
        this.rnRepository = rnRepository;
        this.lessorRepository = lessorRepository;
        this.submissionRepository = submissionRepository;
        this.logRepository = logRepository;
        this.templates = templates;
        this.contextFactory = contextFactory;
        this.renderer = renderer;
        this.labels = labels;
        this.internalUsers = internalUsers;
    }

    public byte[] render(StrDocumentType type, String rn, String razlog) {
        return render(type, rn, razlog, null);
    }

    /**
     * {@code REQUIRES_NEW} jer se ovo zove i iz {@code AFTER_COMMIT} konteksta
     * ({@code EgopRegistrationDispatcher} preko {@code RnIssuedListener}, i
     * {@code RnLifecycleEmailListener}): ondje je izvorna transakcija već dovršena, pa bi je
     * zadani {@code REQUIRED} pokušao nastaviti. Isti razlog stoji iza {@code REQUIRES_NEW}
     * na svakoj metodi {@code EgopFilingStore}.
     *
     * <p>Čitanja su kratka i idu prije rendera; sam PDF se sastavlja izvan upita, ali unutar
     * ove transakcije — riječ je o desecima milisekundi CPU-a, ne o mrežnom čekanju kakvo je
     * ranije držalo eGOP transakciju otvorenom.
     *
     * @param razlog slobodan tekst razloga; {@code null} → uzima se iz revizijskog traga
     * @param filing urudžbene oznake; {@code null} → KLASA se čita sa submissiona, URBROJ se
     *               izostavlja (akti suspenzijskog toka se još ne urudžbiraju — BX1/BX2/BX3)
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public byte[] render(StrDocumentType type, String rn, String razlog, FilingReference filing) {
        if (!type.templateBacked()) {
            throw new DocumentTemplateException(
                    "Vrsta akta " + type + " ima vlastiti generator i ne renderira se iz predloška.");
        }
        RnDetailDto detail = rnRepository.findDetail(rn)
                .orElseThrow(() -> new ResourceNotFoundException("rn not found: " + rn));

        FilingReference oznake = filing != null ? filing
                : new FilingReference(klasaZa(type, detail), null);

        Map<String, String> ctx = contextFactory.forRn(type, detail, razlogAkta(type, rn, razlog), oznake);
        dopuniAdresu(ctx, detail);
        dopuniSluzbenika(ctx, type, rn);
        dopuniDatumPrijelaza(ctx, type, rn);

        byte[] pdf = renderer.render(templates.get(type), ctx);
        log.info("document_rendered type={} rn={} klasa={} bytes={}",
                type, rn, oznake.klasa(), pdf.length);
        return pdf;
    }

    /**
     * KLASA je na predmetu, a predmet na submissionu. Zahtjev, obavijest o dodjeli i zahtjev za
     * promjenu podataka (odnosi se na registrirane podatke, ne urudžbira se) nose klasu registracije
     * (334-06/10), akti nakon dodjele klasu predmeta akata (334-06/11). Dok predmet akata
     * ne postoji (prvi akt RB-a, prije urudžbe), klase nema — bolje nego klasa tuđeg predmeta;
     * konačni dokument s klasom i urbrojem renderira se nakon urudžbe ({@code EgopAktDispatcher}).
     */
    private String klasaZa(StrDocumentType type, RnDetailDto detail) {
        if (detail.submissionId() == null) {
            return null;
        }
        boolean registracija = type == StrDocumentType.DODJELA || type == StrDocumentType.ZAHTJEV
                || type == StrDocumentType.ZAHTJEV_PROMJENE_PODATAKA;
        return submissionRepository.findById(detail.submissionId())
                .map(s -> registracija ? s.getEgopKlasa() : s.getEgopAktiKlasa())
                .orElse(null);
    }

    /**
     * Proslijeđeni razlog vrijedi, osim za akte koji razlog čitaju s prijedloga suspenzije: pozivatelji
     * ({@code RnLifecycleFilingListener}, {@code EgopAktDispatcher}) prosljeđuju razlog <i>svog</i>
     * prijelaza, a za suspenziju istekom roka to je bilješka joba („Rok za rješavanje nepravilnosti
     * istekao.") — procesni okidač na mjestu materijalnog razloga, kao na aktu koji je MINTS
     * komentirao. Zato za te akte vrijedi revizijski trag (vidi {@link #razlogIzTraga}).
     *
     * <p><b>Jedino pravilo razloga akta</b> — po njemu ga ispisuje i obavijest e-poštom
     * ({@code RnLifecycleEmailListener}), da poruka i akt u privitku ne navode različit razlog.
     * {@code REQUIRES_NEW} kao {@link #render(StrDocumentType, String, String, FilingReference)}.
     *
     * @return razlog za ispis; {@code null} kad ga nema (akt tada piše „razlog nije naveden")
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public String razlogAkta(StrDocumentType type, String rn, String razlog) {
        if (razlog == null || razlogSPrijedloga(type)
                || (type == StrDocumentType.POVLACENJE && povucenIstekomRoka(rn))) {
            return razlogIzTraga(type, rn);
        }
        return razlog;
    }

    /**
     * Akti čiji prijelaz uvijek nosi samo procesni okidač (istek roka, obustava), pa razlog uzimaju s
     * prijedloga postupka. Povlačenje to radi kad je broj povučen istekom roka
     * ({@link #povucenIstekomRoka}) — od T12 jedini način; povijesno izravno povlačenje službenika
     * ({@code WITHDRAWAL}) nosi razlog na svom prijelazu.
     */
    private static boolean razlogSPrijedloga(StrDocumentType type) {
        return type == StrDocumentType.SUSPENZIJA || type == StrDocumentType.OBUSTAVA_SUSPENZIJE
                || type == StrDocumentType.OBUSTAVA_POVLACENJA;
    }

    /**
     * Akti čiji prijelaz može napraviti sustav (istek roka) — tada ih potpisuje službenik koji je
     * postupak pokrenuo prijedlogom.
     */
    private static boolean potpisSPrijedloga(StrDocumentType type) {
        return razlogSPrijedloga(type) || type == StrDocumentType.POVLACENJE;
    }

    /** Je li zadnji prijelaz u WITHDRAWN povlačenje istekom roka za očitovanje (T12). */
    private boolean povucenIstekomRoka(String rn) {
        return zadnjiUlazak(rn, RnStatus.WITHDRAWN)
                .map(z -> RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED.name().equals(z.getTriggerName()))
                .orElse(false);
    }

    /**
     * Razlog iz {@code registration_number_log}. {@code RnService.suspend} ne prosljeđuje
     * slobodan tekst, pa u praksi ostaje natpis okidača — što je i točnije od proizvoljnog
     * {@code ?reason=} parametra sa zahtjeva.
     *
     * <p><b>Koji zapis.</b> Suspenzija je dvofazna: materijalni razlog (istek suglasnosti, nalaz
     * nadzora…) zapisan je na prijelazu u {@code SUSPENSION_PROPOSED}, a zadnji prijelaz nosi samo
     * procesni okidač — {@code DEADLINE_EXCEEDED} za suspenziju, {@code REVOKE_PROPOSAL} za
     * obustavu. Iz zadnjeg zapisa je zato obustava ispisivala „pokrenut zbog sljedećeg razloga:
     * obustava postupka suspenzije", a suspenzija „zbog sljedećeg razloga: istek roka za
     * očitovanje" — procesni okidač na mjestu na kojem čl. 98. st. 3 i 5 traže materijalni razlog.
     * Zato ta dva akta razlog čitaju s prijedloga, a ostali sa svog (zadnjeg) prijelaza.
     *
     * <p>Povlačenje je od T12 dvofazno na isti način: prijedlog i obustava povlačenja čitaju razlog s
     * prijedloga povlačenja, a povlačenje istekom roka također (bilješka posla nije razlog). Povijesno
     * izravno povlačenje ({@code WITHDRAWAL}, bez prijedloga) čita svoj prijelaz kao i dosad.
     *
     * <p>Zahtjev za opoziv je podnesak stranke: dobiva samo slobodan tekst koji je iznajmljivač
     * upisao, nikad natpis okidača.
     */
    private String razlogIzTraga(StrDocumentType type, String rn) {
        Optional<RegistrationNumberLogEntity> zapisi = switch (type) {
            // Jednofazna suspenzija iz starijih podataka nema prijedlog u tragu — tada zadnji zapis.
            case SUSPENZIJA, OBUSTAVA_SUSPENZIJE, OBUSTAVA_POVLACENJA, PRIJEDLOG_POVLACENJA ->
                    prijedlog(type, rn).or(() -> logRepository.findFirstByRnOrderByOccurredAtDesc(rn));
            case POVLACENJE -> zapisPovlacenja(rn);
            default -> logRepository.findFirstByRnOrderByOccurredAtDesc(rn);
        };
        if (zapisi.isEmpty()) {
            return null;
        }
        RegistrationNumberLogEntity zapis = zapisi.get();
        if (zapis.getReason() != null && !zapis.getReason().isBlank()) {
            return zapis.getReason();
        }
        if (type == StrDocumentType.ZAHTJEV_OPOZIVA) {
            // Podnesak stranke navodi samo njezin razlog — natpis okidača bi joj podmetnuo tekst
            // koji nije napisala.
            return null;
        }
        if (zapis.getTriggerName() == null) {
            return null;
        }
        try {
            return labels.trigger(RnTrigger.valueOf(zapis.getTriggerName()));
        } catch (IllegalArgumentException e) {
            // Okidač iz starijeg zapisa koji više ne postoji u enumu — bolje ništa nego kriv razlog.
            log.warn("document_unknown_trigger rn={} trigger={}", rn, zapis.getTriggerName());
            return null;
        }
    }

    /**
     * Dan od kojeg akt djeluje ({@code ${prijelaz.datum}}): prijelaz u status koji akt izriče, a ne
     * zadnji zapis traga — akt se renderira i kasnije (konačni dokument nakon urudžbe, na zahtjev).
     */
    private void dopuniDatumPrijelaza(Map<String, String> ctx, StrDocumentType type, String rn) {
        zapisPrijelaza(type, rn)
                .ifPresent(zapis -> ZupContextFactory.putDatumPrijelaza(ctx, zapis.getOccurredAt()));
    }

    /**
     * Zapis prijelaza koji akt izriče — za datum učinka i potpisnika. Akti koji vode u ACTIVE traže se
     * po okidaču, jer u ACTIVE vodi više prijelaza (obustava suspenzije, reaktivacija, obustava
     * povlačenja, uvaženi prigovor na povlačenje).
     */
    private Optional<RegistrationNumberLogEntity> zapisPrijelaza(StrDocumentType type, String rn) {
        return switch (type) {
            case PRIJEDLOG_SUSPENZIJE -> prijedlogSuspenzije(rn);
            case SUSPENZIJA -> zadnjiUlazak(rn, RnStatus.SUSPENDED);
            case OBUSTAVA_SUSPENZIJE -> poOkidacu(rn, RnTrigger.REVOKE_PROPOSAL);
            case REAKTIVACIJA -> poOkidacu(rn, RnTrigger.REACTIVATE);
            case PRIJEDLOG_POVLACENJA -> prijedlogPovlacenja(rn);
            case OBUSTAVA_POVLACENJA -> poOkidacu(rn, RnTrigger.REVOKE_WITHDRAWAL_PROPOSAL);
            case REAKTIVACIJA_POVLACENJA -> poOkidacu(rn, RnTrigger.REINSTATE);
            case POVLACENJE -> zadnjiUlazak(rn, RnStatus.WITHDRAWN);
            case OPOZIV, ZAHTJEV_OPOZIVA -> zadnjiUlazak(rn, RnStatus.REVOKED);
            default -> Optional.empty();
        };
    }

    /** Zadnji prijelaz zadanim okidačem — ulazak u ACTIVE ima više okidača (obustave, reaktivacije). */
    private Optional<RegistrationNumberLogEntity> poOkidacu(String rn, RnTrigger trigger) {
        return logRepository.findFirstByRnAndTriggerNameOrderByOccurredAtDesc(rn, trigger.name());
    }

    private Optional<RegistrationNumberLogEntity> zadnjiUlazak(String rn, RnStatus status) {
        return logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(rn, status.name());
    }

    /** Prijedlog postupka kojem akt pripada: povlačenja za akte povlačenja, inače suspenzije. */
    private Optional<RegistrationNumberLogEntity> prijedlog(StrDocumentType type, String rn) {
        return switch (type) {
            case PRIJEDLOG_POVLACENJA, OBUSTAVA_POVLACENJA, POVLACENJE -> prijedlogPovlacenja(rn);
            default -> prijedlogSuspenzije(rn);
        };
    }

    private Optional<RegistrationNumberLogEntity> prijedlogSuspenzije(String rn) {
        return zadnjiUlazak(rn, RnStatus.SUSPENSION_PROPOSED);
    }

    private Optional<RegistrationNumberLogEntity> prijedlogPovlacenja(String rn) {
        return zadnjiUlazak(rn, RnStatus.WITHDRAWAL_PROPOSED);
    }

    /**
     * Zapis s kojeg akt povlačenja čita razlog: prijelaz u WITHDRAWN, osim povlačenja istekom roka,
     * koje razlog uzima s prijedloga povlačenja. Bez prijelaza u tragu zadnji zapis, kao i dosad.
     */
    private Optional<RegistrationNumberLogEntity> zapisPovlacenja(String rn) {
        Optional<RegistrationNumberLogEntity> povlacenje = zadnjiUlazak(rn, RnStatus.WITHDRAWN);
        if (povlacenje.isPresent()
                && RnTrigger.WITHDRAWAL_DEADLINE_EXCEEDED.name().equals(povlacenje.get().getTriggerName())) {
            return prijedlogPovlacenja(rn).or(() -> povlacenje);
        }
        return povlacenje.or(() -> logRepository.findFirstByRnOrderByOccurredAtDesc(rn));
    }

    /**
     * Potpis akta službenika (MINTS: „oznaka naziva službene dužnosti i osobno ime službene osobe"):
     * ime službenika koji je napravio prijelaz akta ({@code INTERNAL:<oib>} u revizijskom tragu), iz
     * {@code str.application_user}. Suspenziju istekom roka i obustavu bez službenika radi sustav
     * ({@code SYSTEM}), pa ih potpisuje službenik koji je postupak pokrenuo prijedlogom — kao što
     * MINTS za urudžbeni broj traži „službenika koji je pokrenuo postupak".
     *
     * <p>Naziv službene dužnosti {@code str.application_user} ne nosi, pa se ne ispisuje. Bez
     * službenika (stari zapis, nedostupna shema {@code str}) potpis ostaje naziv tijela.
     */
    private void dopuniSluzbenika(Map<String, String> ctx, StrDocumentType type, String rn) {
        if (!type.potpisujeSluzbenik()) {
            return;
        }
        // Zapis prijelaza koji akt izriče, a ne zadnji zapis traga: akt se renderira i kasnije (konačni
        // dokument nakon urudžbe, na zahtjev), kad je zadnji prijelaz već neki drugi (npr. REINSTATE).
        Optional<String> oib = zapisPrijelaza(type, rn)
                .or(() -> logRepository.findFirstByRnOrderByOccurredAtDesc(rn))
                .flatMap(StrDocumentService::oibSluzbenika);
        if (oib.isEmpty() && potpisSPrijedloga(type)) {
            oib = prijedlog(type, rn).flatMap(StrDocumentService::oibSluzbenika);
        }
        oib.flatMap(o -> imeSluzbenika(o, rn)).ifPresent(ime -> ZupContextFactory.putSluzbenik(ctx, ime));
    }

    private static Optional<String> oibSluzbenika(RegistrationNumberLogEntity zapis) {
        String actor = zapis.getActor();
        if (actor == null || !actor.startsWith(INTERNAL_PREFIX)) {
            return Optional.empty();
        }
        String oib = actor.substring(INTERNAL_PREFIX.length()).strip();
        return oib.isEmpty() ? Optional.empty() : Optional.of(oib);
    }

    private Optional<String> imeSluzbenika(String oib, String rn) {
        try {
            Optional<String> ime = Optional.ofNullable(internalUsers.findNames(Set.of(oib)).get(oib));
            if (ime.isEmpty()) {
                log.warn("document_signer_unknown rn={} — službenik nema ime u str.application_user;"
                        + " akt potpisuje tijelo", rn);
            }
            return ime;
        } catch (DataAccessException e) {
            // Akt se ne smije srušiti zbog potpisa: shema str je eTurizmova i zna biti nedostupna.
            log.warn("document_signer_lookup_failed rn={}: {} — akt potpisuje tijelo", rn, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Adresa prebivališta iznajmljivača nije na {@link RnDetailDto}: taj DTO puni akte i
     * mailove, a ekran detalja RB-a adresu dobiva odvojeno ({@code RnDetailExtras}, samo
     * vlasniku i službeniku s rolom).
     */
    private void dopuniAdresu(Map<String, String> ctx, RnDetailDto detail) {
        if (detail.lessorId() == null) {
            return;
        }
        Optional<LessorEntity> lessor = lessorRepository.findById(detail.lessorId());
        lessor.ifPresent(l -> ZupContextFactory.putStrankaAdresa(
                ctx, l.getStreet(), l.getStreetNumber(), l.getPlace(), null));
    }
}
