package com.str.backend.str;

import com.str.backend.str.FacilityRooms.Rooms;
import com.str.backend.str.StrFacilityRepository.FacilityOwnershipRow;
import com.str.backend.str.StrFacilityRepository.FacilityRoomsRow;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacilityRoomsTest {

    private final StrFacilityRepository repository = mock(StrFacilityRepository.class);
    private final FacilityRooms facilityRooms = new FacilityRooms(repository);

    @Test
    void sobePoVrsti_iUkupno() {
        ownership(73L, false);
        rooms(73L, null, 2, 10);

        Optional<Rooms> rooms = facilityRooms.forFacility("73");

        assertThat(rooms).contains(new Rooms(null, 2, 10));
        assertThat(rooms.orElseThrow().ukupno()).isEqualTo(12);
    }

    /** Novi objekt nema eTurizam jedinicu — eTurizam se ni ne pita. */
    @Test
    void noviObjekt_bezFacilityId_prazno() {
        assertThat(facilityRooms.forFacility(null)).isEmpty();
        assertThat(facilityRooms.forFacility("  ")).isEmpty();
        assertThat(facilityRooms.forFacility("nije-broj")).isEmpty();
        verify(repository, never()).findRooms(anyLong());
    }

    /** Migrirani objekt s više jedinica nosi sadržaj cijelog objekta — jedinica bi prikazala tuđe sobe. */
    @Test
    void kapacitetCijelogObjekta_prazno() {
        ownership(210L, true);
        rooms(210L, 2, 2, null);

        assertThat(facilityRooms.forFacility("210")).isEmpty();
    }

    /** Jedinica bez soba u sadržajima (npr. vrsta „Soba") — ništa, ne nule. */
    @Test
    void bezSoba_prazno() {
        ownership(5L, false);
        rooms(5L, null, null, null);

        assertThat(facilityRooms.forFacility("5")).isEmpty();
    }

    /** Pad eTurizma ne smije srušiti render zahtjeva. */
    @Test
    void eTurizamNedostupan_prazno() {
        when(repository.findOwnership(7L)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(facilityRooms.forFacility("7")).isEmpty();
    }

    private void ownership(long id, boolean objectLevel) {
        FacilityOwnershipRow row = mock(FacilityOwnershipRow.class);
        when(row.getObjectLevelCapacity()).thenReturn(objectLevel);
        when(repository.findOwnership(id)).thenReturn(Optional.of(row));
    }

    private void rooms(long id, Integer jedno, Integer dvo, Integer tro) {
        FacilityRoomsRow row = mock(FacilityRoomsRow.class);
        when(row.getJednokrevetne()).thenReturn(jedno);
        when(row.getDvokrevetne()).thenReturn(dvo);
        when(row.getTrokrevetne()).thenReturn(tro);
        when(repository.findRooms(id)).thenReturn(row);
    }
}
