package com.str.backend.validation.go;

import com.str.backend.iznajmljivac.IznajmljivacEntity;
import com.str.backend.sso.SsoEntity;
import com.str.backend.validation.ValidacijskiKontekst;
import com.str.backend.validation.ValidacijskiRezultat;
import com.str.backend.zahtjev.ZahtjevEntity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class Go4SuglasnostSuvlasnikaTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 23);
    private final Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private final Go4SuglasnostSuvlasnika step = new Go4SuglasnostSuvlasnika(clock);

    @Test
    void skips_whenGo2DidNotFlag() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(null, null, null);
        ValidacijskiKontekst ctx = ctx(sso, false);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    @Test
    void passes_whenFlaggedAndSuglasnostValjana() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(true, TODAY.minusDays(10), TODAY.plusDays(30));
        ValidacijskiKontekst ctx = ctx(sso, true);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    @Test
    void passes_whenFlaggedAndSuglasnostWithoutExpiry() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(true, TODAY.minusDays(10), null);
        ValidacijskiKontekst ctx = ctx(sso, true);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Prosla.class);
    }

    @Test
    void rejects_whenFlaggedAndSuglasnostMissing() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(null, null, null);
        ValidacijskiKontekst ctx = ctx(sso, true);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    @Test
    void rejects_whenFlaggedAndSuglasnostFalse() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(false, TODAY.minusDays(10), null);
        ValidacijskiKontekst ctx = ctx(sso, true);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    @Test
    void rejects_whenDatumPovlacenjaIsToday() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(true, TODAY.minusDays(30), TODAY);
        ValidacijskiKontekst ctx = ctx(sso, true);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    @Test
    void rejects_whenDatumPovlacenjaInPast() {
        SsoEntity sso = GoTestFixtures.ssoWithSuglasnost(true, TODAY.minusDays(30), TODAY.minusDays(1));
        ValidacijskiKontekst ctx = ctx(sso, true);
        assertThat(step.provjeri(ctx)).isInstanceOf(ValidacijskiRezultat.Odbijena.class);
    }

    private ValidacijskiKontekst ctx(SsoEntity sso, boolean flagged) {
        IznajmljivacEntity iz = GoTestFixtures.iznajmljivac("Grad Zagreb", "Zagreb");
        ZahtjevEntity z = GoTestFixtures.zahtjev(iz.getIdIznajmljivaca());
        ValidacijskiKontekst k = new ValidacijskiKontekst(z, sso, iz, null);
        if (flagged) {
            k.markiraj();
        }
        return k;
    }
}
