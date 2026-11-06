package com.str.backend.sdep;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.str.backend.activity.AccommodationActivityService;
import com.str.backend.activity.dto.SdepIngestRequest;
import com.str.backend.auth.SecurityConfig;
import com.str.backend.auth.internal.InternalAccessService;
import com.str.backend.domain.RnTrigger;
import com.str.backend.exception.BusinessException;
import com.str.backend.lookup.OnlinePlatformEntity;
import com.str.backend.lookup.OnlinePlatformRepository;
import com.str.backend.rn.RnMapper;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.rn.RnVerification;
import com.str.backend.rn.dto.RnAccommodationStatsDto;
import com.str.backend.rn.dto.VerifyResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracijski API za SDEP: provjera tokena ({@link SdepSecurityConfig}) uz postojeći lanac za
 * službenike i iznajmljivače, i ponašanje {@link SdepIntegrationController}. Par ključeva SDEP-a
 * nastaje za svako pokretanje testa, pa u repozitoriju nema ključeva.
 */
@ActiveProfiles("test")
@Import({SecurityConfig.class, SdepSecurityConfig.class})
@WebMvcTest(controllers = {SdepIntegrationController.class, SdepIntegrationApiTest.BackOfficeController.class},
            excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
class SdepIntegrationApiTest {

    private static final String RN = "HR010001102030405060";
    private static final KeyPair SDEP_KEYS = rsaKeyPair();

    @RestController
    static class BackOfficeController {
        @GetMapping("/api/activity/ping")
        String ping() {
            return "back-office";
        }
    }

    @DynamicPropertySource
    static void sdepPublicKey(DynamicPropertyRegistry registry) throws IOException {
        Path pem = Files.createTempFile("sdep-public", ".pem");
        pem.toFile().deleteOnExit();
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(SDEP_KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        registry.add("str.sdep.public-key", () -> pem.toUri().toString());
    }

    @Autowired MockMvc mvc;

    @MockBean InternalAccessService internalAccess;
    @MockBean UserDetailsService userDetailsService;
    @MockBean RnVerification verification;
    @MockBean AccommodationActivityService activities;
    @MockBean OnlinePlatformRepository platforms;
    @MockBean RnService rnService;
    @MockBean RnMapper rnMapper;
    @MockBean RnRepository rnRepository;

    // --- Token ---------------------------------------------------------------------------------

    @Test
    void validToken_returns200() throws Exception {
        when(verification.verify(RN)).thenReturn(VerifyResponse.invalid());

        mvc.perform(signed(get("/api/sdep/v1/verify/{rn}", RN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void noToken_returns401() throws Exception {
        mvc.perform(get("/api/sdep/v1/verify/{rn}", RN)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKey_returns401() throws Exception {
        Instant now = Instant.now();
        String forged = token((RSAPrivateKey) rsaKeyPair().getPrivate(), "sdep", "turegistar", now, now.plusSeconds(300));

        mvc.perform(get("/api/sdep/v1/verify/{rn}", RN).header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_returns401() throws Exception {
        Instant past = Instant.now().minusSeconds(900);

        mvc.perform(get("/api/sdep/v1/verify/{rn}", RN)
                        .header("Authorization", "Bearer " + token(sdepKey(), "sdep", "turegistar", past, past.plusSeconds(300))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudience_returns401() throws Exception {
        Instant now = Instant.now();

        mvc.perform(get("/api/sdep/v1/verify/{rn}", RN)
                        .header("Authorization", "Bearer " + token(sdepKey(), "sdep", "netko-drugi", now, now.plusSeconds(300))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuer_returns401() throws Exception {
        Instant now = Instant.now();

        mvc.perform(get("/api/sdep/v1/verify/{rn}", RN)
                        .header("Authorization", "Bearer " + token(sdepKey(), "turegistar", "turegistar", now, now.plusSeconds(300))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void longLivedToken_returns401() throws Exception {
        Instant now = Instant.now();

        mvc.perform(get("/api/sdep/v1/verify/{rn}", RN)
                        .header("Authorization", "Bearer " + token(sdepKey(), "sdep", "turegistar", now, now.plusSeconds(3600))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sdepToken_doesNotOpenBackOffice() throws Exception {
        mvc.perform(signed(get("/api/activity/ping"))).andExpect(status().isUnauthorized());
    }

    /** Bez sesije i bez zaglavlja za zaštitu od CSRF-a, koje traži glavni lanac. */
    @Test
    void post_needsNoAjaxHeaderNorSession() throws Exception {
        OnlinePlatformEntity airbnb = platform(1L, "Airbnb");
        when(platforms.findFirstByActiveTrueAndNameIgnoreCase("Airbnb")).thenReturn(Optional.of(airbnb));
        when(activities.ingest(anyLong(), anyList())).thenReturn(new AccommodationActivityService.IngestResult(1, 1, 0, 0));

        mvc.perform(signed(post("/api/sdep/v1/activities"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activitiesBody("Airbnb")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    // --- Provjera broja ------------------------------------------------------------------------

    @Test
    void verify_malformedNumber_returns400() throws Exception {
        mvc.perform(signed(get("/api/sdep/v1/verify/{rn}", "NEISPRAVAN"))).andExpect(status().isBadRequest());
        verify(verification, never()).verify(anyString());
    }

    // --- Predaja aktivnosti --------------------------------------------------------------------

    @Test
    void activities_matchPlatformByNameAndIngest() throws Exception {
        OnlinePlatformEntity booking = platform(2L, "Booking.com");
        when(platforms.findFirstByActiveTrueAndNameIgnoreCase("booking.com")).thenReturn(Optional.of(booking));
        when(activities.ingest(anyLong(), anyList())).thenReturn(new AccommodationActivityService.IngestResult(1, 1, 0, 0));

        mvc.perform(signed(post("/api/sdep/v1/activities"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activitiesBody(" booking.com ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.inserted").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SdepIngestRequest.Entry>> entries = ArgumentCaptor.forClass(List.class);
        verify(activities).ingest(eq(2L), entries.capture());
        assertThat(entries.getValue()).singleElement().satisfies(e -> {
            assertThat(e.getRn()).isEqualTo(RN);
            assertThat(e.getPeriodFrom()).isEqualTo(LocalDate.of(2026, 9, 5));
            assertThat(e.getNumberOfNights()).isEqualTo(3);
            assertThat(e.getSdepActivityId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
            assertThat(e.getGuests()).singleElement().satisfies(g -> {
                assertThat(g.getCountry()).isEqualTo("ITA");
                assertThat(g.getCount()).isEqualTo(2);
            });
            assertThat(e.getFullAddress()).isEqualTo("Ilica 24, 10000 Zagreb");
        });
    }

    @Test
    void activities_unknownPlatform_returns400() throws Exception {
        when(platforms.findFirstByActiveTrueAndNameIgnoreCase(anyString())).thenReturn(Optional.empty());

        mvc.perform(signed(post("/api/sdep/v1/activities"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activitiesBody("Nepoznata")))
                .andExpect(status().isBadRequest());
        verify(activities, never()).ingest(anyLong(), anyList());
    }

    @Test
    void activities_invalidEntry_returns400WithField() throws Exception {
        mvc.perform(signed(post("/api/sdep/v1/activities"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"platformName": "Airbnb", "entries": [{"rn": "%s", "periodTo": "2026-09-08"}]}
                                """.formatted(RN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details['entries[0].periodFrom']").exists());
        verify(activities, never()).ingest(anyLong(), anyList());
    }

    // --- Suspenzija ----------------------------------------------------------------------------

    @Test
    void suspend_actsAsSdepWithDeadlineAndNote() throws Exception {
        LocalDate deadline = LocalDate.now().plusDays(15);

        mvc.perform(signed(post("/api/sdep/v1/rn/{rn}/suspend", RN))
                        .param("reason", "INSPECTION")
                        .param("suspensionDeadline", deadline.toString())
                        .param("note", "Broj ne odgovara adresi oglasa"))
                .andExpect(status().isOk());

        verify(rnService).suspend(eq(RN), eq(RnTrigger.INSPECTION), eq(deadline),
                eq("Broj ne odgovara adresi oglasa"), eq(SdepIntegrationController.ACTOR), eq(List.of()));
    }

    @Test
    void suspend_businessRuleRejected_returns400() throws Exception {
        when(rnService.suspend(eq(RN), any(), any(), any(), any(), anyCollection()))
                .thenThrow(new BusinessException("error.rn.suspend.deadline.required"));

        mvc.perform(signed(post("/api/sdep/v1/rn/{rn}/suspend", RN)).param("reason", "INSPECTION"))
                .andExpect(status().isBadRequest());
    }

    // --- Podaci za DZS -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void accommodationStats_deduplicatesAndReturnsKnownNumbers() throws Exception {
        when(rnRepository.findAccommodationStats(anyCollection()))
                .thenReturn(List.of(new RnAccommodationStatsDto(RN, "Zagreb", 4)));

        mvc.perform(signed(post("/api/sdep/v1/accommodation-stats"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"%s\", \"%s\", \"HR999999999999999999\"]".formatted(RN, RN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rn").value(RN))
                .andExpect(jsonPath("$[0].municipality").value("Zagreb"))
                .andExpect(jsonPath("$[0].maxBeds").value(4));

        ArgumentCaptor<Collection<String>> rns = ArgumentCaptor.forClass(Collection.class);
        verify(rnRepository).findAccommodationStats(rns.capture());
        assertThat(Set.copyOf(rns.getValue())).containsExactlyInAnyOrder(RN, "HR999999999999999999");
        assertThat(rns.getValue()).hasSize(2);
    }

    @Test
    void accommodationStats_emptyListMakesNoQuery() throws Exception {
        mvc.perform(signed(post("/api/sdep/v1/accommodation-stats"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        verify(rnRepository, never()).findAccommodationStats(anyCollection());
    }

    @Test
    void accommodationStats_tooMany_returns400() throws Exception {
        String body = "[" + String.join(",", java.util.Collections.nCopies(
                SdepIntegrationController.MAX_STATS_RNS + 1, "\"" + RN + "\"")) + "]";

        mvc.perform(signed(post("/api/sdep/v1/accommodation-stats"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
        verify(rnRepository, never()).findAccommodationStats(anyCollection());
    }

    // --- Pomoćno -------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) throws Exception {
        Instant now = Instant.now();
        return request.header("Authorization", "Bearer " + token(sdepKey(), "sdep", "turegistar", now, now.plusSeconds(300)));
    }

    private static String token(RSAPrivateKey key, String iss, String aud, Instant iat, Instant exp) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(iss).audience(aud)
                .issueTime(Date.from(iat)).expirationTime(Date.from(exp))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAPrivateKey sdepKey() {
        return (RSAPrivateKey) SDEP_KEYS.getPrivate();
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OnlinePlatformEntity platform(long id, String name) {
        OnlinePlatformEntity platform = org.mockito.Mockito.mock(OnlinePlatformEntity.class);
        when(platform.getPlatformId()).thenReturn(id);
        when(platform.getName()).thenReturn(name);
        return platform;
    }

    /** Kako ga šalje SDEP (_build_entry), s poljima koja STR još ne čita. */
    private static String activitiesBody(String platformName) {
        return """
                {
                  "sdepPlatformId": "8e70f1e2-4c61-477b-89b8-0dbf25ab8b21",
                  "platformName": "%s",
                  "entries": [{
                    "sdepActivityId": "550e8400-e29b-41d4-a716-446655440000",
                    "sdepVersion": "2026-09-01T12:00:00+00:00",
                    "status": "finished",
                    "rn": "%s",
                    "periodFrom": "2026-09-05",
                    "periodTo": "2026-09-08",
                    "numberOfNights": 3,
                    "numberOfGuests": 2,
                    "guests": [{"country": "ITA", "count": 2}],
                    "guestCountries": "ITA",
                    "url": "https://www.airbnb.com/rooms/1",
                    "activityName": null,
                    "fullAddress": "Ilica 24, 10000 Zagreb"
                  }]
                }
                """.formatted(platformName, RN);
    }
}
