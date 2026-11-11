package com.str.backend.kp.api;

import com.str.backend.auth.internal.CountyScope;
import com.str.backend.auth.internal.CountyScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KpZaostaleInterceptorTest {

    private final KpPorukaService service = mock(KpPorukaService.class);
    private final KpPorukaRepository repository = mock(KpPorukaRepository.class);
    private final CountyScopeService countyScope = mock(CountyScopeService.class);
    private final MutableClock clock = new MutableClock();
    private KpZaostaleInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new KpZaostaleInterceptor(service, repository, countyScope, properties(true), clock);
        when(repository.postojeSlobodne(any())).thenReturn(true);
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.county("Splitsko-dalmatinska županija", 1L, 17,
                "Splitsko-dalmatinska županija"));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("oib", null, "ROLE_X"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static KpApiProperties properties(boolean enabled) {
        return new KpApiProperties(enabled, "https://kp.test", 1741, null, null, null, null, null, null, null, null,
                null, null);
    }

    private boolean zahtjev(String sesija) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (sesija != null) {
            request.setSession(new MockHttpSession(null, sesija));
        }
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void zupanijskiSluzbenik_saljeZaostaleSvojeZupanije() {
        assertThat(zahtjev("s1")).isTrue();

        verify(service).posaljiZaostale(17, "s1");
    }

    @Test
    void ministarstvo_neSalje() {
        when(countyScope.scopeFor(any())).thenReturn(CountyScope.ministry("MINTS"));

        zahtjev("s1");

        verify(service, never()).posaljiZaostale(anyInt(), anyString());
    }

    @Test
    void istaSesija_najvisejednomURazmaku() {
        zahtjev("s1");
        zahtjev("s1");
        zahtjev("s2");
        clock.plus(KpZaostaleInterceptor.RAZMAK.toMillis());
        zahtjev("s1");

        verify(service, times(2)).posaljiZaostale(17, "s1");
        verify(service, times(1)).posaljiZaostale(17, "s2");
    }

    @Test
    void bezSlobodnihPoruka_neRazrjesavaZupaniju() {
        when(repository.postojeSlobodne(any())).thenReturn(false);

        zahtjev("s1");

        verify(countyScope, never()).scopeFor(any());
        verify(service, never()).posaljiZaostale(anyInt(), anyString());
    }

    @Test
    void ugasenoIliBezSesije_nista() {
        interceptor = new KpZaostaleInterceptor(service, repository, countyScope, properties(false), clock);
        zahtjev("s1");
        interceptor = new KpZaostaleInterceptor(service, repository, countyScope, properties(true), clock);
        zahtjev(null);

        verify(repository, never()).postojeSlobodne(any());
        verify(service, never()).posaljiZaostale(anyInt(), anyString());
    }

    /** Greška nikad ne ruši ni ne odbija zahtjev službenika. */
    @Test
    void greska_zahtjevIdeDalje() {
        when(countyScope.scopeFor(any())).thenThrow(new IllegalStateException("baza"));

        assertThat(zahtjev("s1")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private long millis = Instant.parse("2026-11-11T10:00:00Z").toEpochMilli();

        void plus(long ms) {
            millis += ms;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
