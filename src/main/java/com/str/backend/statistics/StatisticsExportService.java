package com.str.backend.statistics;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.str.backend.domain.RnStatus;
import com.str.backend.exception.BusinessException;
import com.str.backend.pdf.PdfFonts;
import com.str.backend.rn.RnRegistryView;
import com.str.backend.rn.RnService;
import com.str.backend.rn.dto.RnSummaryDto;
import com.str.backend.statistics.StatisticsRepository.DetailRowProjection;
import com.str.backend.statistics.dto.StrResponse;
import com.str.backend.statistics.dto.CountyStrDto;
import com.str.backend.statistics.dto.PlatformActivityRowDto;
import com.str.backend.statistics.dto.PlatformChipDto;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class StatisticsExportService {

    private static final Color BLUE  = new Color(46, 116, 181);
    private static final Color WHITE = Color.WHITE;
    private static final Color BLACK = Color.BLACK;

    private static final Locale           HR           = Locale.forLanguageTag("hr");
    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("dd.MM.yyyy.");
    private static final DateTimeFormatter ISO_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final List<String> EXPORT_STATUSES =
            List.of(RnStatus.ACTIVE.name(), RnStatus.SUSPENSION_PROPOSED.name(),
                    RnStatus.SUSPENDED.name(), RnStatus.WITHDRAWN.name());

    /**
     * Upper bound for a single activity export. Measured on this code path: 50k rows takes ~20s
     * and ~700MB of heap with the non-streaming writer, 200k takes ~86s and ~2.7GB. Streaming
     * removes most of that, but the cap stays as the backstop that keeps one request from
     * monopolising the service — past this point the answer is to narrow the filters.
     */
    static final int MAX_EXPORT_ROWS = 50_000;

    /** Rows SXSSF keeps in memory; older ones are flushed to a temp file. */
    private static final int SXSSF_WINDOW_ROWS = 200;

    private static final int CSV_INITIAL_BUFFER_BYTES = 1 << 16;

    private static final Font FNT_TITLE;
    private static final Font FNT_TH;
    private static final Font FNT_TD;
    private static final Font FNT_DATE;

    static {
        BaseFont bf = PdfFonts.loadArial();
        FNT_TITLE = new Font(bf, 14, Font.BOLD,   BLUE);
        FNT_TH    = new Font(bf,  9, Font.BOLD,   WHITE);
        FNT_TD    = new Font(bf,  9, Font.NORMAL, BLACK);
        FNT_DATE  = new Font(bf,  9, Font.NORMAL, new Color(100, 100, 100));
    }

    private static final DateTimeFormatter REPORTED_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy.").withZone(ZoneId.of("Europe/Zagreb"));

    private final StatisticsService    statisticsService;
    private final StatisticsRepository statisticsRepository;
    private final PlatformActivityQuery platformActivityQuery;
    private final RnService            rnService;

    public StatisticsExportService(StatisticsService statisticsService,
                                   StatisticsRepository statisticsRepository,
                                   PlatformActivityQuery platformActivityQuery,
                                   RnService rnService) {
        this.statisticsService     = statisticsService;
        this.statisticsRepository  = statisticsRepository;
        this.platformActivityQuery = platformActivityQuery;
        this.rnService             = rnService;
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    public byte[] generateStrPdf() {
        StrResponse data = statisticsService.str(null, null);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 40, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Paragraph title = new Paragraph("STR – Registracijski brojevi u brojkama", FNT_TITLE);
            title.setSpacingAfter(4);
            doc.add(title);

            Paragraph dateP = new Paragraph("Generirano: " + LocalDate.now().format(DATE_FMT), FNT_DATE);
            dateP.setSpacingAfter(14);
            doc.add(dateP);

            // KPI summary table
            String[] kpiHeaders = {
                    "Ukupno objekata", "Registrirani objekti", "Pokrivenost registracijom"
            };
            PdfPTable kpiTable = new PdfPTable(3);
            kpiTable.setWidthPercentage(100);
            kpiTable.setSpacingAfter(16);
            for (String h : kpiHeaders) kpiTable.addCell(thCell(h));
            kpiTable.addCell(tdCell(String.valueOf(data.totals().totalObjects())));
            kpiTable.addCell(tdCell(String.valueOf(data.totals().totalRn())));
            kpiTable.addCell(tdCell(formatRate(data.totals().coverageRate())));
            doc.add(kpiTable);

            // County breakdown table
            String[] countyHeaders = {
                    "Županija", "Objekti", "Aktivni RB", "Suspendirani", "Povučeni", "% registriranih"
            };
            float[] colWidths = {4f, 1.5f, 1.5f, 1.5f, 1.5f, 2f};
            PdfPTable countyTable = new PdfPTable(colWidths);
            countyTable.setWidthPercentage(100);
            for (String h : countyHeaders) countyTable.addCell(thCell(h));
            for (CountyStrDto row : data.counties()) {
                countyTable.addCell(tdCell(row.countyName()));
                countyTable.addCell(tdCell(String.valueOf(row.accommodations())));
                countyTable.addCell(tdCell(String.valueOf(row.activeRn())));
                countyTable.addCell(tdCell(String.valueOf(row.suspendedRn())));
                countyTable.addCell(tdCell(String.valueOf(row.withdrawnRn())));
                countyTable.addCell(tdCell(formatRate(row.registrationRate())));
            }
            doc.add(countyTable);

            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate STR PDF", e);
        }
    }

    private static PdfPCell thCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FNT_TH));
        cell.setBackgroundColor(BLUE);
        cell.setPadding(5);
        cell.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
        return cell;
    }

    private static PdfPCell tdCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FNT_TD));
        cell.setPadding(4);
        cell.setHorizontalAlignment(PdfPCell.ALIGN_CENTER);
        return cell;
    }

    private static String formatRate(double rate) {
        return String.format(HR, "%.1f %%", rate);
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] generateStrCsv() {
        List<DetailRowProjection> rows = statisticsRepository.findDetailRows(EXPORT_STATUSES);
        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM for Excel-compatible UTF-8
        sb.append("Registracijski broj,Naziv objekta,Adresa,Grad/naselje,Županija,")
          .append("Kategorija,Tip ponude,Status RB,Datum izdavanja,")
          .append("Vrijedi od,Vrijedi do,Max ležajeva\r\n");
        for (DetailRowProjection row : rows) {
            sb.append(csvEscape(row.getRn())).append(',')
              .append(csvEscape(row.getName())).append(',')
              .append(csvEscape(address(row))).append(',')
              .append(csvEscape(row.getCityName())).append(',')
              .append(csvEscape(row.getCounty())).append(',')
              .append(csvEscape(row.getCategory())).append(',')
              .append(csvEscape(row.getOfferType())).append(',')
              .append(csvEscape(translateStatus(row.getStatus()))).append(',')
              .append(csvEscape(formatDate(row.getIssueDate()))).append(',')
              .append(csvEscape(formatDate(row.getValidFrom()))).append(',')
              .append(csvEscape(formatDate(row.getValidTo()))).append(',')
              .append(csvEscape(row.getMaxBeds())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Excel ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] generateStrXlsx() {
        List<DetailRowProjection> rows = statisticsRepository.findDetailRows(EXPORT_STATUSES);
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("STR detaljna statistika");

            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(boldFont);

            String[] headers = {
                    "Registracijski broj", "Naziv objekta", "Adresa", "Grad/naselje", "Županija",
                    "Kategorija", "Tip ponude", "Status RB", "Datum izdavanja",
                    "Vrijedi od", "Vrijedi do", "Max ležajeva"
            };
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (DetailRowProjection row : rows) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue(nullToEmpty(row.getRn()));
                r.createCell(1).setCellValue(nullToEmpty(row.getName()));
                r.createCell(2).setCellValue(address(row));
                r.createCell(3).setCellValue(nullToEmpty(row.getCityName()));
                r.createCell(4).setCellValue(nullToEmpty(row.getCounty()));
                r.createCell(5).setCellValue(nullToEmpty(row.getCategory()));
                r.createCell(6).setCellValue(nullToEmpty(row.getOfferType()));
                r.createCell(7).setCellValue(translateStatus(row.getStatus()));
                r.createCell(8).setCellValue(formatDate(row.getIssueDate()));
                r.createCell(9).setCellValue(formatDate(row.getValidFrom()));
                r.createCell(10).setCellValue(formatDate(row.getValidTo()));
                r.createCell(11).setCellValue(row.getMaxBeds() != null ? row.getMaxBeds() : 0);
            }

            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate STR Excel", e);
        }
    }

    // ── RN Registry export ────────────────────────────────────────────────────

    private static final String[] RN_HEADERS = {
            "Registracijski broj", "Status", "Datum izdavanja", "Vrijedi od", "Vrijedi do",
            "Rok za očitovanje",
            "Naziv objekta", "Vrsta objekta", "Iznajmljivač", "Ulica i kbr.", "Grad/naselje", "Županija"
    };

    private List<RnSummaryDto> fetchRegistryForExport(RnRegistryView view, String q, String county,
                                                      String municipality, Long typeId,
                                                      boolean foreignOnly, String rb, String city,
                                                      String street, String name, String lessor,
                                                      Integer deadlineWithinDays) {
        List<RnSummaryDto> rows = rnService.searchRegistryForExport(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays);
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw new BusinessException("error.export.too.many.rows");
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public byte[] generateRegistryXlsx(RnRegistryView view, String q, String county, String municipality,
                                       Long typeId, boolean foreignOnly, String rb, String city,
                                       String street, String name, String lessor,
                                      Integer deadlineWithinDays) {
        List<RnSummaryDto> rows = fetchRegistryForExport(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays);
        SXSSFWorkbook wb = new SXSSFWorkbook(SXSSF_WINDOW_ROWS);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Registar RB");
            org.apache.poi.ss.usermodel.Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(boldFont);
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < RN_HEADERS.length; i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(RN_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }
            int rowIdx = 1;
            for (RnSummaryDto r : rows) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(nullToEmpty(r.rn()));
                row.createCell(1).setCellValue(translateStatus(r.status() != null ? r.status().name() : null));
                row.createCell(2).setCellValue(formatDate(r.issueDate()));
                row.createCell(3).setCellValue(formatDate(r.validFrom()));
                row.createCell(4).setCellValue(formatDate(r.validTo()));
                row.createCell(5).setCellValue(formatDate(r.suspensionDeadline()));
                row.createCell(6).setCellValue(nullToEmpty(r.accommodationName()));
                row.createCell(7).setCellValue(nullToEmpty(r.accommodationTypeName()));
                row.createCell(8).setCellValue(rnLessorLabel(r));
                row.createCell(9).setCellValue(rnAddress(r));
                row.createCell(10).setCellValue(nullToEmpty(r.city()));
                row.createCell(11).setCellValue(nullToEmpty(r.county()));
            }
            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate registry Excel", e);
        } finally {
            wb.dispose();
        }
    }

    @Transactional(readOnly = true)
    public byte[] generateRegistryCsv(RnRegistryView view, String q, String county, String municipality,
                                      Long typeId, boolean foreignOnly, String rb, String city,
                                      String street, String name, String lessor,
                                      Integer deadlineWithinDays) {
        List<RnSummaryDto> rows = fetchRegistryForExport(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(CSV_INITIAL_BUFFER_BYTES);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            out.write('\uFEFF'); // BOM for Excel-compatible UTF-8
            out.write(String.join(",", RN_HEADERS));
            out.write("\r\n");
            for (RnSummaryDto r : rows) {
                out.write(csvEscape(r.rn()));                                       out.write(',');
                out.write(csvEscape(translateStatus(r.status() != null ? r.status().name() : null))); out.write(',');
                out.write(csvEscape(formatDate(r.issueDate())));                    out.write(',');
                out.write(csvEscape(formatDate(r.validFrom())));                    out.write(',');
                out.write(csvEscape(formatDate(r.validTo())));                      out.write(',');
                out.write(csvEscape(formatDate(r.suspensionDeadline())));           out.write(',');
                out.write(csvEscape(r.accommodationName()));                        out.write(',');
                out.write(csvEscape(r.accommodationTypeName()));                    out.write(',');
                out.write(csvEscape(rnLessorLabel(r)));                             out.write(',');
                out.write(csvEscape(rnAddress(r)));                                 out.write(',');
                out.write(csvEscape(r.city()));                                     out.write(',');
                out.write(csvEscape(r.county()));
                out.write("\r\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate registry CSV", e);
        }
        return baos.toByteArray();
    }

    @Transactional(readOnly = true)
    public byte[] generateRegistryPdf(RnRegistryView view, String q, String county, String municipality,
                                      Long typeId, boolean foreignOnly, String rb, String city,
                                      String street, String name, String lessor,
                                      Integer deadlineWithinDays) {
        List<RnSummaryDto> rows = fetchRegistryForExport(
                view, q, county, municipality, typeId, foreignOnly, rb, city, street, name, lessor,
                deadlineWithinDays);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 28, 28, 36, 28);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Paragraph title = new Paragraph("Registar registracijskih brojeva", FNT_TITLE);
            title.setSpacingAfter(4);
            doc.add(title);
            Paragraph dateP = new Paragraph("Generirano: " + LocalDate.now().format(DATE_FMT), FNT_DATE);
            dateP.setSpacingAfter(14);
            doc.add(dateP);

            float[] widths = {3f, 2f, 2f, 2f, 2f, 2f, 3.5f, 2.5f, 3f, 3f, 2.5f, 3f};
            PdfPTable table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            for (String h : RN_HEADERS) table.addCell(thCell(h));
            for (RnSummaryDto r : rows) {
                table.addCell(tdCell(nullToEmpty(r.rn())));
                table.addCell(tdCell(translateStatus(r.status() != null ? r.status().name() : null)));
                table.addCell(tdCell(formatDate(r.issueDate())));
                table.addCell(tdCell(formatDate(r.validFrom())));
                table.addCell(tdCell(formatDate(r.validTo())));
                table.addCell(tdCell(formatDate(r.suspensionDeadline())));
                table.addCell(tdCell(nullToEmpty(r.accommodationName())));
                table.addCell(tdCell(nullToEmpty(r.accommodationTypeName())));
                table.addCell(tdCell(rnLessorLabel(r)));
                table.addCell(tdCell(rnAddress(r)));
                table.addCell(tdCell(nullToEmpty(r.city())));
                table.addCell(tdCell(nullToEmpty(r.county())));
            }
            doc.add(table);
            doc.close();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate registry PDF", e);
        }
    }

    private static String rnLessorLabel(RnSummaryDto r) {
        if (r.lessorLegalEntityName() != null && !r.lessorLegalEntityName().isBlank())
            return r.lessorLegalEntityName();
        String parts = ((r.lessorFirstName() != null ? r.lessorFirstName() : "") + " "
                + (r.lessorLastName() != null ? r.lessorLastName() : "")).trim();
        return parts.isEmpty() ? "-" : parts;
    }

    private static String rnAddress(RnSummaryDto r) {
        String street = nullToEmpty(r.street());
        String number = nullToEmpty(r.streetNumber());
        return number.isEmpty() ? street : street + " " + number;
    }

    // ── Platform activities export (STR-3.2) ───────────────────────────────────

    private static final String[] PA_HEADERS = {
            "Registracijski broj", "Vlasnik", "Adresa", "Grad/naselje", "Županija", "Platforme",
            "Period od", "Period do", "Noćenja", "Gosti", "Status RB", "Prijavljeno"
    };

    @Transactional(readOnly = true)
    public byte[] generatePlatformActivitiesXlsx(PlatformActivityFilter filter) {
        List<PlatformActivityRowDto> rows = fetchForExport(filter);
        SXSSFWorkbook wb = new SXSSFWorkbook(SXSSF_WINDOW_ROWS);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Aktivnosti platformi");

            org.apache.poi.ss.usermodel.Font boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(boldFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < PA_HEADERS.length; i++) {
                var cell = headerRow.createCell(i);
                cell.setCellValue(PA_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (PlatformActivityRowDto row : rows) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue(nullToEmpty(row.rb()));
                r.createCell(1).setCellValue(nullToEmpty(row.ownerName()));
                r.createCell(2).setCellValue(nullToEmpty(row.address()));
                r.createCell(3).setCellValue(nullToEmpty(row.city()));
                r.createCell(4).setCellValue(nullToEmpty(row.countyName()));
                r.createCell(5).setCellValue(platforms(row));
                r.createCell(6).setCellValue(formatDate(row.periodFrom()));
                r.createCell(7).setCellValue(formatDate(row.periodTo()));
                r.createCell(8).setCellValue(row.nights());
                r.createCell(9).setCellValue(row.guestsTotal());
                r.createCell(10).setCellValue(translateActivityStatus(row.rnStatus()));
                r.createCell(11).setCellValue(formatReported(row));
            }

            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate platform activities Excel", e);
        } finally {
            // Closing alone leaves the spill files behind; dispose() is what deletes them.
            wb.dispose();
        }
    }

    /**
     * Writes straight into the byte buffer instead of assembling one giant String first — a
     * {@code StringBuilder} holds the whole export as UTF-16 and then {@code getBytes} allocates
     * the encoded copy alongside it, so the peak was roughly three times the file size.
     */
    @Transactional(readOnly = true)
    public byte[] generatePlatformActivitiesCsv(PlatformActivityFilter filter) {
        List<PlatformActivityRowDto> rows = fetchForExport(filter);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(CSV_INITIAL_BUFFER_BYTES);
        try (Writer out = new BufferedWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            out.write('﻿'); // BOM for Excel-compatible UTF-8
            out.write(String.join(",", PA_HEADERS));
            out.write("\r\n");
            for (PlatformActivityRowDto row : rows) {
                out.write(csvEscape(row.rb()));           out.write(',');
                out.write(csvEscape(row.ownerName()));    out.write(',');
                out.write(csvEscape(row.address()));      out.write(',');
                out.write(csvEscape(row.city()));         out.write(',');
                out.write(csvEscape(row.countyName()));   out.write(',');
                out.write(csvEscape(platforms(row)));     out.write(',');
                out.write(csvEscape(formatDate(row.periodFrom()))); out.write(',');
                out.write(csvEscape(formatDate(row.periodTo())));   out.write(',');
                out.write(csvEscape(row.nights()));       out.write(',');
                out.write(csvEscape(row.guestsTotal()));  out.write(',');
                out.write(csvEscape(translateActivityStatus(row.rnStatus()))); out.write(',');
                out.write(csvEscape(formatReported(row)));
                out.write("\r\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate platform activities CSV", e);
        }
        return baos.toByteArray();
    }

    /**
     * STR-3.2: shared row fetch for both exports. Refuses oversized result sets instead of
     * attempting them — an unfiltered export on a national-scale data set would otherwise spend
     * minutes building a workbook and exhaust the heap, and the request would hit a gateway
     * timeout long before finishing.
     */
    private List<PlatformActivityRowDto> fetchForExport(PlatformActivityFilter filter) {
        List<PlatformActivityRowDto> rows = platformActivityQuery.queryAll(filter, MAX_EXPORT_ROWS);
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw new BusinessException("error.export.too.many.rows");
        }
        return rows;
    }

    private static String platforms(PlatformActivityRowDto row) {
        if (row.platforms() == null) return "";
        return row.platforms().stream()
                .map(PlatformChipDto::name)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(", "));
    }

    private static String formatReported(PlatformActivityRowDto row) {
        return row.reportedAt() == null ? "" : REPORTED_FMT.format(row.reportedAt());
    }

    /** Row status arrives already mapped to the frontend lowercase form. */
    private static String translateActivityStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case "aktivan"          -> "Aktivan";
            case "pred_suspenzijom" -> "Pred suspenzijom";
            case "suspendiran"      -> "Suspendiran";
            case "povucen"          -> "Povučen";
            case "bez_rb"           -> "Bez RB";
            default                 -> status;
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String address(DetailRowProjection row) {
        String street = nullToEmpty(row.getStreet());
        String number = nullToEmpty(row.getStreetNumber());
        return number.isEmpty() ? street : street + " " + number;
    }

    private static String translateStatus(String status) {
        if (status == null) return "";
        return switch (status) {
            case "ACTIVE"              -> "Aktivan";
            case "SUSPENSION_PROPOSED" -> "Pred suspenzijom";
            case "SUSPENDED"           -> "Suspendiran";
            case "WITHDRAWN"           -> "Povučen";
            default                    -> status;
        };
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "" : date.format(ISO_DATE_FMT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String csvEscape(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    private static String csvEscape(Object v) {
        return csvEscape(v == null ? "" : v.toString());
    }
}
