package com.str.backend.egop;

import com.str.backend.auth.internal.InternalUserRepository;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MINT 21.10.2026.: akt službenika nosi njegov urbroj, ostali idu pod aplikacijskim korisnikom. */
class EgopAktKorisnikTest {

    private static final String RN = "HR180000123456789001";
    private static final String APP = "strservis4";

    private RnLifecycleLookup lookup;
    private InternalUserRepository internalUsers;
    private EgopAktKorisnik korisnik;
    private UUID logId;
    private EgopPismenoEntity akt;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        internalUsers = mock(InternalUserRepository.class);
        korisnik = korisnik(EgopAktKorisnik.Nacin.SLUZBENIK);
        logId = UUID.randomUUID();
        akt = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, logId.toString(),
                "Obavijest o povlačenju registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "pdf".getBytes());
    }

    @Test
    void official_usesExternalUsername() {
        actor("INTERNAL:12345678903");
        when(internalUsers.findExternalUsername("12345678903")).thenReturn(Optional.of("ana.sluzbenica"));

        assertThat(korisnik.za(akt)).isEqualTo("ana.sluzbenica");
    }

    /** Odluka 21.10.2026.: službenik bez eGOP korisnika — aplikacijski korisnik uz ERROR. */
    @Test
    void officialWithoutExternalUsername_fallsBackToAppUser() {
        actor("INTERNAL:12345678903");
        when(internalUsers.findExternalUsername("12345678903")).thenReturn(Optional.empty());

        assertThat(korisnik.za(akt)).isEqualTo(APP);
    }

    @Test
    void lookupFailure_fallsBackToAppUser() {
        actor("INTERNAL:12345678903");
        when(internalUsers.findExternalUsername(any())).thenThrow(new DataAccessResourceFailureException("db"));

        assertThat(korisnik.za(akt)).isEqualTo(APP);
    }

    /** Opoziv radi iznajmljivač, a suspenziju po isteku roka sustav. */
    @Test
    void lessorAndSystem_useAppUser() {
        actor("NIAS:12345678903");
        assertThat(korisnik.za(akt)).isEqualTo(APP);

        actor("SYSTEM");
        assertThat(korisnik.za(akt)).isEqualTo(APP);
        verify(internalUsers, never()).findExternalUsername(any());
    }

    @Test
    void unknownTransition_usesAppUser() {
        when(lookup.findEvent(logId)).thenReturn(Optional.empty());

        assertThat(korisnik.za(akt)).isEqualTo(APP);
    }

    /** 22.10.2026.: prekidač šalje i akt službenika pod aplikacijskim korisnikom, bez čitanja baze. */
    @Test
    void aplikacijskiNacin_officialUsesAppUser() {
        korisnik = korisnik(EgopAktKorisnik.Nacin.APLIKACIJSKI);
        actor("INTERNAL:12345678903");

        assertThat(korisnik.za(akt)).isEqualTo(APP);
        verify(internalUsers, never()).findExternalUsername(any());
    }

    private EgopAktKorisnik korisnik(EgopAktKorisnik.Nacin nacin) {
        return new EgopAktKorisnik(lookup, internalUsers, new EgopProperties(true, "u", "p", "", APP,
                null, null, "https://egopeaitest.mint.hr", null, null, null, null), nacin);
    }

    private void actor(String actor) {
        when(lookup.findEvent(logId)).thenReturn(Optional.of(new RnLifecycleEvent(logId, RN, RnStatus.ACTIVE,
                RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, actor, null)));
    }
}
