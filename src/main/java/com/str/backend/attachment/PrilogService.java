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
 * {@code app.attachments.max-file-size}, PDF/JPG/PNG/DOC/DOCX po sadržaju ({@link VrstaDatoteke}).
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
    private final int maxPoVlasniku;
    private final long maxVelicina;
    private final int maxNevezanih;

    public PrilogService(PrilogRepository repository,
                         @Value("${app.attachments.max-per-owner:5}") int maxPoVlasniku,
                         @Value("${app.attachments.max-file-size:10MB}") DataSize maxVelicina,
                         @Value("${app.attachments.max-staged-per-uploader:20}") int maxNevezanih) {
        this.repository = repository;
        this.maxPoVlasniku = maxPoVlasniku;
        this.maxVelicina = maxVelicina.toBytes();
        this.maxNevezanih = maxNevezanih;
    }

    /**
     * Sprema nevezan prilog. Redoslijed provjera: prazno (400), veličina (413), vrsta po sadržaju
     * (415), pa broj nevezanih istog učitavatelja (409) — staging nije pohrana, pa je ograničen.
     */
    @Transactional
    public PrilogResponse stage(String ucitao, MultipartFile datoteka) {
        Objects.requireNonNull(ucitao, "ucitao");
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
        if (repository.countByUcitaoAndVlasnikIdIsNull(ucitao) >= maxNevezanih) {
            throw new PrilogException(HttpStatus.CONFLICT, "error.attachment.stagedLimit",
                    PrilogException.STAGED_LIMIT);
        }
        PismenoPrilogEntity saved = repository.save(
                PismenoPrilogEntity.staged(ucitao, naziv, vrsta.contentType(), sadrzaj, sha256(sadrzaj)));
        return PrilogResponse.of(saved);
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
     * postojanje (404), učitavatelj (403), nevezanost (409). Ponovljeni id se računa jednom.
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
        if (jedinstveni.size() > maxPoVlasniku) {
            throw tooMany();
        }
        List<PrilogSazetak> prilozi = repository.findSazeci(jedinstveni);
        if (prilozi.size() != jedinstveni.size()) {
            throw notFound();
        }
        for (PrilogSazetak prilog : prilozi) {
            requireUnbound(ucitao, prilog);
        }
        long vec = repository.countByVrstaVlasnikaAndVlasnikId(vlasnik.vrsta(), vlasnik.id());
        if (vec + jedinstveni.size() > maxPoVlasniku) {
            throw tooMany();
        }
        int vezano = repository.bind(jedinstveni, ucitao, vlasnik.vrsta(), vlasnik.id(), Instant.now());
        if (vezano != jedinstveni.size()) {
            // Provjera iznad ne zatvara utrku dviju radnji s istim prilogom — uvjetni UPDATE zatvara.
            throw alreadyBound();
        }
        return repository.findSazeci(jedinstveni);
    }

    /** Prilozi vlasnika bez sadržaja, redom učitavanja. */
    @Transactional(readOnly = true)
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

    @Transactional(readOnly = true)
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

    private static void requireUnbound(String ucitao, PrilogSazetak prilog) {
        if (!ucitao.equals(prilog.getUcitao())) {
            throw new PrilogException(HttpStatus.FORBIDDEN, "error.attachment.forbidden", PrilogException.FORBIDDEN);
        }
        if (prilog.getVlasnikId() != null) {
            throw alreadyBound();
        }
    }

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
