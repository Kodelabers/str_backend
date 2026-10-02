package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.FilingReference;
import com.str.backend.document.StrDocumentService;
import com.str.backend.document.StrDocumentType;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.domain.RnStatus;
import com.str.backend.email.EmailService;
import com.str.backend.email.MailRetryEntity;
import com.str.backend.email.MailRetryStore;
import com.str.backend.email.RnIssuedMail;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EgopRegistrationDispatcherTest {

    private EgopFilingStore store;
    private AccommodationRepository accommodationRepository;
    private LessorRepository lessorRepository;
    private RnRepository rnRepository;
    private AccommodationTypeRepository accommodationTypeRepository;
    private SubmissionPdfGenerator pdfGenerator;
    private StrDocumentService documentService;
    private EgopFilingService egopFilingService;
    private EmailService emailService;
    private MailRetryStore mailRetryStore;
    private EgopRegistrationDispatcher dispatcher;

    private SubmissionEntity submission;
    private LessorEntity lessor;
    private AccommodationEntity accommodation;
    private RnEntity rn;

    @BeforeEach
    void setUp() {
        store = mock(EgopFilingStore.class);
        accommodationRepository = mock(AccommodationRepository.class);
        lessorRepository = mock(LessorRepository.class);
        rnRepository = mock(RnRepository.class);
        accommodationTypeRepository = mock(AccommodationTypeRepository.class);
        pdfGenerator = mock(SubmissionPdfGenerator.class);
        documentService = mock(StrDocumentService.class);
        egopFilingService = mock(EgopFilingService.class);
        emailService = mock(EmailService.class);
        mailRetryStore = mock(MailRetryStore.class);
        EgopRetryPolicy retryPolicy =
                new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2));
        dispatcher = new EgopRegistrationDispatcher(store, accommodationRepository,
                lessorRepository, rnRepository, accommodationTypeRepository, pdfGenerator,
                documentService, egopFilingService, retryPolicy, emailService, mailRetryStore);

        lessor = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", "ana@example.com");
        submission = SubmissionEntity.create(null, lessor.getLessorId(), null, null, null, null);
        accommodation = mock(AccommodationEntity.class);
        when(accommodation.getName()).thenReturn("Apartman Sunce");
        when(accommodation.getCounty()).thenReturn("Grad Zagreb");
        when(accommodation.getPostalCode()).thenReturn("10000");
        when(accommodation.getAccommodationTypeId()).thenReturn(null);
        rn = mock(RnEntity.class);
        when(rn.getRn()).thenReturn("HR123456789012345678");

        when(store.findSubmission(submission.getSubmissionId())).thenReturn(Optional.of(submission));
        when(lessorRepository.findById(lessor.getLessorId())).thenReturn(Optional.of(lessor));
        when(accommodationRepository.findBySubmissionId(submission.getSubmissionId()))
                .thenReturn(List.of(accommodation));
        when(rnRepository.findBySubmissionId(submission.getSubmissionId())).thenReturn(List.of(rn));
        // markFailed vraća broj pokušaja nakon inkrementa, kao i prava implementacija
        when(store.markFailed(any(), any(), any(), any())).thenAnswer(inv -> {
            submission.markEgopFailed(inv.getArgument(1), inv.getArgument(2));
            return submission.getEgopSyncAttempts();
        });
        when(emailService.sendRnIssuedNotification(any())).thenReturn(true);
        // Obavijest o dodjeli bez urudžbenih oznaka — kad mock urudžbiranja ne prođe do izlaznog pismena.
        when(documentService.render(eq(StrDocumentType.DODJELA), anyString(), isNull()))
                .thenReturn("dodjela".getBytes());
    }

    /**
     * Iznajmljivač s OIB-om dobiva obavijest s brojem i objektom, ali bez privitka — obavijest
     * o dodjeli ide u korisnički pretinac. Prije je ovaj put mail preskakao u potpunosti.
     */
    @Test
    void dispatch_lessorWithOib_emailsNoticeWithoutPdf() throws Exception {
        lessor.setLessorOib("12345678901");
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenReturn(new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "pdf".getBytes()));

        dispatcher.dispatch(submission.getSubmissionId());

        verify(egopFilingService).fileRegistration(eq(submission), eq(lessor), any());
        RnIssuedMail mail = sentMail();
        assertEquals("ana@example.com", mail.to());
        assertEquals("Ana", mail.ime());
        assertEquals("HR123456789012345678", mail.rn());
        assertEquals("Apartman Sunce", mail.objekt());
        assertFalse(mail.dostavaMailom());
        assertNull(mail.pdf());
        verify(store).markRnEmailSent(submission.getSubmissionId());
    }

    /** Obavijest ne treba PDF, pa je ne smije blokirati ni pad urudžbiranja ni pad rendera. */
    @Test
    void dispatch_lessorWithOib_filingAndPdfFail_stillEmails() throws Exception {
        lessor.setLessorOib("12345678901");
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenThrow(new EgopBadRequestException("eGOP down"));
        when(pdfGenerator.generate(any())).thenThrow(new IllegalStateException("render"));

        dispatcher.dispatch(submission.getSubmissionId());

        assertFalse(sentMail().dostavaMailom());
    }

    /**
     * Non-EU: mail je dostava, pa u privitku ide akt — obavijest o dodjeli s URBROJ-em
     * izlaznog pismena, ista koja je priložena u eGOP — a ne PDF zahtjeva.
     */
    @Test
    void dispatch_nonEuLessor_emailsNumberedNoticeFromFiling() throws Exception {
        // non-EU = bez OIB-a
        FilingReference izlazno = new FilingReference("UP/I-334-01/26-01/1", "2181-26-2");
        when(documentService.render(eq(StrDocumentType.DODJELA), anyString(), isNull(), eq(izlazno)))
                .thenReturn("dodjela-urbroj".getBytes());
        when(egopFilingService.fileRegistration(any(), any(), any())).thenAnswer(inv -> {
            EgopDocumentSupplier documents = inv.getArgument(2);
            documents.obavijestODodjeli(izlazno);
            return new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "zahtjev".getBytes());
        });

        dispatcher.dispatch(submission.getSubmissionId());

        RnIssuedMail mail = sentMail();
        assertEquals("ana@example.com", mail.to());
        assertEquals("HR123456789012345678", mail.rn());
        assertTrue(mail.dostavaMailom());
        assertArrayEquals("dodjela-urbroj".getBytes(), mail.pdf());
        verify(documentService, never()).render(any(), anyString(), any());
        verify(store).markRnEmailSent(submission.getSubmissionId());
    }

    /** Urudžbiranje nije stiglo do izlaznog pismena — akt se renderira bez URBROJ-a i ipak šalje. */
    @Test
    void dispatch_nonEuLessor_filingFails_emailsNoticeWithoutUrbroj() throws Exception {
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenThrow(new EgopBadRequestException("eGOP down"));

        dispatcher.dispatch(submission.getSubmissionId());

        assertArrayEquals("dodjela".getBytes(), sentMail().pdf());
        verify(store).markRnEmailSent(submission.getSubmissionId());
    }

    /**
     * Bez akta nema dostave: poruka bi tvrdila da je akt u privitku. PDF zahtjeva ga ne smije
     * zamijeniti, a predmet ostaje neoznačen da ga retry pokuša ponovo.
     */
    @Test
    void dispatch_nonEuLessor_noticeRenderFails_noEmail_notMarked() throws Exception {
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenThrow(new EgopBadRequestException("eGOP down"));
        when(pdfGenerator.generate(any())).thenReturn("zahtjev".getBytes());
        when(documentService.render(eq(StrDocumentType.DODJELA), anyString(), isNull()))
                .thenThrow(new IllegalStateException("predložak"));

        dispatcher.dispatch(submission.getSubmissionId());

        verify(emailService, never()).sendRnIssuedNotification(any());
        assertNull(submission.getRnEmailSentAt());
    }

    /**
     * SMTP neuspjeh ne smije ostaviti trag „poslano" — inače ga sljedeći dispatch (retry)
     * preskoči i iznajmljivač obavijest nikad ne dobije.
     */
    @Test
    void dispatch_emailNotSent_notMarked_retriedOnNextDispatch() throws Exception {
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenReturn(new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "pdf".getBytes()));
        when(emailService.sendRnIssuedNotification(any())).thenReturn(false, true);

        dispatcher.dispatch(submission.getSubmissionId());

        assertNull(submission.getRnEmailSentAt());
        verify(store, never()).markRnEmailSent(any());
        verify(mailRetryStore).recordFailure(
                MailRetryEntity.Kind.RN_ISSUED, submission.getSubmissionId(), "smtp_failed");

        dispatcher.dispatch(submission.getSubmissionId());

        verify(emailService, times(2)).sendRnIssuedNotification(any());
        verify(store).markRnEmailSent(submission.getSubmissionId());
        verify(mailRetryStore).markSent(MailRetryEntity.Kind.RN_ISSUED, submission.getSubmissionId());
    }

    /**
     * Ponovno slanje ne smije ponovo urudžbiti — predmet je možda već {@code SYNCED}, a
     * urudžbiranje ima vlastiti retry.
     */
    @Test
    void retryEmail_sendsWithoutFiling() throws Exception {
        lessor.setLessorOib("12345678901");

        dispatcher.retryEmail(submission.getSubmissionId());

        verify(egopFilingService, never()).fileRegistration(any(), any(), any());
        assertEquals("HR123456789012345678", sentMail().rn());
        verify(mailRetryStore).markSent(MailRetryEntity.Kind.RN_ISSUED, submission.getSubmissionId());
    }

    /**
     * Non-EU, predmet već urudžbiran: ponovno poslani akt nosi URBROJ spremljenog izlaznog
     * pismena — isti dokument koji je u eGOP-u, ne verziju bez urudžbenog broja.
     */
    @Test
    void retryEmail_nonEu_usesStoredOutgoingFilingNumber() {
        EgopPismenoEntity izlazno = EgopPismenoEntity.create(submission.getSubmissionId(),
                StrDocumentType.DODJELA.vrstaPismenaNaziv(), EgopPismenoEntity.Smjer.IZLAZNO,
                7, "2181-26-2", "OBV", false);
        when(store.findPismeno(submission.getSubmissionId(), StrDocumentType.DODJELA.vrstaPismenaNaziv(),
                EgopPismenoEntity.ACT_REF_REGISTRACIJA)).thenReturn(Optional.of(izlazno));
        when(documentService.render(eq(StrDocumentType.DODJELA), anyString(), isNull(),
                eq(new FilingReference(null, "2181-26-2", 7))))
                .thenReturn("dodjela-urbroj".getBytes());

        dispatcher.retryEmail(submission.getSubmissionId());

        assertArrayEquals("dodjela-urbroj".getBytes(), sentMail().pdf());
    }

    /**
     * Ponovno urudžbiranje ne šalje obavijest koja već čeka u redu — inače bi EgopRetryJob trošio
     * pokušaje maila mimo njegovog backoffa i red bi odustao prije vremena.
     */
    @Test
    void dispatch_mailAlreadyQueued_leavesItToMailRetryJob() throws Exception {
        when(mailRetryStore.isQueued(MailRetryEntity.Kind.RN_ISSUED, submission.getSubmissionId()))
                .thenReturn(true);
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenReturn(new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "pdf".getBytes()));

        dispatcher.dispatch(submission.getSubmissionId());

        verify(egopFilingService).fileRegistration(eq(submission), eq(lessor), any());
        verify(emailService, never()).sendRnIssuedNotification(any());
        verify(mailRetryStore, never()).recordFailure(any(), any(), any());
    }

    /** Za povučeni RB obavijest o izdavanju više ne vrijedi. */
    @Test
    void retryEmail_withdrawnRn_isAbandoned() {
        when(rn.getStatus()).thenReturn(RnStatus.WITHDRAWN);

        dispatcher.retryEmail(submission.getSubmissionId());

        verify(emailService, never()).sendRnIssuedNotification(any());
        verify(mailRetryStore).abandon(MailRetryEntity.Kind.RN_ISSUED, submission.getSubmissionId(), "superseded");
    }

    /** Bez adrese ponavljanje nema smisla — zapis se zatvara umjesto da se vrti do iscrpljenja. */
    @Test
    void noEmail_abandonsRetry() {
        LessorEntity bezMaila = LessorEntity.create("Ana", "Anić", "Ilica", "1", "Zagreb", "Grad Zagreb", null);
        SubmissionEntity sub = SubmissionEntity.create(null, bezMaila.getLessorId(), null, null, null, null);
        when(store.findSubmission(sub.getSubmissionId())).thenReturn(Optional.of(sub));
        when(lessorRepository.findById(bezMaila.getLessorId())).thenReturn(Optional.of(bezMaila));
        when(accommodationRepository.findBySubmissionId(sub.getSubmissionId())).thenReturn(List.of(accommodation));
        when(rnRepository.findBySubmissionId(sub.getSubmissionId())).thenReturn(List.of(rn));

        dispatcher.retryEmail(sub.getSubmissionId());

        verify(emailService, never()).sendRnIssuedNotification(any());
        verify(mailRetryStore).abandon(MailRetryEntity.Kind.RN_ISSUED, sub.getSubmissionId(), "no_email");
    }

    /** Postojeći eTurizam objekt smije biti bez adrese — opis ne smije imati viseće zareze. */
    @Test
    void objekt_joinsOnlyPresentParts() {
        AccommodationEntity a = mock(AccommodationEntity.class);
        when(a.getName()).thenReturn("Apartman Sunce");
        when(a.getStreet()).thenReturn("Ilica");
        when(a.getStreetNumber()).thenReturn("1");
        when(a.getSettlement()).thenReturn(" ");
        when(a.getCity()).thenReturn("Zagreb");
        assertEquals("Apartman Sunce, Ilica 1, Zagreb", EgopRegistrationDispatcher.objekt(a));

        AccommodationEntity prazan = mock(AccommodationEntity.class);
        assertEquals("—", EgopRegistrationDispatcher.objekt(prazan));
    }

    /**
     * Retry job zove dispatch ponovo do 10 puta — bez oznake o poslanoj dostavi
     * non-EU iznajmljivač bi dobio isto toliko identičnih mailova.
     */
    @Test
    void dispatch_calledTwice_emailsOnce() throws Exception {
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenReturn(new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "pdf".getBytes()));

        dispatcher.dispatch(submission.getSubmissionId());
        dispatcher.dispatch(submission.getSubmissionId());

        verify(emailService, times(1))
                .sendRnIssuedNotification(any());
    }

    @Test
    void dispatch_filingFails_marksFailed_stillEmailsNonEu() throws Exception {
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenThrow(new EgopBadRequestException("eGOP down"));
        when(pdfGenerator.generate(any())).thenReturn("fallback".getBytes());

        dispatcher.dispatch(submission.getSubmissionId());

        assertEquals(EgopSyncStatus.FAILED, submission.getEgopSyncStatus());
        assertEquals(1, submission.getEgopSyncAttempts());
        // fallback PDF (bez filing broja) ipak ide non-EU korisniku
        assertEquals("ana@example.com", sentMail().to());
    }

    /** Backoff se mora upisati, inače retry job odmah ponovo napada isti submission. */
    @Test
    void dispatch_filingFails_schedulesNextAttempt() throws Exception {
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenThrow(new EgopBadRequestException("eGOP down"));

        dispatcher.dispatch(submission.getSubmissionId());

        Instant next = submission.getEgopNextAttemptAt();
        assertNotNull(next);
        // prvi pokušaj => base (2 min); dopuštamo malu toleranciju na protek vremena
        assertEquals(true, next.isAfter(Instant.now().plus(Duration.ofSeconds(90))));
        assertEquals(true, next.isBefore(Instant.now().plus(Duration.ofMinutes(3))));
    }

    @Test
    void dispatch_missingRn_skips() {
        when(rnRepository.findBySubmissionId(submission.getSubmissionId())).thenReturn(List.of());

        dispatcher.dispatch(submission.getSubmissionId());

        verify(emailService, never()).sendRnIssuedNotification(any());
    }

    private RnIssuedMail sentMail() {
        ArgumentCaptor<RnIssuedMail> captor = ArgumentCaptor.forClass(RnIssuedMail.class);
        verify(emailService).sendRnIssuedNotification(captor.capture());
        return captor.getValue();
    }
}
