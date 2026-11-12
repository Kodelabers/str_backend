package com.str.backend.attachment;

import com.str.backend.attachment.PrilogRepository.PrilogSazetak;
import com.str.backend.common.Strings;
import com.str.backend.common.VrstaDatoteke;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Prilozi uz pismena (T17): do {@code app.attachments.max-per-owner} datoteka po pismenu, svaka do
 * {@code app.attachments.max-file-size}, PDF/JPG/PNG/DOC/DOCX po sadržaju ({@link VrstaDatoteke}). Očitovanje
 * i prigovor imaju vlastitu granicu, {@code app.attachments.max-per-objection} (dokument i do 5 priloga).
 *
 * <p>Ugovor je <b>staging, pa vezanje</b>. {@link #stage} sprema datoteku bez vlasnika, uz
 * učitavatelja ({@link Ucitao}); radnja (zahtjev za RB, opoziv, akt službenika, prigovor) u svojoj
 * transakciji zove {@link #bind}. Tako su prilozi vezani i vidljivi prije nego AFTER_COMMIT
 * listeneri (eGOP, e-pošta) krenu — bez utrke. Nevezane briše {@link PrilogCleanupJob}.
 */
@Service
public class PrilogService {

    static final Set<VrstaDatoteke> DOPUSTENE_VRSTE = EnumSet.allOf(VrstaDatoteke.class);

    private final PrilogRepository repository;
    private final PrilogStagingLock stagingLock;
    private final int maxPoVlasniku;
    private final int maxPoPrigovoru;
    private final long maxVelicina;
    private final int maxNevezanih;
    private final long maxNevezanihUkupno;
    private final long maxUkupnoSluzbenik;

    public PrilogService(PrilogRepository repository,
                         PrilogStagingLock stagingLock,
                         @Value("${app.attachments.max-per-owner:5}") int maxPoVlasniku,
                         @Value("${app.attachments.max-per-objection:6}") int maxPoPrigovoru,
                         @Value("${app.attachments.max-file-size:10MB}") DataSize maxVelicina,
                         @Value("${app.attachments.max-staged-per-uploader:20}") int maxNevezanih,
                         @Value("${app.attachments.max-staged-total-per-uploader:100MB}") DataSize maxNevezanihUkupno,
                         @Value("${app.attachments.official-max-total:15MB}") DataSize maxUkupnoSluzbenik) {
        this.repository = repository;
        this.stagingLock = stagingLock;
        this.maxPoVlasniku = maxPoVlasniku;
        this.maxPoPrigovoru = maxPoPrigovoru;
        this.maxVelicina = maxVelicina.toBytes();
        this.maxNevezanih = maxNevezanih;
        this.maxNevezanihUkupno = maxNevezanihUkupno.toBytes();
        this.maxUkupnoSluzbenik = maxUkupnoSluzbenik.toBytes();
    }

    /**
     * Sprema nevezan prilog. Redoslijed provjera: prazno (400), veličina (413), vrsta po sadržaju
     * (415), pa kvota nevezanih istog učitavatelja — broj i ukupna veličina (409) — staging nije
     * pohrana, pa je ograničen.
     *
     * <p>Kvota je „prebroji pa upiši", pa se staging istog učitavatelja serijalizira
     * ({@link PrilogStagingLock}): inače bi paralelni zahtjevi svi vidjeli isti broj i kvotu zaobišli.
     */
    @Transactional
    public PrilogResponse stage(String ucitao, MultipartFile datoteka) {
        Objects.requireNonNull(ucitao, "ucitao");
        Datoteka d = procitaj(datoteka);
        stagingLock.zakljucaj(ucitao);
        if (repository.countByUcitaoAndVlasnikIdIsNull(ucitao) >= maxNevezanih) {
            throw new PrilogException(HttpStatus.CONFLICT, "error.attachment.stagedLimit",
                    PrilogException.STAGED_LIMIT);
        }
        if (repository.sumVelicinaNevezanih(ucitao) + d.sadrzaj().length > maxNevezanihUkupno) {
            throw new PrilogException(HttpStatus.CONFLICT, "error.attachment.stagedTotal",
                    PrilogException.STAGED_LIMIT);
        }
        PismenoPrilogEntity saved = repository.save(
                PismenoPrilogEntity.staged(ucitao, d.naziv(), d.contentType(), d.sadrzaj(), sha256(d.sadrzaj())));
        return PrilogResponse.of(saved);
    }

    /**
     * Sprema datoteke odmah vezane uz {@code vlasnik}, bez staginga — za dodatne datoteke prigovora,
     * koje stižu u istom multipart zahtjevu kao i prigovor. Iste provjere kao {@link #stage} (prazno,
     * veličina, vrsta), a broj se računa zajedno s {@code vecPrilozeno} datoteka koje je radnja već
     * spremila drugdje (prvi dokument prigovora je u {@code suspension_objection.file_content}).
     *
     * <p>{@code MANDATORY}: kao {@link #bind}, pad poništava i radnju.
     *
     * @return spremljeni prilozi, redom datoteka
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PrilogResponse> spremiVezane(String ucitao, List<MultipartFile> datoteke, PrilogVlasnik vlasnik,
                                             int vecPrilozeno) {
        Objects.requireNonNull(ucitao, "ucitao");
        Objects.requireNonNull(vlasnik, "vlasnik");
        if (datoteke == null || datoteke.isEmpty()) {
            return List.of();
        }
        long vec = repository.countByVrstaVlasnikaAndVlasnikId(vlasnik.vrsta(), vlasnik.id());
        requireBroj(vlasnik.vrsta(), vecPrilozeno + vec + datoteke.size());
        // Sve provjere prije prve pohrane: neispravna peta datoteka ne ostavlja spremljene prve četiri.
        List<Datoteka> procitane = datoteke.stream().map(this::procitaj).toList();
        return procitane.stream()
                .map(d -> repository.save(PismenoPrilogEntity.vezan(ucitao, d.naziv(), d.contentType(),
                        d.sadrzaj(), sha256(d.sadrzaj()), vlasnik)))
                .map(PrilogResponse::of)
                .toList();
    }

    /** Briše vlastiti nevezani prilog (korisnik ga je maknuo s forme). Vezani se ne brišu. */
    @Transactional
    public void deleteStaged(String ucitao, Long id) {
        PrilogSazetak prilog = repository.findSazeci(List.of(id)).stream().findFirst()
                .orElseThrow(PrilogService::notFound);
        requireUnbound(ucitao, prilog);
        if (repository.deleteStaged(id, ucitao) == 0) {
            // Paralelna radnja ga je u međuvremenu vezala (ili obrisala).
            throw alreadyBound();
        }
    }

    /**
     * Veže nevezane priloge {@code ucitao} uz {@code vlasnik}. Zove se <b>unutar transakcije
     * radnje</b> (zato {@code MANDATORY}): pad vezanja poništava i radnju, a vezani prilozi postoje
     * prije AFTER_COMMIT listenera.
     *
     * <p>Provjere: broj (najviše {@code max-per-owner} uz vlasnika, zajedno s već vezanima),
     * postojanje i učitavatelj (404 i za tuđi prilog — kao i nepostojeći, da se ne otkriva što
     * postoji), nevezanost (409), a za službenika i ukupna veličina
     * ({@code app.attachments.official-max-total}, 400 {@code ATTACHMENT_TOTAL_TOO_LARGE}).
     * Ponovljeni id se računa jednom.
     *
     * @param ids {@code prilogIds} iz zahtjeva; {@code null} ili prazno ne radi ništa
     * @return vezani prilozi, redom id-eva
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PrilogSazetak> bind(String ucitao, Collection<Long> ids, PrilogVlasnik vlasnik) {
        Objects.requireNonNull(ucitao, "ucitao");
        Objects.requireNonNull(vlasnik, "vlasnik");
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> bezPonavljanja = new LinkedHashSet<>(ids);
        bezPonavljanja.remove(null);
        List<Long> jedinstveni = List.copyOf(bezPonavljanja);
        if (jedinstveni.isEmpty()) {
            return List.of();
        }
        requireBroj(vlasnik.vrsta(), jedinstveni.size());
        List<PrilogSazetak> prilozi = repository.findSazeci(jedinstveni);
        if (prilozi.size() != jedinstveni.size()) {
            throw notFound();
        }
        for (PrilogSazetak prilog : prilozi) {
            requireUnbound(ucitao, prilog);
        }
        long vec = repository.countByVrstaVlasnikaAndVlasnikId(vlasnik.vrsta(), vlasnik.id());
        requireBroj(vlasnik.vrsta(), vec + jedinstveni.size());
        if (Ucitao.jeSluzbenik(ucitao)) {
            requireUkupnoSluzbenika(prilozi,
                    vec > 0 ? repository.sumVelicinaVlasnika(vlasnik.vrsta(), vlasnik.id()) : 0);
        }
        int vezano = repository.bind(jedinstveni, ucitao, vlasnik.vrsta(), vlasnik.id(), Instant.now());
        if (vezano != jedinstveni.size()) {
            // Provjera iznad ne zatvara utrku dviju radnji s istim prilogom — uvjetni UPDATE zatvara.
            throw alreadyBound();
        }
        return repository.findSazeci(jedinstveni);
    }

    /**
     * Najviše {@code app.attachments.max-per-owner} datoteka uz jedno pismeno, a uz očitovanje i prigovor
     * ({@link VrstaVlasnika#OBJECTION}) {@code app.attachments.max-per-objection} — dokument i do 5 priloga
     * (400 {@code ATTACHMENT_COUNT}). Jedino mjesto te provjere — i za prigovor, čiji je dokument izvan
     * {@code pismeno_prilog}.
     */
    public void requireBroj(VrstaVlasnika vrsta, long ukupnoDatoteka) {
        if (ukupnoDatoteka > (vrsta == VrstaVlasnika.OBJECTION ? maxPoPrigovoru : maxPoVlasniku)) {
            throw tooMany();
        }
    }

    /**
     * Prilozi vlasnika bez sadržaja, redom učitavanja. {@code REQUIRES_NEW}: zove ga i obavijest
     * e-poštom iz {@code AFTER_COMMIT} listenera, gdje je izvorna transakcija već dovršena.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<PrilogSazetak> zaVlasnika(PrilogVlasnik vlasnik) {
        return repository.findSazeciVlasnika(vlasnik.vrsta(), vlasnik.id());
    }

    /** Prilozi više vlasnika iste vrste, po vlasniku — za popis dokumenata RB-a. */
    @Transactional(readOnly = true)
    public Map<UUID, List<PrilogSazetak>> zaVlasnike(VrstaVlasnika vrsta, Collection<UUID> vlasnici) {
        if (vlasnici.isEmpty()) {
            return Map.of();
        }
        return repository.findSazeciVlasnika(vrsta, vlasnici).stream()
                .collect(Collectors.groupingBy(PrilogSazetak::getVlasnikId));
    }

    /** Sadržaj priloga; {@code REQUIRES_NEW} kao {@link #zaVlasnika}. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public byte[] sadrzaj(Long id) {
        byte[] sadrzaj = repository.findSadrzaj(id);
        if (sadrzaj == null) {
            throw notFound();
        }
        return sadrzaj;
    }

    /**
     * Prilog za preuzimanje uz RB. Nevezan prilog, prilog drugog RB-a i nepostojeći id su isti
     * 404 — endpoint ne otkriva što postoji. Pristup samom RB-u provjerava pozivatelj.
     */
    @Transactional(readOnly = true)
    public PrilogSadrzaj zaPreuzimanje(String rn, Long id) {
        PismenoPrilogEntity prilog = repository.findById(id)
                .filter(p -> p.isVezan() && pripadaRb(p, rn))
                .orElseThrow(() -> new ResourceNotFoundException("error.attachment.notFound"));
        return new PrilogSadrzaj(prilog.getNazivDatoteke(), prilog.getContentType(), prilog.getFileContent());
    }

    /**
     * Ide li prilog prijelaza uz pismeno zadanog smjera. Stranka (opoziv) prilaže uz svoj podnesak,
     * službenik uz izlazni akt: kad uz isti prijelaz postoje oba pismena, svako nosi samo svoje.
     */
    public static boolean pripadaPismenu(PrilogSazetak prilog, boolean izlaznoPismeno) {
        return Ucitao.jeSluzbenik(prilog.getUcitao()) == izlaznoPismeno;
    }

    private boolean pripadaRb(PismenoPrilogEntity p, String rn) {
        return switch (p.getVrstaVlasnika()) {
            case SUBMISSION -> repository.isZahtjevRb(p.getVlasnikId(), rn);
            case RN_LOG -> repository.isPrijelazRb(p.getVlasnikId(), rn);
            case OBJECTION -> repository.isPrigovorRb(p.getVlasnikId(), rn);
        };
    }

    /** Tuđi prilog je 404, kao i nepostojeći — odgovor ne smije otkriti da id postoji. */
    private static void requireUnbound(String ucitao, PrilogSazetak prilog) {
        if (!ucitao.equals(prilog.getUcitao())) {
            throw notFound();
        }
        if (prilog.getVlasnikId() != null) {
            throw alreadyBound();
        }
    }

    /**
     * Prilozi službenika uz jednu radnju zajedno najviše {@code app.attachments.official-max-total}.
     * Non-EU iznajmljivaču je e-pošta pravna dostava akta ({@code RnLifecycleEmailListener}), pa svi
     * prilozi moraju stati u jednu poruku — iznad limita poruka bi ih samo navela.
     */
    private void requireUkupnoSluzbenika(List<PrilogSazetak> novi, long vecVezano) {
        long ukupno = vecVezano;
        for (PrilogSazetak p : novi) {
            ukupno += p.getVelicina();
        }
        if (ukupno > maxUkupnoSluzbenik) {
            throw new PrilogException(HttpStatus.BAD_REQUEST, "error.attachment.totalTooLarge",
                    PrilogException.TOTAL_TOO_LARGE);
        }
    }

    /**
     * Sadržaj i vrsta datoteke nakon provjera: prazno (400), veličina (413), vrsta po sadržaju (415).
     * Ekstenzija naziva postaje ona koju je potvrdio sadržaj ({@link VrstaDatoteke#nazivSEkstenzijom}),
     * da preuzeti prilog ne otvori program koji naziv laže.
     */
    private Datoteka procitaj(MultipartFile datoteka) {
        if (datoteka == null || datoteka.isEmpty()) {
            throw new PrilogException(HttpStatus.BAD_REQUEST, "error.attachment.file.empty", PrilogException.EMPTY);
        }
        if (datoteka.getSize() > maxVelicina) {
            throw new PrilogException(HttpStatus.PAYLOAD_TOO_LARGE, "error.upload.file.tooLarge",
                    PrilogException.TOO_LARGE);
        }
        byte[] sadrzaj = readBytes(datoteka);
        String naziv = Strings.safeFileName(datoteka.getOriginalFilename(), "prilog");
        VrstaDatoteke vrsta = VrstaDatoteke.odredi(sadrzaj, naziv, DOPUSTENE_VRSTE)
                .orElseThrow(() -> new PrilogException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "error.attachment.file.type", PrilogException.TYPE));
        return new Datoteka(vrsta.nazivSEkstenzijom(naziv), vrsta.contentType(), sadrzaj);
    }

    private record Datoteka(String naziv, String contentType, byte[] sadrzaj) {}

    private PrilogException tooMany() {
        return new PrilogException(HttpStatus.BAD_REQUEST, "error.attachment.count", PrilogException.COUNT);
    }

    private static PrilogException notFound() {
        return new PrilogException(HttpStatus.NOT_FOUND, "error.attachment.notFound", PrilogException.NOT_FOUND);
    }

    private static PrilogException alreadyBound() {
        return new PrilogException(HttpStatus.CONFLICT, "error.attachment.alreadyBound",
                PrilogException.ALREADY_BOUND);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("error.attachment.file.unreadable");
        }
    }

    static String sha256(byte[] sadrzaj) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sadrzaj));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nije dostupan", e);
        }
    }
}
