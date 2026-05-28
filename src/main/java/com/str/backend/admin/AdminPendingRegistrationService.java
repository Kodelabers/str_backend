package com.str.backend.admin;

import com.str.backend.address.CountryEntity;
import com.str.backend.address.CountryRepository;
import com.str.backend.admin.dto.DocumentMetaDto;
import com.str.backend.admin.dto.PendingRegistrationDetailDto;
import com.str.backend.admin.dto.PendingRegistrationStatsDto;
import com.str.backend.admin.dto.PendingRegistrationSummaryDto;
import com.str.backend.domain.LessorApplicationStatus;
import com.str.backend.email.event.RegistrationApprovedEvent;
import com.str.backend.email.event.RegistrationRejectedEvent;
import com.str.backend.exception.ResourceNotFoundException;
import com.str.backend.lessor.LessorDocumentEntity;
import com.str.backend.lessor.LessorDocumentRepository;
import com.str.backend.lessor.LessorEntity;
import com.str.backend.lessor.LessorRepository;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class AdminPendingRegistrationService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Zagreb");

    private final LessorRepository lessorRepository;
    private final LessorDocumentRepository documentRepository;
    private final CountryRepository countryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AdminPendingRegistrationService(LessorRepository lessorRepository,
                                           LessorDocumentRepository documentRepository,
                                           CountryRepository countryRepository,
                                           ApplicationEventPublisher eventPublisher) {
        this.lessorRepository = lessorRepository;
        this.documentRepository = documentRepository;
        this.countryRepository = countryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public PendingRegistrationStatsDto getStats() {
        Instant startOfToday = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        Instant fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS);

        long totalPending = lessorRepository.countByApplicationStatus(LessorApplicationStatus.PENDING);
        long receivedToday = lessorRepository
                .countByApplicationStatusAndCreatedAtGreaterThanEqual(LessorApplicationStatus.PENDING, startOfToday);
        long olderThan5Days = lessorRepository
                .countByApplicationStatusAndCreatedAtLessThan(LessorApplicationStatus.PENDING, fiveDaysAgo);

        return new PendingRegistrationStatsDto(totalPending, receivedToday, olderThan5Days);
    }

    @Transactional(readOnly = true)
    public Page<PendingRegistrationSummaryDto> search(LessorApplicationStatus status,
                                                       String q,
                                                       String country,
                                                       String documentType,
                                                       Pageable pageable) {
        LessorApplicationStatus effectiveStatus = status != null ? status : LessorApplicationStatus.PENDING;
        String normalizedQ = blankToNull(q);
        String normalizedCountry = blankToNull(country);
        String normalizedDocumentType = blankToNull(documentType);
        return lessorRepository.searchRegistrations(
                effectiveStatus, normalizedQ, normalizedCountry, normalizedDocumentType, pageable);
    }

    @Transactional(readOnly = true)
    public PendingRegistrationDetailDto getDetail(UUID lessorId) {
        LessorEntity lessor = lessorRepository.findById(lessorId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Zahtjev za registraciju nije pronađen: " + lessorId));
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

    // @Transactional is load-bearing: RegistrationApprovedEvent fires @TransactionalEventListener(AFTER_COMMIT)
    @Transactional
    public void approve(UUID lessorId, String actorId) {
        LessorEntity lessor = findPendingOrThrow(lessorId);
        lessor.approveRegistration(actorId);
        eventPublisher.publishEvent(new RegistrationApprovedEvent(
                lessor.getLessorId(),
                lessor.getEmail(),
                lessor.getFirstName(),
                lessor.getUsername()
        ));
    }

    // @Transactional is load-bearing: RegistrationRejectedEvent fires @TransactionalEventListener(AFTER_COMMIT)
    @Transactional
    public void reject(UUID lessorId, String actorId) {
        LessorEntity lessor = findPendingOrThrow(lessorId);
        lessor.rejectRegistration(actorId);
        eventPublisher.publishEvent(new RegistrationRejectedEvent(
                lessor.getLessorId(),
                lessor.getEmail(),
                lessor.getFirstName()
        ));
    }

    @Transactional(readOnly = true)
    public byte[] exportXlsx(LessorApplicationStatus status, String q, String country, String documentType) {
        LessorApplicationStatus effectiveStatus = status != null ? status : LessorApplicationStatus.PENDING;
        List<PendingRegistrationSummaryDto> rows = lessorRepository
                .searchRegistrations(effectiveStatus, blankToNull(q), blankToNull(country),
                        blankToNull(documentType), Pageable.unpaged())
                .getContent();

        DateTimeFormatter dateFmt     = DateTimeFormatter.ofPattern("dd.MM.yyyy.");
        DateTimeFormatter dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm");

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Zahtjevi za registraciju");

            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(boldFont);

            String[] headers = {
                    "Ime", "Prezime", "Email", "Datum rođenja", "Vrsta dokumenta",
                    "Broj dokumenta", "Država boravka", "OIB / porezni broj", "Status", "Datum zahtjeva"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (PendingRegistrationSummaryDto row : rows) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue(nullToEmpty(row.firstName()));
                r.createCell(1).setCellValue(nullToEmpty(row.lastName()));
                r.createCell(2).setCellValue(nullToEmpty(row.email()));
                r.createCell(3).setCellValue(row.dateOfBirth() != null
                        ? row.dateOfBirth().format(dateFmt) : "");
                r.createCell(4).setCellValue(translateDocumentType(row.documentType()));
                r.createCell(5).setCellValue(nullToEmpty(row.documentNumber()));
                r.createCell(6).setCellValue(nullToEmpty(row.countryOfResidenceName()));
                r.createCell(7).setCellValue(nullToEmpty(row.taxNumber()));
                r.createCell(8).setCellValue(translateApplicationStatus(row.applicationStatus()));
                r.createCell(9).setCellValue(row.createdAt() != null
                        ? row.createdAt().atZone(ZONE).format(dateTimeFmt) : "");
            }

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Excel export", e);
        }
    }

    private LessorEntity findPendingOrThrow(UUID lessorId) {
        return lessorRepository.findByLessorIdAndApplicationStatus(lessorId, LessorApplicationStatus.PENDING)
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

    private static String translateDocumentType(String type) {
        if (type == null) return "";
        return switch (type) {
            case "PASSPORT" -> "Putovnica";
            case "ID_CARD"  -> "Osobna iskaznica";
            default         -> type;
        };
    }

    private static String translateApplicationStatus(LessorApplicationStatus status) {
        if (status == null) return "";
        return switch (status) {
            case PENDING  -> "Na čekanju";
            case ACCEPTED -> "Odobreno";
            case REJECTED -> "Odbijeno";
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String value) {
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }
}
