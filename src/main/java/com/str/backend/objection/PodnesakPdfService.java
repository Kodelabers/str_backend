package com.str.backend.objection;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.document.DocumentProperties;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentType;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.ObjectGroupEntity;
import com.str.backend.lookup.ObjectGroupPolicy;
import com.str.backend.pdf.PdfObrazac;
import com.str.backend.pdf.PodnesakPdfContext;
import com.str.backend.pdf.PodnesakPdfGenerator;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RucniAkt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PDF podneska stranke na akt (očitovanje, prigovor) — glavni dokument njegova ulaznog pismena (T12). Skuplja
 * podatke s prigovora, RB-a, iznajmljivača i objekta za {@link PodnesakPdfGenerator}.
 *
 * <ul>
 *   <li>županijski postupak: renderira se pri ručnom urudžbiranju, s KLASOM i URBROJ-em iz forme i
 *       zaglavljem županije prijavljenog službenika ({@link #zaglavlje(RucniAkt)});</li>
 *   <li>postupak MINTS-a: renderira se nakon {@code KreirajPismeno2}, s KLASOM i URBROJ-em iz eGOP-a i
 *       zaglavljem Ministarstva ({@link #zaglavljeMinistarstva()}), pa se prilaže kao glavni dokument;</li>
 *   <li>urudžbirano pismeno bez PDF-a (zapisano prije ove promjene): {@link #zaPismeno} ga izvodi iz retka.</li>
 * </ul>
 *
 * <p>Dvije ulazne točke iste logike: {@link #render} čita u transakciji pozivatelja (urudžbiranje drži zaključan
 * RB — druga konekcija iz poola tu ne treba), a {@link #renderNakonCommita} u vlastitoj kratkoj transakciji, kao
 * {@code RnLifecycleLookup}: eGOP tok radi nakon commita, gdje bi {@code REQUIRED} pokušao nastaviti završenu.
 *
 * <p>Nedostaju li podaci (iznajmljivač, objekt, vrijeme predaje), PDF ih ispisuje prazne — podnesak se mora moći
 * urudžbirati i tada.
 */
@Service
public class PodnesakPdfService {

    private final PodnesakPdfGenerator generator;
    private final DocumentProperties documentProperties;
    private final RnRepository rnRepository;
    private final SubmissionRepository submissionRepository;
    private final LessorRepository lessorRepository;
    private final AccommodationRepository accommodationRepository;
    private final ObjectGroupPolicy objectGroupPolicy;
    private final EgopPismenoRepository pismenoRepository;
    private final RegistrationNumberLogRepository logRepository;
    private final SuspensionObjectionRepository objectionRepository;
    private final PrilogService prilogService;

    public PodnesakPdfService(PodnesakPdfGenerator generator,
                              DocumentProperties documentProperties,
                              RnRepository rnRepository,
                              SubmissionRepository submissionRepository,
                              LessorRepository lessorRepository,
                              AccommodationRepository accommodationRepository,
                              ObjectGroupPolicy objectGroupPolicy,
                              EgopPismenoRepository pismenoRepository,
                              RegistrationNumberLogRepository logRepository,
                              SuspensionObjectionRepository objectionRepository,
                              PrilogService prilogService) {
        this.generator = generator;
        this.documentProperties = documentProperties;
        this.rnRepository = rnRepository;
        this.submissionRepository = submissionRepository;
        this.lessorRepository = lessorRepository;
        this.accommodationRepository = accommodationRepository;
        this.objectGroupPolicy = objectGroupPolicy;
        this.pismenoRepository = pismenoRepository;
        this.logRepository = logRepository;
        this.objectionRepository = objectionRepository;
        this.prilogService = prilogService;
    }

    /** Adresat u postupku MINTS-a — kao na zahtjevu. */
    public List<String> zaglavljeMinistarstva() {
        return PdfObrazac.adresat(documentProperties.tijelo());
    }

    /** Adresat u županijskom postupku: organizacija službenika ({@code internal_organization.name}) i jedinica. */
    public static List<String> zaglavlje(RucniAkt rucni) {
        return Arrays.asList(rucni.izdavateljNaziv(), rucni.izdavateljJedinica());
    }

    /**
     * PDF podneska s danim zaglavljem i urudžbenim oznakama, u transakciji pozivatelja.
     *
     * @param filing KLASA i URBROJ pismena (forma ili eGOP)
     */
    @Transactional(readOnly = true)
    public byte[] render(SuspensionObjectionEntity prigovor, List<String> zaglavlje, FilingReference filing) {
        return sastavi(prigovor, zaglavlje, filing);
    }

    /** Kao {@link #render}, ali u vlastitoj transakciji — za eGOP tok nakon commita i retry job. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public byte[] renderNakonCommita(SuspensionObjectionEntity prigovor, List<String> zaglavlje,
                                     FilingReference filing) {
        return sastavi(prigovor, zaglavlje, filing);
    }

    private byte[] sastavi(SuspensionObjectionEntity prigovor, List<String> zaglavlje, FilingReference filing) {
        RnEntity rn = rnRepository.findById(prigovor.getRn())
                .orElseThrow(() -> new IllegalStateException("RB " + prigovor.getRn() + " prigovora "
                        + prigovor.getObjectionId() + " ne postoji"));
        SubmissionEntity submission = rn.getSubmissionId() == null ? null
                : submissionRepository.findById(rn.getSubmissionId()).orElse(null);
        // Podnositelj je iznajmljivač sa zahtjeva RB-a — snimak u trenutku podnošenja zahtjeva, kao na PDF-u
        // zahtjeva i u eGOP subjektu. Prigovor nosi samo OIB/lessorId predavatelja, ne matične i kontakt podatke.
        LessorEntity lessor = Optional.ofNullable(submission).map(SubmissionEntity::getLessorId)
                .flatMap(lessorRepository::findById)
                .orElse(null);
        AccommodationEntity objekt = objekt(rn);
        StrDocumentType vrsta = StrDocumentType.podnesakNaAkt(prigovor.getRnStatus())
                .orElseThrow(() -> new IllegalStateException("Prigovor " + prigovor.getObjectionId()
                        + " uz status " + prigovor.getRnStatus() + " nema vrstu pismena"));

        List<String> prilozi = new ArrayList<>();
        prilozi.add(prigovor.getFileName());
        prilogService.zaVlasnike(VrstaVlasnika.OBJECTION, List.of(prigovor.getObjectionId()))
                .getOrDefault(prigovor.getObjectionId(), List.of()).stream()
                .map(PrilogSazetak::getNazivDatoteke)
                .forEach(prilozi::add);

        return generator.generate(new PodnesakPdfContext(
                zaglavlje,
                vrsta.naslov(),
                vrsta.vrstaPismenaNaziv(),
                prigovor.getSubmittedAt(),
                filing,
                rn.getRn(),
                odnosiSeNa(vrsta, prigovor, submission),
                lessor,
                objekt == null ? null : objectGroupPolicy.groupOfType(objekt.getAccommodationTypeId())
                        .map(ObjectGroupEntity::getName).orElse(null),
                objekt == null ? null : objekt.getStreet(),
                objekt == null ? null : objekt.getStreetNumber(),
                objekt == null ? null : objekt.getPostalCode(),
                objekt == null ? null : objekt.getCity(),
                prilozi));
    }

    /**
     * PDF za ručno (županijsko) urudžbirano pismeno podneska koje ga nema — zapisano prije PDF-a podneska. Oznake
     * su s retka; zaglavlje je županija koja je izdala akt na koji podnesak odgovara (izdavatelj na zapisu
     * prijelaza — službenik koji je urudžbirao se ne pamti), a bez njega županija objekta. Samo ručna pismena:
     * staro pismeno MINTS-a u eGOP-u ima izvornik kao glavni dokument, pa ga STR ne smije zamijeniti PDF-om.
     */
    @Transactional(readOnly = true)
    public byte[] zaRucnoPismeno(EgopPismenoEntity pismeno) {
        if (!pismeno.isRucno()) {
            throw new IllegalArgumentException("Pismeno " + pismeno.getId() + " nije ručno urudžbirano");
        }
        UUID objectionId = pismeno.objectionId()
                .orElseThrow(() -> new IllegalArgumentException("Pismeno " + pismeno.getId() + " nije podnesak"));
        SuspensionObjectionEntity prigovor = objectionRepository.findById(objectionId)
                .orElseThrow(() -> new IllegalStateException("Prigovor " + objectionId + " ne postoji"));
        List<String> zaglavlje = logRepository.findRucniAkt(prigovor.getRnLogId())
                .map(PodnesakPdfService::zaglavlje)
                .orElseGet(() -> zupanijaObjekta(prigovor.getRn()));
        return sastavi(prigovor, zaglavlje, new FilingReference(pismeno.getKlasa(), pismeno.getUrBroj()));
    }

    private List<String> zupanijaObjekta(String rn) {
        return rnRepository.findById(rn).map(this::objekt).map(AccommodationEntity::getCounty)
                .map(List::of).orElse(List.of());
    }

    private AccommodationEntity objekt(RnEntity rn) {
        Optional<AccommodationEntity> poRb = rn.getAccommodationId() == null ? Optional.empty()
                : accommodationRepository.findById(rn.getAccommodationId());
        return poRb.or(() -> rn.getSubmissionId() == null ? Optional.empty()
                        : accommodationRepository.findBySubmissionId(rn.getSubmissionId()).stream().findFirst())
                .orElse(null);
    }

    /**
     * Akt na koji podnesak odgovara ({@link StrDocumentType#pobijaniAkt()}, prijelaz {@code rn_log_id}), uz
     * njegovu KLASU i URBROJ kad je urudžbiran; inače samo naziv.
     */
    private String odnosiSeNa(StrDocumentType vrsta, SuspensionObjectionEntity prigovor, SubmissionEntity submission) {
        Optional<StrDocumentType> pobijani = vrsta.pobijaniAkt();
        if (pobijani.isEmpty()) {
            return null;
        }
        String naziv = pobijani.get().vrstaPismenaNaziv();
        Optional<EgopPismenoEntity> akt = prigovor.getRnLogId() == null ? Optional.empty()
                : pismenoRepository.findFirstByRnAndActRefAndVrstaPismenaNaziv(prigovor.getRn(),
                        prigovor.getRnLogId().toString(), naziv);
        if (akt.isEmpty() || akt.get().getUrBroj() == null) {
            return naziv;
        }
        // Akt MINTS-a nema KLASU na retku: KLASA predmeta akata RB-a (submission.egop_akti_klasa) — mijenja se s
        // uvođenjem predmeta po postupku.
        String klasa = akt.get().getKlasa() != null ? akt.get().getKlasa()
                : submission == null ? null : submission.getEgopAktiKlasa();
        return naziv + " (" + (klasa == null ? "" : "KLASA: " + klasa + ", ") + "URBROJ: " + akt.get().getUrBroj() + ")";
    }
}
