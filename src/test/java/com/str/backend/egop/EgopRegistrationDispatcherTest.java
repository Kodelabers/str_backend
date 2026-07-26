package com.str.backend.egop;

import com.str.backend.accommodation.AccommodationEntity;
import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.document.StrDocumentService;
import com.str.backend.domain.EgopSyncStatus;
import com.str.backend.egop.exception.EgopBadRequestException;
import com.str.backend.email.EmailService;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.pdf.SubmissionPdfGenerator;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        EgopRetryPolicy retryPolicy =
                new EgopRetryPolicy(10, Duration.ofMinutes(2), Duration.ofHours(2));
        dispatcher = new EgopRegistrationDispatcher(store, accommodationRepository,
                lessorRepository, rnRepository, accommodationTypeRepository, pdfGenerator,
                documentService, egopFilingService, retryPolicy, emailService);

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
    }

    @Test
    void dispatch_euLessor_files_noEmail() throws Exception {
        lessor.setLessorOib("12345678901");
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenReturn(new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "pdf".getBytes()));

        dispatcher.dispatch(submission.getSubmissionId());

        verify(egopFilingService).fileRegistration(eq(submission), eq(lessor), any());
        verify(emailService, never()).sendRnIssuedNotification(anyString(), anyString(), anyString(), any());
    }

    @Test
    void dispatch_nonEuLessor_files_andEmails() throws Exception {
        // non-EU = bez OIB-a
        when(egopFilingService.fileRegistration(any(), any(), any()))
                .thenReturn(new EgopFilingService.FilingResult("KLASA: x, URBROJ: y", "pdf".getBytes()));

        dispatcher.dispatch(submission.getSubmissionId());

        verify(emailService).sendRnIssuedNotification(eq("ana@example.com"), eq("Ana"),
                eq("HR123456789012345678"), any());
        verify(store).markRnEmailSent(submission.getSubmissionId());
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
                .sendRnIssuedNotification(anyString(), anyString(), anyString(), any());
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
        verify(emailService).sendRnIssuedNotification(eq("ana@example.com"), eq("Ana"), anyString(), any());
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

        verify(emailService, never()).sendRnIssuedNotification(anyString(), anyString(), anyString(), any());
    }
}
