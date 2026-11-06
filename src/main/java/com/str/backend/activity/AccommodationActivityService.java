package com.str.backend.activity;

import com.str.backend.activity.dto.SdepIngestRequest;
import com.str.backend.admin.AdminAuditService;
import com.str.backend.domain.RegistrationNumber;
import com.str.backend.guest.GuestCountryNames;
import com.str.backend.guest.GuestEntity;
import com.str.backend.guest.GuestRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * STR §10: SDEP ingestion (3.1) + competent authority query (3.2) + auto-purge (3.3, 18 months).
 */
@Service
public class AccommodationActivityService {

    private static final Logger log = LoggerFactory.getLogger(AccommodationActivityService.class);

    private static final Pattern RN_FORMAT = Pattern.compile(RegistrationNumber.REGEXP);

    private final AccommodationActivityRepository repository;
    private final AdminAuditService auditService;
    private final RnRepository rnRepository;
    private final GuestRepository guestRepository;
    private final GuestCountryNames countryNames;

    public AccommodationActivityService(AccommodationActivityRepository repository,
                                        AdminAuditService auditService,
                                        RnRepository rnRepository,
                                        GuestRepository guestRepository,
                                        GuestCountryNames countryNames) {
        this.repository = repository;
        this.auditService = auditService;
        this.rnRepository = rnRepository;
        this.guestRepository = guestRepository;
        this.countryNames = countryNames;
    }

    /** Ishod predaje: koliko je unosa stiglo i što se s njima dogodilo. */
    public record IngestResult(int received, int inserted, int updated, int unchanged) {
    }

    /** STR-3.1: predaja aktivnosti; vraća broj primljenih unosa, kao i dosad. */
    @Transactional
    public int ingest(SdepIngestRequest req) {
        return ingest(req.getPlatformId(), req.getEntries()).received();
    }

    /**
     * STR-3.1 uz ispravke i otkazivanja (T6). Unos sa {@code sdepActivityId} je verzija aktivnosti:
     * prva se dodaje, novija zamjenjuje redak i goste, a ista ili starija ne mijenja ništa. Ponavlja li
     * se ista aktivnost u paketu, vrijedi najnovija verzija. Otkazana se sprema s oznakom i kad stigne
     * prije izvorne. Unos bez {@code sdepActivityId} uvijek je novi redak, kao i dosad.
     *
     * <p>Registracijski broj nije uvjet: svaki se sprema s oznakom valjanosti (T7).
     */
    @Transactional
    public IngestResult ingest(Long platformId, List<SdepIngestRequest.Entry> entries) {
        Map<String, RnCheck> checks = rnChecks(entries.stream().map(SdepIngestRequest.Entry::getRn).toList());

        List<SdepIngestRequest.Entry> plain = new ArrayList<>();
        Map<String, SdepIngestRequest.Entry> newestById = new LinkedHashMap<>();
        for (SdepIngestRequest.Entry entry : entries) {
            if (entry.getSdepActivityId() == null) {
                plain.add(entry);
            } else {
                newestById.merge(entry.getSdepActivityId(), entry,
                        (kept, next) -> isNewer(next, kept) ? next : kept);
            }
        }

        int inserted = 0;
        for (SdepIngestRequest.Entry entry : plain) {
            AccommodationActivityEntity e = AccommodationActivityEntity.ingest(
                    platformId, entry.getRn(), entry.getAccommodationId(),
                    entry.getPeriodFrom(), entry.getPeriodTo(),
                    entry.getNumberOfNights(), entry.getNumberOfGuests(), entry.getGuestCountries());
            e.markRnCheck(checks.get(entry.getRn()));
            repository.save(e);
            saveGuests(e.getActivityId(), entry);
            inserted++;
        }

        Map<String, AccommodationActivityEntity> existing = newestById.isEmpty() ? Map.of()
                : repository.findByPlatformIdAndSdepActivityIdIn(platformId, newestById.keySet()).stream()
                        .collect(Collectors.toMap(AccommodationActivityEntity::getSdepActivityId, Function.identity()));
        int updated = 0;
        // Starije kopije iste aktivnosti iz istog paketa ne mijenjaju ništa
        int unchanged = entries.size() - plain.size() - newestById.size();
        Map<UUID, SdepIngestRequest.Entry> corrected = new LinkedHashMap<>();
        for (SdepIngestRequest.Entry entry : newestById.values()) {
            AccommodationActivityEntity.ListingData data = listingData(entry);
            RnCheck check = checks.get(entry.getRn());
            AccommodationActivityEntity known = existing.get(entry.getSdepActivityId());
            if (known == null) {
                AccommodationActivityEntity e = AccommodationActivityEntity.fromSdep(
                        platformId, entry.getSdepActivityId(), version(entry), data, check);
                repository.save(e);
                saveGuests(e.getActivityId(), entry);
                inserted++;
            } else if (known.applyCorrection(version(entry), data, check)) {
                corrected.put(known.getActivityId(), entry);
                updated++;
            } else {
                unchanged++;
            }
        }
        if (!corrected.isEmpty()) {
            guestRepository.deleteByActivityIds(corrected.keySet());
            corrected.forEach(this::saveGuests);
        }

        IngestResult result = new IngestResult(entries.size(), inserted, updated, unchanged);
        log.info("sdep_ingest platforma={} count={} inserted={} updated={} unchanged={}",
                platformId, result.received(), inserted, updated, unchanged);
        return result;
    }

    /** @param scopeCounties opseg službenika ({@code accommodation.county}); {@code null} = cijela RH */
    @Transactional(readOnly = true)
    public List<AccommodationActivityEntity> search(Long platformId, String rn, LocalDate od, LocalDate toDate,
                                                    List<String> scopeCounties) {
        return scopeCounties == null
                ? repository.search(platformId, rn, od, toDate)
                : repository.searchInCounties(platformId, rn, od, toDate, scopeCounties);
    }

    @Transactional
    public int purgeExpired() {
        int n = repository.purgeExpired(Instant.now());
        if (n > 0) {
            log.info("sdep_purge removed={}", n);
            // STR-3.3: brisanje podataka o aktivnosti uz revizijski log (čl. 5. st. 5, čl. 9).
            auditService.record(AdminAuditService.SYSTEM, "ACTIVITY_PURGE", "ACTIVITY", null,
                    "removed=" + n);
        }
        return n;
    }

    /** Valjanost svakog prijavljenog broja, jednim upitom za cijeli paket (T7). */
    private Map<String, RnCheck> rnChecks(Collection<String> rns) {
        Set<String> wellFormed = new HashSet<>();
        for (String rn : rns) {
            if (RN_FORMAT.matcher(rn).matches()) {
                wellFormed.add(rn);
            }
        }
        Map<String, RnEntity> known = wellFormed.isEmpty() ? Map.of()
                : rnRepository.findAllById(wellFormed).stream()
                        .collect(Collectors.toMap(RnEntity::getRn, Function.identity()));
        Map<String, RnCheck> checks = new LinkedHashMap<>();
        for (String rn : rns) {
            RnEntity e = known.get(rn);
            checks.put(rn, !wellFormed.contains(rn) ? RnCheck.INVALID_FORMAT
                    : e == null ? RnCheck.UNKNOWN
                    : e.getStatus().isActiveForPublicUse() ? RnCheck.VALID
                    : RnCheck.NOT_ACTIVE);
        }
        return checks;
    }

    private void saveGuests(UUID activityId, SdepIngestRequest.Entry entry) {
        if (entry.getGuests() == null) {
            return;
        }
        for (SdepIngestRequest.Guest guest : entry.getGuests()) {
            guestRepository.save(GuestEntity.fromSdep(activityId, guest.getCount(), guest.getCountry(),
                    countryNames.nameOf(guest.getCountry())));
        }
    }

    /** Je li {@code candidate} barem jednako nova kao {@code current}; kod jednakih vrijedi kasnija u paketu. */
    private static boolean isNewer(SdepIngestRequest.Entry candidate, SdepIngestRequest.Entry current) {
        Instant a = version(candidate);
        Instant b = version(current);
        return a == null || b == null || !a.isBefore(b);
    }

    private static Instant version(SdepIngestRequest.Entry entry) {
        OffsetDateTime v = entry.getSdepVersion();
        return v == null ? null : v.toInstant();
    }

    private static AccommodationActivityEntity.ListingData listingData(SdepIngestRequest.Entry entry) {
        return new AccommodationActivityEntity.ListingData(entry.getRn(), entry.getPeriodFrom(), entry.getPeriodTo(),
                entry.getNumberOfNights(), entry.getNumberOfGuests(), entry.getGuestCountries(),
                entry.isCancelled(), entry.getUrl(), entry.getActivityName(), entry.getFullAddress());
    }
}
