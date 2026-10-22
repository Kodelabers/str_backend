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
    private MailRetryStore retryStore;
    private RnLifecycleEmailListener listener;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        documentService = mock(StrDocumentService.class);
        emailService = mock(EmailService.class);
        retryStore = mock(MailRetryStore.class);
        listener = new RnLifecycleEmailListener(lookup, documentService,
                new DocumentLabels(), emailService, retryStore);

        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com")));
        when(documentService.render(any(), any(), any())).thenReturn("pdf".getBytes());
        when(emailService.sendRnLifecycleNotification(any())).thenReturn(true);
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
     * Opoziv (REVOKED) i povlačenje (WITHDRAWN) imaju svaki svoj status, a poruke su bitno
     * različite („na Vaš zahtjev" vs po službenoj dužnosti).
     */
    @Test
    void revocation_byLessor_usesRevocationTemplate() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.REVOKED,
                RnTrigger.REVOCATION, "LESSOR:" + UUID.randomUUID(), null));

        assertThat(captured().template()).isEqualTo(MailTemplate.OPOZIV);
    }

    /** NIAS je produkcijski put prijave — i taj opoziv je na zahtjev stranke, ne po dužnosti. */
    @Test
    void revocation_byNiasLessor_usesRevocationTemplate() {
        listener.onLifecycleChange(event(RnStatus.SUSPENSION_PROPOSED, RnStatus.REVOKED,
                RnTrigger.REVOCATION, "NIAS:12345678903", null));

        assertThat(captured().template()).isEqualTo(MailTemplate.OPOZIV);
    }

    /** Poruka se bira po statusu: akter iznajmljivača ne pretvara povlačenje u opoziv. */
    @Test
    void templateIsChosenByStatus_notByActor() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.WITHDRAWN,
                RnTrigger.WITHDRAWAL, "NIAS:12345678903", null));

        assertThat(captured().template()).isEqualTo(MailTemplate.POVLACENJE);
    }

    /** Zaostala obavijest o opozivu rekonstruira se iz revizijskog zapisa s REVOKED (changeset 137). */
    @Test
    void retry_revocation_resendsRevocationMail() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.REVOKED, RnTrigger.REVOCATION,
                "NIAS:12345678903", null);
        when(lookup.findEvent(event.logId())).thenReturn(Optional.of(event));
        when(lookup.isLatestTransition(RN, event.logId())).thenReturn(true);

        listener.retry(event.logId());

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

    /** Reaktivacija ima predložak akta, pa ga i mail nosi. */
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

        assertThat(captured().dostava()).isEqualTo(Dostava.EPOSTA);
    }

    @Test
    void euLessor_isNotMarkedAsEmailDelivery() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INSPECTION, null, null));

        assertThat(captured().dostava()).isEqualTo(Dostava.PRETINAC);
    }

    /**
     * Opoziv je u eGOP-u ulazno pismeno i ne ide u korisnički pretinac (MINT, 22.10.2026.), pa
     * poruka ne smije tvrditi dostavu u pretinac — ni korisniku s OIB-om ni non-EU korisniku.
     */
    @Test
    void revocation_hasNoDeliveryClause_forEuLessor() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.REVOKED,
                RnTrigger.REVOCATION, "NIAS:12345678903", null));

        assertThat(captured().dostava()).isEqualTo(Dostava.BEZ_KLAUZULE);
        assertThat(captured().pdf()).isNotEmpty();
    }

    @Test
    void revocation_hasNoDeliveryClause_forNonEuLessor() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com", null)));

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.REVOKED,
                RnTrigger.REVOCATION, "LESSOR:" + UUID.randomUUID(), null));

        assertThat(captured().dostava()).isEqualTo(Dostava.BEZ_KLAUZULE);
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
        verify(retryStore, never()).recordFailure(any(), any(), any());
    }

    /** SMTP neuspjeh ide u red ponovnog slanja, pod log_id-em prijelaza. */
    @Test
    void sendFailure_isQueuedForRetry() {
        when(emailService.sendRnLifecycleNotification(any())).thenReturn(false);
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INSPECTION, null, null);

        listener.onLifecycleChange(event);

        verify(retryStore).recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "smtp_failed");
        verify(retryStore, never()).markSent(any(), any());
    }

    @Test
    void sendSuccess_closesRetryRecord() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INSPECTION, null, null);

        listener.onLifecycleChange(event);

        verify(retryStore).markSent(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId());
    }

    @Test
    void retry_latestTransition_resendsFromAuditRecord() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.WITHDRAWN, RnTrigger.WITHDRAWAL, null, "inspekcija");
        when(lookup.findEvent(event.logId())).thenReturn(Optional.of(event));
        when(lookup.isLatestTransition(RN, event.logId())).thenReturn(true);

        listener.retry(event.logId());

        assertThat(captured().template()).isEqualTo(MailTemplate.POVLACENJE);
        verify(retryStore).markSent(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId());
    }

    /**
     * Prijedlog suspenzije nakon kojeg je već stigla obustava: zakašnjela obavijest o prijedlogu
     * javila bi stranci stanje koje više ne vrijedi.
     */
    @Test
    void retry_supersededTransition_isAbandoned() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENSION_PROPOSED,
                RnTrigger.INSPECTION, null, null);
        when(lookup.findEvent(event.logId())).thenReturn(Optional.of(event));
        when(lookup.isLatestTransition(RN, event.logId())).thenReturn(false);

        listener.retry(event.logId());

        verify(emailService, never()).sendRnLifecycleNotification(any());
        verify(retryStore).abandon(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "superseded");
    }

    @Test
    void retry_missingAuditRecord_isAbandoned() {
        UUID logId = UUID.randomUUID();
        when(lookup.findEvent(logId)).thenReturn(Optional.empty());

        listener.retry(logId);

        verify(emailService, never()).sendRnLifecycleNotification(any());
        verify(retryStore).abandon(MailRetryEntity.Kind.RN_LIFECYCLE, logId, "missing_log");
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
                "Apartman Sunce", "Apartman", 4, "3*",
                UUID.randomUUID(), "Ana", "Anić", null, email, oib,
                false, null, null, null,
                null, null, null, null, null);
    }
}
