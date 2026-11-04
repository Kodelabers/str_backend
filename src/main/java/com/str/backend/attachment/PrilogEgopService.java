package com.str.backend.attachment;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.common.VrstaDatoteke;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopClient;
import com.str.backend.egop.EgopNaziv;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPrilozi;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.egop.exception.EgopException;
import hr.infodom.egov.pismeno.KreirajPrilogIPridruziDokument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Prilaže priloge uz pismeno u eGOP-u ({@code KreirajPrilogIPridruziDokument}: novi prilog pismena
 * s elektroničkim dokumentom, jedan poziv po prilogu). Zove ga {@code EgopFilingService}:
 *
 * <ul>
 *   <li>ulazno pismeno zahtjeva ({@code act_ref=REGISTRACIJA}) ← prilozi {@link VrstaVlasnika#SUBMISSION};</li>
 *   <li>pismeno prijelaza RB-a ({@code act_ref=log_id}) ← prilozi {@link VrstaVlasnika#RN_LOG}, i to
 *       ulazno (podnesak stranke, npr. opoziv) samo prilozi stranke, a izlazno (akt) samo prilozi
 *       službenika ({@link PrilogService#pripadaPismenu}).</li>
 * </ul>
 *
 * <p>Prilozi se <b>ne pečate</b> — pečatira se samo akt. Idempotentno po prilogu: priloženi prilog
 * ima {@code egop_jop} pismena i preskače se; oznaka se upisuje odmah nakon uspješnog poziva, u
 * vlastitoj transakciji (kao {@code document_attached}). Pad baca iznimku i pismeno ide u retry.
 * {@code rbrPriloga} je redni broj priloga na pismenu (1, 2, …) po redoslijedu učitavanja, pa je
 * isti i na ponovljenom pokušaju.
 *
 * <p><b>Bez transakcije</b> preko SOAP poziva — kao i ostatak eGOP toka.
 */
@Service
public class PrilogEgopService implements EgopPrilozi {

    private static final Logger log = LoggerFactory.getLogger(PrilogEgopService.class);

    private static final String ZAHTJEV_NAZIV = StrDocumentType.ZAHTJEV.vrstaPismenaNaziv();

    private final PrilogRepository repository;
    private final EgopClient egopClient;
    private final String vrstaSifra;
    private final String vrstaNaziv;

    public PrilogEgopService(PrilogRepository repository,
                             EgopClient egopClient,
                             // Šifra vrste priloga u eGOP šifrarniku (MDM DohvatiVrstePrilogaActive);
                             // bez nje se razrješava po nazivu, kao vrste pismena.
                             @Value("${str.egop.prilog.vrsta-sifra:}") String vrstaSifra,
                             @Value("${str.egop.prilog.vrsta-naziv:Prilog}") String vrstaNaziv) {
        this.repository = repository;
        this.egopClient = egopClient;
        this.vrstaSifra = vrstaSifra;
        this.vrstaNaziv = vrstaNaziv;
    }

    @Override
    public void prilozi(EgopPismenoEntity pismeno, String korisnik) throws EgopException {
        List<PrilogSazetak> prilozi = priloziPismena(pismeno);
        if (prilozi.isEmpty()) {
            return;
        }
        if (!pismeno.isFiled()) {
            throw new EgopBadRequestException("Pismeno " + pismeno.getId() + " nema JOP — prilozi se ne mogu priložiti");
        }
        int jop = pismeno.getJop();
        Integer vrstaPriloga = null;
        int rbr = 0;
        int prilozeno = 0;
        for (PrilogSazetak prilog : prilozi) {
            rbr++;
            if (Objects.equals(prilog.getEgopJop(), jop)) {
                continue;
            }
            if (vrstaPriloga == null) {
                vrstaPriloga = vrstaPriloga();
            }
            KreirajPrilogIPridruziDokument request = new KreirajPrilogIPridruziDokument();
            request.setUserName(korisnik);
            request.setJop(jop);
            request.setRbrPriloga(rbr);
            request.setIdVrstaPriloga(vrstaPriloga);
            request.setNazivPriloga(prilog.getNazivDatoteke());
            request.setExtension(ekstenzija(prilog));
            request.setAttachment(repository.findSadrzaj(prilog.getId()));
            egopClient.kreirajPrilogIPridruziDokument(request);
            repository.markEgopPrilozeno(prilog.getId(), jop, rbr, Instant.now());
            prilozeno++;
        }
        if (prilozeno > 0) {
            log.info("egop_prilozi_ok pismeno={} jop={} vrsta='{}' prilozeno={} ukupno={}",
                    pismeno.getId(), jop, pismeno.getVrstaPismenaNaziv(), prilozeno, prilozi.size());
        }
    }

    /** Prilozi koji idu uz ovo pismeno; prazno za pismena bez priloga (npr. obavijest o dodjeli). */
    List<PrilogSazetak> priloziPismena(EgopPismenoEntity pismeno) {
        if (EgopPismenoEntity.ACT_REF_REGISTRACIJA.equals(pismeno.getActRef())) {
            return pismeno.getSmjer() == EgopPismenoEntity.Smjer.ULAZNO
                    && ZAHTJEV_NAZIV.equals(pismeno.getVrstaPismenaNaziv())
                    ? repository.findSazeciVlasnika(VrstaVlasnika.SUBMISSION, pismeno.getSubmissionId())
                    : List.of();
        }
        Optional<UUID> logId = uuid(pismeno.getActRef());
        if (logId.isEmpty()) {
            return List.of();
        }
        boolean izlazno = pismeno.getSmjer() == EgopPismenoEntity.Smjer.IZLAZNO;
        return repository.findSazeciVlasnika(VrstaVlasnika.RN_LOG, logId.get()).stream()
                .filter(p -> PrilogService.pripadaPismenu(p, izlazno))
                .toList();
    }

    private int vrstaPriloga() throws EgopBadRequestException {
        if (vrstaSifra != null && !vrstaSifra.isBlank()) {
            try {
                return Integer.parseInt(vrstaSifra.trim());
            } catch (NumberFormatException e) {
                throw new EgopBadRequestException("str.egop.prilog.vrsta-sifra nije broj: '" + vrstaSifra + "'");
            }
        }
        Integer id = EgopNaziv.resolveId(egopClient.getVrstePriloga(), vrstaNaziv);
        if (id == null) {
            throw new EgopBadRequestException("eGOP šifrarnik nema vrstu priloga '" + vrstaNaziv
                    + "' — zadati str.egop.prilog.vrsta-sifra ili ispravan str.egop.prilog.vrsta-naziv");
        }
        return id;
    }

    private static String ekstenzija(PrilogSazetak prilog) {
        return VrstaDatoteke.poContentType(prilog.getContentType())
                .map(VrstaDatoteke::ekstenzija)
                .orElse("pdf");
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
