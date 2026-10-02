package com.str.backend.registration;

import com.str.backend.accommodation.AccommodationRepository;
import com.str.backend.address.CadastreResolver;
import com.str.backend.address.CountyRepository;
import com.str.backend.address.HouseNumberRepository;
import com.str.backend.address.MunicipalityRepository;
import com.str.backend.address.SettlementRepository;
import com.str.backend.draft.SubmissionDraftService;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.lessor.SubjectProfileService;
import com.str.backend.lookup.AccommodationTypeRepository;
import com.str.backend.request.SubmissionEntity;
import com.str.backend.request.SubmissionRepository;
import com.str.backend.rn.RnRepository;
import com.str.backend.rn.RnService;
import com.str.backend.str.FacilityClaimVerifier;
import com.str.backend.validation.ParallelValidationOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PDF zahtjeva nosi ime, OIB i adresu prebivališta podnositelja — smije ga preuzeti samo vlasnik.
 * Do sada ga je štitila samo neprobojnost UUID-a.
 */
class RegistrationServicePdfAccessTest {

    private static final String OIB = "12312312316";

    private final LessorRepository lessorRepository = mock(LessorRepository.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private RegistrationService service;
    private LessorEntity owner;
    private SubmissionEntity submission;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                lessorRepository, mock(AccommodationRepository.class), submissionRepository,
                mock(ParallelValidationOrchestrator.class), mock(RnService.class), mock(RnRepository.class),
                mock(SubjectProfileService.class), mock(CountyRepository.class),
                mock(MunicipalityRepository.class), mock(SettlementRepository.class),
                mock(AccommodationTypeRepository.class), mock(FacilityClaimVerifier.class),
                new CadastreResolver(mock(HouseNumberRepository.class)), mock(ApplicationEventPublisher.class),
                mock(SubmissionDraftService.class));

        owner = LessorEntity.create("Pero", "Perić", "Ilica", "1", "Zagreb", "Grad Zagreb", "pero@example.hr");
        owner.setLessorOib(OIB);
        submission = SubmissionEntity.create(null, owner.getLessorId(), null, Instant.now(), null,
                "%PDF-1.4".getBytes());
        when(submissionRepository.findById(submission.getSubmissionId())).thenReturn(Optional.of(submission));
        when(lessorRepository.findById(owner.getLessorId())).thenReturn(Optional.of(owner));
    }

    @Test
    void niasOwner_getsPdf() {
        assertThat(service.getSubmissionForPdf(submission.getSubmissionId(), SubmissionRequester.oib(OIB)))
                .isSameAs(submission);
    }

    /** Tuđi zahtjev daje isti 404 kao nepostojeći — ne otkriva se da postoji. */
    @Test
    void niasStranger_getsNotFound() {
        assertThatThrownBy(() -> service.getSubmissionForPdf(submission.getSubmissionId(),
                SubmissionRequester.oib("19819819816")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nonEuOwner_getsPdf_byLessorId() {
        assertThat(service.getSubmissionForPdf(submission.getSubmissionId(),
                SubmissionRequester.lessor(owner.getLessorId()))).isSameAs(submission);
    }

    @Test
    void nonEuStranger_getsNotFound() {
        assertThatThrownBy(() -> service.getSubmissionForPdf(submission.getSubmissionId(),
                SubmissionRequester.lessor(UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unrestricted_getsPdf() {
        assertThat(service.getSubmissionForPdf(submission.getSubmissionId(), SubmissionRequester.ANYONE))
                .isSameAs(submission);
    }
}
