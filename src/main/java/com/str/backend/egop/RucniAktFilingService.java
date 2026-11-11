package com.str.backend.egop;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.kp.api.KpAkt;
import com.str.backend.kp.api.KpPorukaService;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RucniAkt;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Akt županijskog službenika s ručnom KLASOM i URBROJEM (T16): eGOP se ne koristi.
 *
 * <p>Dvije odvojene transakcije, da pad jednog koraka ne poništi drugi:
 * <ol>
 *   <li>{@link #zapisi} — PDF (zaglavlje županije, ručne oznake, bez e-pečata — {@link StrDocumentService} ih
 *       čita sa zapisa prijelaza) → {@code egop_pismeno} kao ručni i {@code SYNCED} (retry, eGOP prilozi i eGOP KP
 *       otprema ga time preskaču);</li>
 *   <li>{@link #zabiljeziZaKp} — poruke u korisnički pretinac preko KP API-ja, samo fizičkoj osobi s OIB-om.
 *       Pravna osoba i non-EU iznajmljivač dobivaju akt samo e-poštom ({@code RnLifecycleEmailListener}).</li>
 * </ol>
 */
@Service
public class RucniAktFilingService {

    private static final Logger log = LoggerFactory.getLogger(RucniAktFilingService.class);

    private static final Pattern OIB = Pattern.compile("\\d{11}");
    private static final Locale HR = Locale.forLanguageTag("hr");

    private final StrDocumentService documentService;
    private final EgopPismenoRepository pismenoRepository;
    private final RnRepository rnRepository;
    private final PrilogService prilogService;
    private final KpPorukaService kpPoruke;

    public RucniAktFilingService(StrDocumentService documentService, EgopPismenoRepository pismenoRepository,
                                 RnRepository rnRepository, PrilogService prilogService, KpPorukaService kpPoruke) {
        this.documentService = documentService;
        this.pismenoRepository = pismenoRepository;
        this.rnRepository = rnRepository;
        this.prilogService = prilogService;
        this.kpPoruke = kpPoruke;
    }

    /** Zapisuje ručni akt (PDF i pismeno). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EgopPismenoEntity zapisi(RnLifecycleEvent event, UUID submissionId, StrDocumentType type, String actRef,
                                    RucniAkt rucni) {
        byte[] pdf = documentService.render(type, event.rn(), event.reason());
        EgopPismenoEntity akt = pismenoRepository.save(EgopPismenoEntity.rucniAkt(submissionId, event.rn(), actRef,
                type.vrstaPismenaNaziv(), pdf, rucni.klasa(), rucni.urBroj()));
        log.info("rucni_akt_zapisan rn={} akt={} vrsta='{}' klasa={} urbroj={} zu_rb={} bytes={}", event.rn(),
                akt.getId(), type.vrstaPismenaNaziv(), rucni.klasa(), rucni.urBroj(), rucni.izdavateljZuRb(), pdf.length);
        return akt;
    }

    /** Bilježi zapisani ručni akt za korisnički pretinac (fizička osoba s OIB-om). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void zabiljeziZaKp(RnLifecycleEvent event, StrDocumentType type, EgopPismenoEntity akt, RucniAkt rucni) {
        RnDetailDto detail = rnRepository.findDetail(event.rn()).orElse(null);
        if (detail == null) {
            log.warn("rucni_akt_bez_kp rn={} akt={} — RB nije pronađen pri slanju u KP, akt ide samo e-poštom",
                    event.rn(), akt.getId());
            return;
        }
        if (!fizickaOsobaSOib(detail)) {
            log.info("rucni_akt_bez_kp rn={} akt={} — primatelj nije fizička osoba s OIB-om, akt ide samo e-poštom",
                    event.rn(), akt.getId());
            return;
        }
        if (rucni.izdavateljZuRb() == null) {
            log.warn("rucni_akt_bez_kp rn={} akt={} — ručni akt bez županije izdavatelja, ne zna se tko šalje "
                    + "u KP; akt ide samo e-poštom", event.rn(), akt.getId());
            return;
        }
        int velicina = akt.getPdfContent() == null ? 0 : akt.getPdfContent().length;
        kpPoruke.zabiljezi(new KpAkt(akt.getId(), event.rn(), detail.lessorOib().strip(), type.name(),
                rucni.izdavateljZuRb(), naslov(type, event.rn()), sadrzaj(type, event.rn(), rucni),
                rucni.klasa(), rucni.urBroj(), type.slug() + "-" + event.rn() + ".pdf", velicina,
                prilozi(event)));
    }

    private static boolean fizickaOsobaSOib(RnDetailDto detail) {
        return !Boolean.TRUE.equals(detail.legalEntityOwner())
                && detail.lessorOib() != null && OIB.matcher(detail.lessorOib().strip()).matches();
    }

    /** Prilozi službenika uz prijelaz (T17) — oni idu uz izlazni akt. */
    private List<KpAkt.Prilog> prilozi(RnLifecycleEvent event) {
        if (event.logId() == null) {
            return List.of();
        }
        return prilogService.zaVlasnika(PrilogVlasnik.prijelaz(event.logId())).stream()
                .filter(p -> PrilogService.pripadaPismenu(p, true))
                .map(RucniAktFilingService::prilog)
                .toList();
    }

    private static KpAkt.Prilog prilog(PrilogSazetak p) {
        return new KpAkt.Prilog(p.getId(), p.getNazivDatoteke(), p.getContentType(), p.getVelicina());
    }

    static String naslov(StrDocumentType type, String rn) {
        return type.vrstaPismenaNaziv() + " " + rn;
    }

    static String sadrzaj(StrDocumentType type, String rn, RucniAkt rucni) {
        String izdavatelj = rucni.izdavateljNaziv() == null ? "" : rucni.izdavateljNaziv().toUpperCase(HR);
        return "Poštovani,\n\n"
                + "u privitku Vam dostavljamo akt „" + type.vrstaPismenaNaziv() + "“ za registracijski broj " + rn
                + " (KLASA: " + rucni.klasa() + ", URBROJ: " + rucni.urBroj() + ").\n\n"
                + "S poštovanjem,\n" + izdavatelj;
    }
}
