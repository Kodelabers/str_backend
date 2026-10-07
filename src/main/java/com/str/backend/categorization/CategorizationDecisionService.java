package com.str.backend.categorization;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.categorization.CategorizationDecisionEntity.CategorizationDecisionMetadata;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lookup.AccommodationTypeEntity;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import com.str.backend.str.StrFacilityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class CategorizationDecisionService {

    private static final byte[] MAGIC_PDF = {0x25, 0x50, 0x44, 0x46};                 // %PDF
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] MAGIC_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** Parcijalni unique index iz changeseta 126 — jedno predano ili potvrđeno rješenje po RB-u. */
    static final String ACTIVE_DECISION_INDEX = "uq_categorization_decision_rn_active";
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * Statusi RB-a uz koje se rješenje smije predati. Suspendiran i predložen za suspenziju
     * su namjerno tu: predaja rješenja upravo je odgovor na suspenziju zbog nepotpune
     * dokumentacije. Povučen RB je konačan i rješenje uz njega nema svrhu.
     */
    public static final Set<RnStatus> UPLOAD_ALLOWED_RN_STATUSES =
            EnumSet.of(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED, RnStatus.SUSPENDED);

    /** Rješenje u ovim statusima „zauzima" RB — novo je dopušteno tek nakon odbijanja. */
    public static final Set<CategorizationDecisionStatus> ACTIVE_DECISION_STATUSES =
            EnumSet.of(CategorizationDecisionStatus.SUBMITTED, CategorizationDecisionStatus.VERIFIED);

    private final CategorizationDecisionRepository repository;
    private final AccommodationTypeRepository accommodationTypeRepository;
    private final RnRepository rnRepository;
    private final AccommodationRepository accommodationRepository;
    private final StrFacilityRepository facilityRepository;

    public CategorizationDecisionService(CategorizationDecisionRepository repository,
                                        AccommodationTypeRepository accommodationTypeRepository,
                                        RnRepository rnRepository,
                                        AccommodationRepository accommodationRepository,
                                        StrFacilityRepository facilityRepository) {
        this.repository = repository;
        this.accommodationTypeRepository = accommodationTypeRepository;
        this.rnRepository = rnRepository;
        this.accommodationRepository = accommodationRepository;
        this.facilityRepository = facilityRepository;
    }

    /**
     * Predaja rješenja uz RB vlasnika {@code oib}. Rješenje traži RB novog objekta. Objekt iz
     * eTurizma (smještaj s {@code facilityId}) kategorizaciju već ima, pa se uz njegov RB
     * rješenje ne predaje — osim kad je objekt neverificiran (migriran iz starog sustava, podaci
     * nisu provjereni): tada ga iznajmljivač smije priložiti, neobavezno.
     *
     * <p>Redoslijed provjera: prvo vlasništvo (404 i za nepostojeći i za tuđi RB, da se ne
     * otkriva postoji li), zatim smisao predaje (409), a tek onda datoteka (400).
     */
    @Transactional
    public CategorizationDecisionResponse upload(String oib, CategorizationDecisionRequest req) {
        String rn = trimToNull(req.getRegistrationNumber());
        // Ista poruka za nepostojeći i tuđi RB — ključ, ne ulaz korisnika — da se ne otkriva postoji li.
        if (rn == null || !rnRepository.isOwnedByOib(rn, oib)) {
            throw new ResourceNotFoundException("error.rn.notFound");
        }
        RnEntity rnEntity = rnRepository.findById(rn)
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));
        AccommodationEntity accommodation = accommodationRepository.findById(rnEntity.getAccommodationId())
                .orElseThrow(() -> new ResourceNotFoundException("error.rn.notFound"));

        String facilityId = trimToNull(accommodation.getFacilityId());
        if (facilityId != null && !isUnverifiedFacility(facilityId)) {
            throw new ConflictException("error.categorization.notRequired", "CATEGORIZATION_NOT_REQUIRED");
        }
        if (!UPLOAD_ALLOWED_RN_STATUSES.contains(rnEntity.getStatus())) {
            throw new ConflictException("error.categorization.rnStatus", "CATEGORIZATION_RN_STATUS");
        }
        if (repository.existsByRnAndStatusIn(rn, ACTIVE_DECISION_STATUSES)) {
            throw new ConflictException("error.categorization.alreadySubmitted", "CATEGORIZATION_ALREADY_SUBMITTED");
        }

        MultipartFile file = req.getDatoteka();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("error.categorization.file.empty");
        }
        byte[] content = readBytes(file);
        String contentType = detectContentType(content);

        CategorizationDecisionEntity entity = CategorizationDecisionEntity.create(
                oib, rn, safeFileName(file.getOriginalFilename()), contentType, content,
                metadataOf(accommodation));

        return CategorizationDecisionResponse.of(saveActive(entity));
    }

    /**
     * Je li eTurizam objekt neverificiran — pita se eTurizam u trenutku predaje, ne stanje pri
     * izdavanju RB-a. Objekt koji eTurizam ne pronađe, kao i id koji nije broj, ne računa se kao
     * neverificiran.
     *
     * <p>Gleda se zapis jedinice spremljen uz RB ({@code accommodation.facility_id}), ne aktualna
     * jedinica objekta. Kad eTurizam migrirani zapis zamijeni novim verificiranim (nova verzija
     * istog {@code system_uuid}), stari zapis i dalje kaže {@code optimit}, pa bi izravan poziv
     * API-ja rješenje i dalje primio. Prihvaćeno (odluka 6. 10. 2026.): forma prilog nudi samo
     * prema claimu aktualne jedinice, predaja ide odmah nakon izdavanja, a rješenje svejedno
     * pregledava nadležno tijelo.
     */
    private boolean isUnverifiedFacility(String facilityId) {
        long id;
        try {
            id = Long.parseLong(facilityId);
        } catch (NumberFormatException e) {
            return false;
        }
        return facilityRepository.findOwnership(id)
                .map(row -> Boolean.FALSE.equals(row.getVerified()))
                .orElse(false);
    }

    /**
     * Provjera iznad ne zatvara utrku dviju istodobnih predaja (dvostruki klik, dva prozora) —
     * zatvara je unique index. Upis se zato odmah flusha: bez toga INSERT ide tek pri commitu,
     * izvan ove metode, i sukob bi stigao kao generički {@code DATA_CONFLICT} umjesto koda po
     * kojem frontend zna da je rješenje uz taj RB već zaprimljeno.
     */
    private CategorizationDecisionEntity saveActive(CategorizationDecisionEntity entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            if (isActiveDecisionConflict(e)) {
                throw new ConflictException("error.categorization.alreadySubmitted", "CATEGORIZATION_ALREADY_SUBMITTED");
            }
            throw e;
        }
    }

    static boolean isActiveDecisionConflict(DataIntegrityViolationException e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase(Locale.ROOT).contains(ACTIVE_DECISION_INDEX);
            }
            if (c instanceof SQLException sql && SQLSTATE_UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                // Bez naziva constrainta: jedini drugi unique je PK, a on je nasumičan UUID.
                return true;
            }
        }
        return false;
    }

    /**
     * Metapodaci za nadležno tijelo, iz smještaja RB-a — isti podaci koje je korisnik upisao
     * u zahtjev. Broj i datum rješenja se ne znaju (čitaju se sa skena), napomene nema.
     */
    private CategorizationDecisionMetadata metadataOf(AccommodationEntity a) {
        String typeCode = a.getAccommodationTypeId() == null ? null
                : accommodationTypeRepository.findById(a.getAccommodationTypeId())
                        .map(AccommodationTypeEntity::getCode)
                        .orElse(null);
        String streetLine = joinNonBlank(" ", a.getStreet(), a.getStreetNumber());
        String placeLine = joinNonBlank(" ", a.getPostalCode(), a.getCity());
        return new CategorizationDecisionMetadata(
                truncate(trimToNull(a.getName()), 255),
                typeCode,
                truncate(joinNonBlank(", ", streetLine, placeLine), 500),
                null,
                null,
                a.getMaxBeds() != null && a.getMaxBeds() > 0 ? a.getMaxBeds() : null,
                null);
    }

    /**
     * Tip se određuje iz sadržaja, ne iz {@code Content-Type} headera — header postavlja
     * klijent i može lagati, a ovdje pohranjujemo datoteku koju će kasnije otvarati
     * nadležno tijelo. Dopušteni su PDF, JPEG i PNG, isto što frontend nudi u dropzoneu.
     */
    private static String detectContentType(byte[] content) {
        if (startsWith(content, MAGIC_PDF)) return "application/pdf";
        if (startsWith(content, MAGIC_JPEG)) return "image/jpeg";
        if (startsWith(content, MAGIC_PNG)) return "image/png";
        throw new BusinessException("error.categorization.file.type");
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        return content.length >= magic.length
                && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("error.categorization.file.unreadable");
        }
    }

    /**
     * Klijent može poslati i putanju u {@code filename}; zadržava se samo naziv datoteke.
     * Nizovnim operacijama, ne {@code Paths}: naziv poput {@code "/"} ili s NUL znakom ondje
     * baca iznimku i predaja bi pala s 500.
     */
    static String safeFileName(String originalFilename) {
        String name = trimToNull(originalFilename);
        if (name != null) {
            name = name.replace("\0", "");
            name = trimToNull(name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1));
        }
        if (name == null) {
            return "rjesenje";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        String joined = Stream.of(parts)
                .map(CategorizationDecisionService::trimToNull)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(delimiter));
        return joined.isEmpty() ? null : joined;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
