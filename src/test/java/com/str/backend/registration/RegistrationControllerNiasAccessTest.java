package com.str.backend.registration;

import com.str.backend.auth.LessorPrincipal;
import com.str.backend.auth.nias.NiasIdentity;
import com.str.backend.captcha.AltchaService;
import com.str.backend.domain.OfferType;
import com.str.backend.domain.Offering;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.registration.dto.RegistrationRequest;
import com.str.backend.request.SubmissionEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.DefaultSaml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tko smije izdati RB s OIB-om i preuzeti PDF zahtjeva kad je NIAS uključen.
 *
 * <p>Non-EU prijava sprema kontekst u istu HTTP sesiju, pa sesija s {@link LessorPrincipal}
 * prolazi i NIAS security lanac ({@code authenticated()}). Ovi testovi zato idu izravno na
 * kontroler: granica mora biti ovdje, ne samo u filteru.
 */
class RegistrationControllerNiasAccessTest {

    private static final String OIB = "12312312316";
    private static final String OTHER_OIB = "19819819816";

    private final RegistrationService service = mock(RegistrationService.class);
    private final RegistrationController nias =
            new RegistrationController(service, mock(AltchaService.class), true);
    private final RegistrationController local =
            new RegistrationController(service, mock(AltchaService.class), false);

    /** Samoregistrirani non-EU korisnik ne smije zatražiti RB na tuđi OIB. */
    @Test
    void submit_rejectsLessorPrincipalSession_whenNiasEnabled() {
        assertThatThrownBy(() -> nias.generateRegistrationNumber(request(OTHER_OIB), lessorAuth(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);
    }

    @Test
    void submit_rejectsMissingIdentity_whenNiasEnabled() {
        assertThatThrownBy(() -> nias.generateRegistrationNumber(request(OTHER_OIB), null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    /** OIB iz tijela se zanemaruje — mjerodavan je assertion, s imenom i prezimenom. */
    @Test
    void submit_usesAssertionIdentity_notBodyOib() {
        nias.generateRegistrationNumber(request(OTHER_OIB), niasAuth(OIB), null);

        ArgumentCaptor<RegistrationRequest> req = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(service).generateRegistrationNumber(req.capture(), eq(new NiasIdentity(OIB, "Pero", "Perić")));
        assertThat(req.getValue().oib()).isEqualTo(OIB);
    }

    /** Bez NIAS-a (local/mock) OIB ostaje iz tijela — tako se lokalno testiraju razni iznajmljivači. */
    @Test
    void submit_keepsBodyOib_whenNiasDisabled() {
        local.generateRegistrationNumber(request(OTHER_OIB), null, null);

        ArgumentCaptor<RegistrationRequest> req = ArgumentCaptor.forClass(RegistrationRequest.class);
        verify(service).generateRegistrationNumber(req.capture(), eq(null));
        assertThat(req.getValue().oib()).isEqualTo(OTHER_OIB);
    }

    @Test
    void pdf_requesterIsAssertionOib() {
        UUID id = UUID.randomUUID();
        when(service.getSubmissionForPdf(eq(id), any())).thenReturn(submission());

        nias.downloadPdf(id, niasAuth(OIB));

        verify(service).getSubmissionForPdf(id, SubmissionRequester.oib(OIB));
    }

    @Test
    void pdf_requesterIsLessorId_forNonEuSession() {
        UUID id = UUID.randomUUID();
        when(service.getSubmissionForPdf(eq(id), any())).thenReturn(submission());
        Authentication auth = lessorAuth();

        nias.downloadPdf(id, auth);

        UUID lessorId = ((LessorPrincipal) auth.getPrincipal()).getLessorId();
        verify(service).getSubmissionForPdf(id, SubmissionRequester.lessor(lessorId));
    }

    @Test
    void pdf_rejectsAnonymous_whenNiasEnabled() {
        assertThatThrownBy(() -> nias.downloadPdf(UUID.randomUUID(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(service);
    }

    @Test
    void pdf_unrestricted_onlyWhenNiasDisabledAndAnonymous() {
        UUID id = UUID.randomUUID();
        when(service.getSubmissionForPdf(eq(id), any())).thenReturn(submission());

        local.downloadPdf(id, null);

        verify(service).getSubmissionForPdf(id, SubmissionRequester.ANYONE);
    }

    // --- fixtures ---

    private static Authentication niasAuth(String oib) {
        DefaultSaml2AuthenticatedPrincipal principal = new DefaultSaml2AuthenticatedPrincipal(
                "persistent-nameid", Map.of(
                        "oib", List.<Object>of(oib),
                        "ime", List.<Object>of("Pero"),
                        "prezime", List.<Object>of("Perić")));
        return new Saml2Authentication(principal, "<saml2p:Response/>", List.of());
    }

    private static Authentication lessorAuth() {
        LessorEntity lessor = LessorEntity.create("John", "Doe", "Main St", "1", "London", "", "john@example.com");
        LessorPrincipal principal = new LessorPrincipal(lessor);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static SubmissionEntity submission() {
        return SubmissionEntity.create(null, UUID.randomUUID(), null, Instant.now(), null,
                "%PDF-1.4".getBytes());
    }

    private static RegistrationRequest request(String oib) {
        return new RegistrationRequest(
                oib, "AP1", null,
                7L, "Split", "Meje",
                "Marulićeva", "5", null, "21000",
                4,
                OfferType.PRIMARY_RESIDENCE, Offering.WHOLE,
                false, null, false, true,
                null, null, null, null, null, null, null,
                "iznajmljivac@example.com", "0991234567", null, null, null, null, null);
    }
}
