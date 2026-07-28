package com.str.backend.email;

import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RnLifecycleEmailListenerTest {

    private static final String RN = "HR180000123456789001";

    private RnLifecycleLookup lookup;
    private StrDocumentService documentService;
    private EmailService emailService;
    private RnLifecycleEmailListener listener;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        documentService = mock(StrDocumentService.class);
        emailService = mock(EmailService.class);
        listener = new RnLifecycleEmailListener(lookup, documentService,
                new DocumentLabels(), emailService);

        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com")));
        when(documentService.render(any(), any(), any())).thenReturn("pdf".getBytes());
    }

    @Test
    void suspension_sendsSuspensionMailWithAct() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INCOMPLETE_DOCUMENTATION, null, null));

        RnLifecycleMail mail = captured();
        assertThat(mail.template()).isEqualTo(MailTemplate.SUSPENZIJA);
        assertThat(mail.to()).isEqualTo("ana@example.com");
        assertThat(mail.razlog()).isEqualTo("nepotpuna dokumentacija");
        assertThat(mail.rok()).isEqualTo("najkasnije do 15.08.2026.");
        assertThat(mail.pdf()).isNotEmpty();
        verify(documentService).render(StrDocumentType.SUSPENZIJA, RN, "nepotpuna dokumentacija");
    }

    /**
     * Opoziv i povlačenje dijele okidač {@link RnTrigger#WITHDRAWAL} — razlikuje ih jedino
     * tko ih je pokrenuo, a poruke su bitno različite („na Vaš zahtjev" vs po službenoj dužnosti).
     */
    @Test
    void withdrawal_byLessor_usesRevocationTemplate() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, "LESSOR:" + UUID.randomUUID(), null));

        assertThat(captured().template()).isEqualTo(MailTemplate.OPOZIV);
    }

    /** NIAS je produkcijski put prijave — i taj opoziv je na zahtjev stranke, ne po dužnosti. */
    @Test
    void withdrawal_byNiasLessor_usesRevocationTemplate() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, "NIAS:12345678903", null));

        assertThat(captured().template()).isEqualTo(MailTemplate.OPOZIV);
    }

    /**
     * Poziv na izjašnjavanje: mail mora nositi rok, jer je to jedini razlog zbog kojeg stranka
     * na njega uopće mora reagirati.
     */
    @Test
    void suspensionProposal_sendsMailWithDeadline() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED,
                RnTrigger.INCOMPLETE_DOCUMENTATION, null, null));

        RnLifecycleMail mail = captured();
        assertThat(mail.template()).isEqualTo(MailTemplate.PRIJEDLOG_SUSPENZIJE);
        assertThat(mail.razlog()).isEqualTo("nepotpuna dokumentacija");
        assertThat(mail.rok()).isEqualTo("najkasnije do 15.08.2026.");
        assertThat(mail.pdf()).isNotEmpty();
    }

    @Test
    void revokedProposal_sendsDiscontinuationMail() {
        listener.onLifecycleChange(event(RnStatus.SUSPENSION_PROPOSED, RnStatus.ACTIVE,
                RnTrigger.REVOKE_PROPOSAL, null, null));

        RnLifecycleMail mail = captured();
        assertThat(mail.template()).isEqualTo(MailTemplate.OBUSTAVA_SUSPENZIJE);
        assertThat(mail.razlog()).isEqualTo("obustava postupka suspenzije");
        verify(documentService).render(StrDocumentType.OBUSTAVA_SUSPENZIJE, RN,
                "obustava postupka suspenzije");
    }

    @Test
    void withdrawal_byAuthority_usesWithdrawalTemplate() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, null, "nalaz inspekcije"));

        RnLifecycleMail mail = captured();
        assertThat(mail.template()).isEqualTo(MailTemplate.POVLACENJE);
        assertThat(mail.razlog()).isEqualTo("nalaz inspekcije");
    }

    /**
     * Reaktivacija ima predložak akta, pa ga i mail nosi. Urudžbiranje je zasebno pitanje —
     * vrsta pismena nema šifru u eGOP šifrarniku, pa ga blokira zastavica u
     * {@code RnLifecycleFilingListener}, ne ovaj listener.
     */
    @Test
    void reactivation_sendsMailWithAct() {
        listener.onLifecycleChange(event(RnStatus.SUSPENDED, RnStatus.ACTIVE,
                RnTrigger.REACTIVATE, null, null));

        RnLifecycleMail mail = captured();
        assertThat(mail.template()).isEqualTo(MailTemplate.REAKTIVACIJA);
        assertThat(mail.pdf()).isNotEmpty();
        verify(documentService).render(StrDocumentType.REAKTIVACIJA, RN, "reaktivacija registracijskog broja");
    }

    /**
     * Non-EU iznajmljivač nema korisnički pretinac — e-pošta mu je kanal dostave, pa poruka
     * mora nositi drugu klauzulu. Tvrditi dostavu u pretinac nekome tko ga nema je netočno.
     */
    @Test
    void nonEuLessor_isMarkedAsEmailDelivery() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com", null)));

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        assertThat(captured().dostavaMailom()).isTrue();
    }

    @Test
    void euLessor_isNotMarkedAsEmailDelivery() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        assertThat(captured().dostavaMailom()).isFalse();
    }

    /** Izdavanje RB-a već pokriva EgopRegistrationDispatcher; dvostruka obavijest se ne šalje. */
    @Test
    void issuance_isHandledElsewhere_noMail() {
        listener.onLifecycleChange(event(RnStatus.IN_PROCESSING, RnStatus.ACTIVE,
                RnTrigger.ISSUE, null, null));

        verify(emailService, never()).sendRnLifecycleNotification(any());
    }

    /** Pad rendera akta ne smije progutati obavijest — status je već promijenjen. */
    @Test
    void actRenderFailure_stillSendsMail() {
        when(documentService.render(any(), any(), any()))
                .thenThrow(new IllegalStateException("nema predloška"));

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        assertThat(captured().pdf()).isNull();
    }

    @Test
    void missingEmail_skipsSilently() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail(null)));

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        verify(emailService, never()).sendRnLifecycleNotification(any());
    }

    private RnLifecycleMail captured() {
        ArgumentCaptor<RnLifecycleMail> captor = ArgumentCaptor.forClass(RnLifecycleMail.class);
        verify(emailService).sendRnLifecycleNotification(captor.capture());
        return captor.getValue();
    }

    private static RnLifecycleEvent event(RnStatus from, RnStatus to, RnTrigger trigger,
                                          String actor, String reason) {
        return new RnLifecycleEvent(UUID.randomUUID(), RN, from, to, trigger, actor, reason);
    }

    private static RnDetailDto detail(String email) {
        return detail(email, "98765432109");
    }

    private static RnDetailDto detail(String email, String oib) {
        return new RnDetailDto(RN, RnStatus.SUSPENDED,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), null,
                LocalDate.of(2026, 8, 15), null, null, UUID.randomUUID(),
                UUID.randomUUID(), "Grad Zagreb", "Zagreb", null, "Ilica", "1",
                "Apartman Sunce", "Apartman", 4, 6, "3*",
                UUID.randomUUID(), "Ana", "Anić", null, email, oib,
                false, null, null, null,
                null, null, null, null, null);
    }
}
