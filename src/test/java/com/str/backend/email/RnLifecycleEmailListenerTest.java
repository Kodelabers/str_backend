package com.str.backend.email;

import com.str.backend.attachment.PrilogService;
import com.str.backend.attachment.PrilogVlasnik;
import com.str.backend.attachment.TestPrilog;
import com.str.backend.attachment.VrstaVlasnika;
import com.str.backend.document.DocumentLabels;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.RnStatus;
import com.str.backend.domain.RnTrigger;
import com.str.backend.egop.EgopPismenoEntity;
import com.str.backend.egop.EgopPismenoRepository;
import com.str.backend.eseal.PdfSealer;
import com.str.backend.rn.RnLifecycleLookup;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.event.RnLifecycleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.time.LocalDate;
import java.util.List;
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
    private PdfSealer sealer;
    private EgopPismenoRepository pismenoRepository;
    private PrilogService prilogService;
    private RnLifecycleEmailListener listener;

    @BeforeEach
    void setUp() {
        lookup = mock(RnLifecycleLookup.class);
        documentService = mock(StrDocumentService.class);
        emailService = mock(EmailService.class);
        retryStore = mock(MailRetryStore.class);
        sealer = mock(PdfSealer.class);
        pismenoRepository = mock(EgopPismenoRepository.class);
        prilogService = mock(PrilogService.class);
        listener = new RnLifecycleEmailListener(lookup, documentService,
                new DocumentLabels(), emailService, retryStore, sealer, pismenoRepository, prilogService,
                DataSize.ofMegabytes(15));

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
        assertThat(mail.razlog()).isEqualTo("nepotpuni i/ili netočni podaci");
        assertThat(mail.rok()).isEqualTo("najkasnije do 15.08.2026.");
        assertThat(mail.pdf()).isNotEmpty();
        verify(documentService).render(StrDocumentType.SUSPENZIJA, RN, "nepotpuni i/ili netočni podaci");
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
        assertThat(mail.razlog()).isEqualTo("nepotpuni i/ili netočni podaci");
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
     * Obavijest o opozivu je od T15 izlazni akt tijela i ide u korisnički pretinac, pa poruka nosi
     * klauzulu o dostavi kao i ostale obavijesti.
     */
    @Test
    void revocation_deliveredToMailbox_forEuLessor() {
        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.REVOKED,
                RnTrigger.REVOCATION, "NIAS:12345678903", null));

        assertThat(captured().dostava()).isEqualTo(Dostava.PRETINAC);
        assertThat(captured().pdf()).isNotEmpty();
    }

    @Test
    void revocation_deliveredByEmail_forNonEuLessor() {
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com", null)));

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.REVOKED,
                RnTrigger.REVOCATION, "LESSOR:" + UUID.randomUUID(), null));

        assertThat(captured().dostava()).isEqualTo(Dostava.EPOSTA);
    }

    /**
     * Non-EU uz eSeal: opoziv daje dva pismena istog prijelaza (zahtjev i obavijest), a mail mora
     * nositi pečaćenu obavijest — traži se po vrsti, ne prvo pismeno tog prijelaza.
     */
    @Test
    void revocation_nonEu_sealingEnabled_attachesSealedNotice() {
        when(sealer.isEnabled()).thenReturn(true);
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com", null)));
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.REVOKED, RnTrigger.REVOCATION,
                "LESSOR:" + UUID.randomUUID(), null);
        EgopPismenoEntity obavijest = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, event.logId().toString(),
                StrDocumentType.OPOZIV.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO, "snimak".getBytes());
        obavijest.applySeal("%PDF-opoziv".getBytes(), UUID.randomUUID(), "7K3M-Q9RT-X2VW", "B-LT", "sha-256=:x:");
        when(pismenoRepository.findFirstByRnAndActRefAndVrstaPismenaNaziv(RN, event.logId().toString(),
                StrDocumentType.OPOZIV.vrstaPismenaNaziv())).thenReturn(Optional.of(obavijest));

        listener.onLifecycleChange(event);

        assertThat(captured().template()).isEqualTo(MailTemplate.OPOZIV);
        assertThat(captured().pdf()).isEqualTo("%PDF-opoziv".getBytes());
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

    /** Non-EU uz eSeal: mail je dostava, pa nosi pečaćeni akt iz eGOP toka, ne novi render. */
    @Test
    void nonEu_sealingEnabled_attachesSealedAct() {
        when(sealer.isEnabled()).thenReturn(true);
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com", null)));
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INCOMPLETE_DOCUMENTATION,
                null, null);
        EgopPismenoEntity akt = EgopPismenoEntity.forAct(UUID.randomUUID(), RN, event.logId().toString(),
                "Obavijest o suspenziji registracijskog broja", EgopPismenoEntity.Smjer.IZLAZNO, "snimak".getBytes());
        akt.applySeal("%PDF-pecaceni".getBytes(), UUID.randomUUID(), "7K3M-Q9RT-X2VW", "B-LT", "sha-256=:x:");
        when(pismenoRepository.findFirstByRnAndActRefAndVrstaPismenaNaziv(RN, event.logId().toString(),
                StrDocumentType.SUSPENZIJA.vrstaPismenaNaziv())).thenReturn(Optional.of(akt));

        listener.onLifecycleChange(event);

        assertThat(captured().pdf()).isEqualTo("%PDF-pecaceni".getBytes());
        verify(documentService, never()).render(any(), any(), any());
    }

    /** Pečat još nije stigao: dostava čeka u redu ponovnog slanja umjesto da ode nepečaćena. */
    @Test
    void nonEu_sealingEnabled_notSealedYet_defersMail() {
        when(sealer.isEnabled()).thenReturn(true);
        when(lookup.findDetail(RN)).thenReturn(Optional.of(detail("ana@example.com", null)));
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INCOMPLETE_DOCUMENTATION,
                null, null);
        when(pismenoRepository.findFirstByRnAndActRefAndVrstaPismenaNaziv(RN, event.logId().toString(),
                StrDocumentType.SUSPENZIJA.vrstaPismenaNaziv())).thenReturn(Optional.empty());

        listener.onLifecycleChange(event);

        verify(emailService, never()).sendRnLifecycleNotification(any());
        verify(retryStore).recordFailure(MailRetryEntity.Kind.RN_LIFECYCLE, event.logId(), "not_sealed");
    }

    /** Iznajmljivač s OIB-om akt prima u pretinac; mail i dalje nosi presliku kao dosad. */
    @Test
    void withOib_sealingEnabled_keepsCopyAttachment() {
        when(sealer.isEnabled()).thenReturn(true);

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INCOMPLETE_DOCUMENTATION, null, null));

        assertThat(captured().pdf()).isEqualTo("pdf".getBytes());
        verify(pismenoRepository, never()).findFirstByRnAndActRefAndVrstaPismenaNaziv(any(), any(), any());
    }

    /** T17: prilozi službenika uz akt idu u privitak dok su zajedno do limita (15 MB). */
    @Test
    void officialAttachments_underLimit_areAttached() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INCOMPLETE_DOCUMENTATION,
                "INTERNAL:12345678903", null);
        when(prilogService.zaVlasnika(PrilogVlasnik.prijelaz(event.logId()))).thenReturn(List.of(
                TestPrilog.vezan(1, VrstaVlasnika.RN_LOG, event.logId(), "zapisnik.pdf", "INTERNAL:12345678903")
                        .withVelicina(14L * 1024 * 1024),
                TestPrilog.vezan(2, VrstaVlasnika.RN_LOG, event.logId(), "skica.pdf", "INTERNAL:12345678903")
                        .withVelicina(1024 * 1024)));
        when(prilogService.sadrzaj(1L)).thenReturn("a".getBytes());
        when(prilogService.sadrzaj(2L)).thenReturn("b".getBytes());

        listener.onLifecycleChange(event);

        RnLifecycleMail mail = captured();
        assertThat(mail.prilozi()).extracting(MailPrilog::naziv).containsExactly("zapisnik.pdf", "skica.pdf");
        assertThat(mail.prilozi().get(0).sadrzaj()).isEqualTo("a".getBytes());
        assertThat(mail.priloziIzvanPoruke()).isEmpty();
        assertThat(mail.pdf()).isNotEmpty();
    }

    /** Iznad limita poruka priloge samo navodi; sadržaj se i ne čita. */
    @Test
    void officialAttachments_overLimit_areOnlyListed() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.SUSPENDED, RnTrigger.INCOMPLETE_DOCUMENTATION,
                "INTERNAL:12345678903", null);
        when(prilogService.zaVlasnika(PrilogVlasnik.prijelaz(event.logId()))).thenReturn(List.of(
                TestPrilog.vezan(1, VrstaVlasnika.RN_LOG, event.logId(), "zapisnik.pdf", "INTERNAL:12345678903")
                        .withVelicina(10L * 1024 * 1024),
                TestPrilog.vezan(2, VrstaVlasnika.RN_LOG, event.logId(), "fotografije.pdf", "INTERNAL:12345678903")
                        .withVelicina(5L * 1024 * 1024 + 1)));

        listener.onLifecycleChange(event);

        RnLifecycleMail mail = captured();
        assertThat(mail.prilozi()).isEmpty();
        assertThat(mail.priloziIzvanPoruke()).containsExactly("zapisnik.pdf", "fotografije.pdf");
        verify(prilogService, never()).sadrzaj(any());
    }

    /** Prilozi stranke (npr. uz opoziv) ne vraćaju se stranci e-poštom — samo prilozi službenika. */
    @Test
    void lessorAttachments_areNotMailedBack() {
        RnLifecycleEvent event = event(RnStatus.ACTIVE, RnStatus.REVOKED, RnTrigger.REVOCATION,
                "NIAS:98765432109", null);
        when(prilogService.zaVlasnika(PrilogVlasnik.prijelaz(event.logId()))).thenReturn(List.of(
                TestPrilog.vezan(1, VrstaVlasnika.RN_LOG, event.logId(), "moj.pdf", "NIAS:98765432109")));

        listener.onLifecycleChange(event);

        RnLifecycleMail mail = captured();
        assertThat(mail.prilozi()).isEmpty();
        assertThat(mail.priloziIzvanPoruke()).isEmpty();
    }

    /** Pad čitanja priloga ne smije spriječiti obavijest. */
    @Test
    void attachmentLookupFailure_mailStillSent() {
        when(prilogService.zaVlasnika(any())).thenThrow(new IllegalStateException("db"));

        listener.onLifecycleChange(event(RnStatus.ACTIVE, RnStatus.SUSPENDED,
                RnTrigger.INCOMPLETE_DOCUMENTATION, null, null));

        assertThat(captured().prilozi()).isEmpty();
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
