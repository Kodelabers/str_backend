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
import com.str.backend.pdf.PdfFonts;
import com.str.backend.statistics.StatisticsRepository.DetailRowProjection;
import com.str.backend.statistics.dto.BpsoResponse;
import com.str.backend.statistics.dto.CountyBpsoDto;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class StatisticsExportService {

    private static final Color BLUE  = new Color(46, 116, 181);
    private static final Color WHITE = Color.WHITE;
    private static final Color BLACK = Color.BLACK;

    private static final Locale           HR           = Locale.forLanguageTag("hr");
    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("dd.MM.yyyy.");
    private static final DateTimeFormatter ISO_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final List<String> EXPORT_STATUSES =
            List.of(RnStatus.ACTIVE.name(), RnStatus.SUSPENDED.name(), RnStatus.WITHDRAWN.name());

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

    private final StatisticsService    statisticsService;
    private final StatisticsRepository statisticsRepository;

    public StatisticsExportService(StatisticsService statisticsService,
                                   StatisticsRepository statisticsRepository) {
        this.statisticsService    = statisticsService;
        this.statisticsRepository = statisticsRepository;
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    public byte[] generateBpsoPdf() {
        BpsoResponse data = statisticsService.bpso(null, null);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 36, 36, 40, 36);
            PdfWriter.getInstance(doc, baos);
            doc.open();

            Paragraph title = new Paragraph("BPSO – Registracijski brodovi u brojkama", FNT_TITLE);
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
            for (CountyBpsoDto row : data.counties()) {
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
            throw new RuntimeException("Failed to generate BPSO PDF", e);
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
    public byte[] generateBpsoCsv() {
        List<DetailRowProjection> rows = statisticsRepository.findDetailRows(EXPORT_STATUSES);
        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // BOM for Excel-compatible UTF-8
        sb.append("Registracijski broj,Naziv objekta,Adresa,Grad,Županija,")
          .append("Kategorija,Tip ponude,Status RB,Datum izdavanja,")
          .append("Vrijedi od,Vrijedi do,Max ležajeva,Max gostiju\r\n");
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
              .append(csvEscape(row.getMaxBeds())).append(',')
              .append(csvEscape(row.getMaxGuests())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── Excel ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public byte[] generateBpsoXlsx() {
        List<DetailRowProjection> rows = statisticsRepository.findDetailRows(EXPORT_STATUSES);
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("BPSO detaljna statistika");

            XSSFFont boldFont = wb.createFont();
            boldFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(boldFont);

            String[] headers = {
                    "Registracijski broj", "Naziv objekta", "Adresa", "Grad", "Županija",
                    "Kategorija", "Tip ponude", "Status RB", "Datum izdavanja",
                    "Vrijedi od", "Vrijedi do", "Max ležajeva", "Max gostiju"
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
                r.createCell(12).setCellValue(row.getMaxGuests() != null ? row.getMaxGuests() : 0);
            }

            wb.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate BPSO Excel", e);
        }
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
            case "ACTIVE"    -> "Aktivan";
            case "SUSPENDED" -> "Suspendiran";
            case "WITHDRAWN" -> "Povučen";
            default          -> status;
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
