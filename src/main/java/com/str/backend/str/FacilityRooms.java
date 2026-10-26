package com.str.backend.str;

import com.str.backend.str.StrFacilityRepository.FacilityRoomsRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Sobe postojećeg objekta iz eTurizma za tablicu kapaciteta na zahtjevu (jednokrevetne,
 * dvokrevetne i trokrevetne sobe te ukupan broj soba). Novi objekt sobe ne prijavljuje — obrazac
 * ih ne prikuplja — pa za njega podataka nema.
 *
 * <p>Prazno i kad bi broj bio pogrešan: migrirani objekt s više jedinica nosi na svakoj jedinici
 * sadržaj cijelog objekta ({@code objectLevelCapacity}, P-22), pa bi jedinica prikazala tuđe sobe.
 *
 * <p>Pad upita nad eTurizmom ne smije srušiti render zahtjeva: tablica tada ostaje bez soba.
 * Namjerno bez {@code @Transactional}, iz istog razloga kao {@link RnFacilityVerification}.
 */
@Service
public class FacilityRooms {

    private static final Logger log = LoggerFactory.getLogger(FacilityRooms.class);

    /** Broj soba po vrsti; {@code null} kad jedinica te vrste nema. */
    public record Rooms(Integer jednokrevetne, Integer dvokrevetne, Integer trokrevetne) {

        /** Ukupan broj soba; {@code null} kad nijedne vrste nema. */
        public Integer ukupno() {
            if (jednokrevetne == null && dvokrevetne == null && trokrevetne == null) {
                return null;
            }
            return nz(jednokrevetne) + nz(dvokrevetne) + nz(trokrevetne);
        }

        private static int nz(Integer value) {
            return value == null ? 0 : value;
        }
    }

    private final StrFacilityRepository facilityRepository;

    public FacilityRooms(StrFacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    /** Sobe jedinice {@code facilityId} (kakav stoji na smještaju); prazno kad ih ne znamo. */
    public Optional<Rooms> forFacility(String facilityId) {
        Long id = parseId(facilityId);
        if (id == null) {
            return Optional.empty();
        }
        try {
            boolean objectLevel = facilityRepository.findOwnership(id)
                    .map(row -> Boolean.TRUE.equals(row.getObjectLevelCapacity()))
                    .orElse(false);
            if (objectLevel) {
                return Optional.empty();
            }
            FacilityRoomsRow row = facilityRepository.findRooms(id);
            if (row == null) {
                return Optional.empty();
            }
            Rooms rooms = new Rooms(row.getJednokrevetne(), row.getDvokrevetne(), row.getTrokrevetne());
            return rooms.ukupno() == null ? Optional.empty() : Optional.of(rooms);
        } catch (DataAccessException e) {
            log.warn("facility_rooms_unavailable facility={}: {}", facilityId, e.getMessage());
            return Optional.empty();
        }
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
