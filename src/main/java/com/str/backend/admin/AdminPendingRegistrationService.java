package com.str.backend.admin;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.admin.dto.DocumentMetaDto;
import com.str.backend.admin.dto.PendingRegistrationDetailDto;
import com.str.backend.admin.dto.PendingRegistrationStatsDto;
import com.str.backend.admin.dto.PendingRegistrationSummaryDto;
import com.str.backend.domain.SubmissionStatus;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorDocumentEntity;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AdminPendingRegistrationService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Zagreb");

    private final LessorRepository lessorRepository;
    private final LessorDocumentRepository documentRepository;
    private final CountryRepository countryRepository;

    public AdminPendingRegistrationService(LessorRepository lessorRepository,
                                           LessorDocumentRepository documentRepository,
                                           CountryRepository countryRepository) {
        this.lessorRepository = lessorRepository;
        this.documentRepository = documentRepository;
        this.countryRepository = countryRepository;
    }

    @Transactional(readOnly = true)
    public PendingRegistrationStatsDto getStats() {
        Instant startOfToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS);

        long totalPending = lessorRepository.countByApplicationStatus(SubmissionStatus.INITIATED);
        long receivedToday = lessorRepository
                .countByApplicationStatusAndCreatedAtGreaterThanEqual(SubmissionStatus.INITIATED, startOfToday);
        long olderThan5Days = lessorRepository
                .countByApplicationStatusAndCreatedAtLessThan(SubmissionStatus.INITIATED, fiveDaysAgo);

        return new PendingRegistrationStatsDto(totalPending, receivedToday, olderThan5Days);
    }

    @Transactional(readOnly = true)
    public Page<PendingRegistrationSummaryDto> search(SubmissionStatus status,
                                                       String q,
                                                       String country,
                                                       String documentType,
                                                       Pageable pageable) {
        SubmissionStatus effectiveStatus = status != null ? status : SubmissionStatus.INITIATED;
        String normalizedQ = blankToNull(q);
        String normalizedCountry = blankToNull(country);
        String normalizedDocumentType = blankToNull(documentType);
        return lessorRepository.searchRegistrations(
                effectiveStatus, normalizedQ, normalizedCountry, normalizedDocumentType, pageable);
    }

    @Transactional(readOnly = true)
    public PendingRegistrationDetailDto getDetail(UUID lessorId) {
        LessorEntity lessor = findPendingOrThrow(lessorId);
        LessorDocumentEntity doc = findDocumentOrThrow(lessorId);
        return toDetail(lessor, doc);
    }

    @Transactional(readOnly = true)
    public byte[] getFrontImage(UUID lessorId) {
        LessorDocumentEntity doc = findDocumentOrThrow(lessorId);
        return doc.getFrontImage();
    }

    @Transactional(readOnly = true)
    public byte[] getBackImage(UUID lessorId) {
        LessorDocumentEntity doc = findDocumentOrThrow(lessorId);
        if (doc.getBackImage() == null) {
            throw new ResourceNotFoundException("Stražnja strana isprave nije dostupna.");
        }
        return doc.getBackImage();
    }

    @Transactional
    public void approve(UUID lessorId) {
        LessorEntity lessor = findPendingOrThrow(lessorId);
        lessor.approveRegistration();
    }

    @Transactional
    public void reject(UUID lessorId) {
        LessorEntity lessor = findPendingOrThrow(lessorId);
        lessor.rejectRegistration();
    }

    private LessorEntity findPendingOrThrow(UUID lessorId) {
        return lessorRepository.findByLessorIdAndApplicationStatus(lessorId, SubmissionStatus.INITIATED)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Zahtjev za registraciju nije pronađen: " + lessorId));
    }

    private LessorDocumentEntity findDocumentOrThrow(UUID lessorId) {
        return documentRepository.findByLessorId(lessorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Dokument nije pronađen za korisnika: " + lessorId));
    }

    private PendingRegistrationDetailDto toDetail(LessorEntity e, LessorDocumentEntity doc) {
        DocumentMetaDto docMeta = new DocumentMetaDto(
                doc.getDocumentId(),
                doc.getDocumentType(),
                doc.getDocumentNumber(),
                true,
                doc.getBackImage() != null
        );
        return new PendingRegistrationDetailDto(
                e.getLessorId(),
                e.getFirstName(),
                e.getLastName(),
                e.getEmail(),
                e.getDateOfBirth(),
                e.getCountryOfResidenceId(),
                resolveCountryName(e.getCountryOfResidenceId()),
                e.getTaxNumber(),
                e.getStreet(),
                e.getMobileNumber(),
                e.getApplicationStatus(),
                e.getCreatedAt(),
                docMeta
        );
    }

    private String resolveCountryName(Integer countryId) {
        if (countryId == null) {
            return null;
        }
        return countryRepository.findById(countryId.longValue())
                .map(CountryEntity::getName)
                .orElse(null);
    }

    private static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
}
