package com.str.backend.objection;

import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.exception.ConflictException;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import com.str.backend.registration.RnOwner;
import com.str.backend.rn.RegistrationNumberLogEntity;
import com.str.backend.rn.RegistrationNumberLogRepository;
import com.str.backend.rn.RnEntity;
import com.str.backend.rn.RnRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspensionObjectionServiceTest {

    private static final String OIB = "99999999990";
    private static final RnOwner OWNER = RnOwner.byOib(OIB);
    private static final String SUBMITTED_BY = "NIAS:11111111119";
    private static final UUID LESSOR_ID = UUID.fromString("0f6c2c9e-6a43-4d8f-9b0d-6a1f3b1b7c55");
    private static final String RN = "HR120001000000000123";
    private static final UUID LOG_ID = UUID.randomUUID();
    private static final byte[] PDF = "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1, 0x00};
    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    private final SuspensionObjectionRepository repository = mock(SuspensionObjectionRepository.class);
    private final RnRepository rnRepository = mock(RnRepository.class);
    private final RegistrationNumberLogRepository logRepository = mock(RegistrationNumberLogRepository.class);
    private final LessorRepository lessorRepository = mock(LessorRepository.class);
    private final SuspensionObjectionService service =
            new SuspensionObjectionService(repository, rnRepository, logRepository, lessorRepository);

    private final RnEntity rn = mock(RnEntity.class);

    @BeforeEach
    void ownedRnProposedForSuspension() {
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(true);
        when(rnRepository.findById(RN)).thenReturn(Optional.of(rn));
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENSION_PROPOSED);
        RegistrationNumberLogEntity proposal = mock(RegistrationNumberLogEntity.class);
        when(proposal.getLogId()).thenReturn(LOG_ID);
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "SUSPENSION_PROPOSED"))
                .thenReturn(Optional.of(proposal));
    }

    @Test
    void stores_pdfAsSubmitted_linkedToRnAndProposal() {
        SuspensionObjectionEntity saved = capture(OWNER, request(file("prigovor.pdf", PDF)));

        assertThat(saved.getLessorOib()).isEqualTo(OIB);
        assertThat(saved.getLessorId()).isNull();
        assertThat(saved.getRn()).isEqualTo(RN);
        assertThat(saved.getSubmittedBy()).isEqualTo(SUBMITTED_BY);
        assertThat(saved.getRnStatus()).isEqualTo(RnStatus.SUSPENSION_PROPOSED);
        assertThat(saved.getRnLogId()).isEqualTo(LOG_ID);
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getFileName()).isEqualTo("prigovor.pdf");
        assertThat(saved.getFileSize()).isEqualTo(PDF.length);
        assertThat(saved.getStatus()).isEqualTo(SuspensionObjectionStatus.SUBMITTED);
        assertThat(saved.getSubmittedAt()).isNotNull();
    }

    @Test
    void accepts_docAndDocx_byContentAndExtension() {
        assertThat(capture(OWNER, request(file("prigovor.doc", DOC))).getContentType())
                .isEqualTo("application/msword");
        assertThat(capture(OWNER, request(file("Prigovor.DOCX", ZIP))).getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    /** DOCX i DOC potpisi nisu jedinstveni — ZIP ili OLE2 s drugom ekstenzijom ne prolazi. */
    @Test
    void rejects_zipOrOle2WithoutWordExtension_andOtherTypes() {
        assertFileType(file("arhiva.zip", ZIP));
        assertFileType(file("tablica.xls", DOC));
        assertFileType(file("prigovor.png", PNG));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void suspendedRn_linksToSuspension() {
        when(rn.getStatus()).thenReturn(RnStatus.SUSPENDED);
        RegistrationNumberLogEntity suspension = mock(RegistrationNumberLogEntity.class);
        UUID suspensionLogId = UUID.randomUUID();
        when(suspension.getLogId()).thenReturn(suspensionLogId);
        when(logRepository.findFirstByRnAndToStatusOrderByOccurredAtDesc(RN, "SUSPENDED"))
                .thenReturn(Optional.of(suspension));

        SuspensionObjectionEntity saved = capture(OWNER, request(file("prigovor.pdf", PDF)));

        assertThat(saved.getRnStatus()).isEqualTo(RnStatus.SUSPENDED);
        assertThat(saved.getRnLogId()).isEqualTo(suspensionLogId);
    }

    @Test
    void nonEuLessor_ownedByLessorId_withEmailSnapshot() {
        when(rnRepository.isOwnedByLessor(RN, LESSOR_ID)).thenReturn(true);
        LessorEntity lessor = mock(LessorEntity.class);
        when(lessor.getEmail()).thenReturn("john@example.com");
        when(lessorRepository.findById(LESSOR_ID)).thenReturn(Optional.of(lessor));

        SuspensionObjectionEntity saved = capture(RnOwner.byLessor(LESSOR_ID), request(file("prigovor.pdf", PDF)));

        assertThat(saved.getLessorOib()).isNull();
        assertThat(saved.getLessorId()).isEqualTo(LESSOR_ID);
        assertThat(saved.getLessorEmail()).isEqualTo("john@example.com");
    }

    @Test
    void foreignRn_is404() {
        when(rnRepository.isOwnedByOib(RN, OIB)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(OWNER, SUBMITTED_BY, request(file("prigovor.pdf", PDF))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = RnStatus.class, names = {"ACTIVE", "REVOKED", "WITHDRAWN"})
    void rnOutsideProposalAndSuspension_is409(RnStatus status) {
        when(rn.getStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.submit(OWNER, SUBMITTED_BY, request(file("prigovor.pdf", PDF))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_RN_STATUS"));
    }

    @Test
    void pendingObjection_is409() {
        when(repository.existsByRnAndStatus(RN, SuspensionObjectionStatus.SUBMITTED)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(OWNER, SUBMITTED_BY, request(file("prigovor.pdf", PDF))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_ALREADY_SUBMITTED"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void mapsSubmittedIndexViolation_toAlreadySubmitted() {
        when(repository.saveAndFlush(any(SuspensionObjectionEntity.class))).thenThrow(new DataIntegrityViolationException(
                "dup", new ConstraintViolationException("dup", new SQLException("dup", "23505"),
                        SuspensionObjectionService.SUBMITTED_OBJECTION_INDEX)));

        assertThatThrownBy(() -> service.submit(OWNER, SUBMITTED_BY, request(file("prigovor.pdf", PDF))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OBJECTION_ALREADY_SUBMITTED"));
    }

    @Test
    void emptyFile_is400() {
        assertThatThrownBy(() -> service.submit(OWNER, SUBMITTED_BY, request(file("prigovor.pdf", new byte[0]))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.objection.file.empty");
    }

    private void assertFileType(MockMultipartFile file) {
        assertThatThrownBy(() -> service.submit(OWNER, SUBMITTED_BY, request(file)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.objection.file.type");
    }

    private SuspensionObjectionEntity capture(RnOwner owner, SuspensionObjectionRequest req) {
        when(repository.saveAndFlush(any(SuspensionObjectionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service.submit(owner, SUBMITTED_BY, req);

        ArgumentCaptor<SuspensionObjectionEntity> captor = ArgumentCaptor.forClass(SuspensionObjectionEntity.class);
        verify(repository, atLeastOnce()).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static SuspensionObjectionRequest request(MockMultipartFile file) {
        SuspensionObjectionRequest req = new SuspensionObjectionRequest();
        req.setDatoteka(file);
        req.setRegistrationNumber(RN);
        return req;
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("datoteka", name, "application/octet-stream", content);
    }
}
