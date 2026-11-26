package com.str.backend.admin;

import com.str.backend.common.SearchTokens;
import com.str.backend.common.Strings;
import com.str.backend.common.Zagreb;
import com.str.backend.exception.BusinessException;
import com.str.backend.objection.AdminObjectionRowDto;
import com.str.backend.objection.AdminObjectionStatsDto;
import com.str.backend.objection.ObjectionKind;
import com.str.backend.objection.ObjectionResolution;
import com.str.backend.objection.SuspensionObjectionRepository;
import com.str.backend.objection.SuspensionObjectionStatus;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Popis ulaznih pismena — očitovanja i prigovora — preko svih RB-ova, za interni portal (MINTS 17.11.,
 * točka 1). Službenik ovdje vidi što je stiglo i čeka ga; rješava se na detalju RB-a, kao i dosad
 * ({@code SuspensionObjectionAdminService}). Samo čitanje, pa bez audita.
 *
 * <p>Opseg je kao u registru: županijski službenik vidi samo podneske uz RB-ove na smještaju u svojoj
 * županiji ({@code CountyScopeService#accommodationCounties}), MINTS sve.
 */
@Service
public class AdminObjectionService {

    /** Dopušteni pragovi filtra „Stariji od X dana" (dogovor s MINTS-om); ostali su 400. */
    static final Set<Integer> OLDER_THAN_DAYS = Set.of(5, 15, 30, 45, 60);

    /** Kao izvoz registra ({@code StatisticsExportService#MAX_EXPORT_ROWS}). */
    static final int MAX_EXPORT_ROWS = 50_000;

    /** Širine stupaca izvoza u znakovima, redom kao zaglavlje. */
    private static final int[] COLUMN_WIDTHS = {30, 16, 22, 30, 28, 36, 14, 18, 22, 18};

    /** Sortira se samo po trenutku zaprimanja; zadano najstarije prvo — najdulje čeka. */
    static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.ASC, "submittedAt");

    /** Neprazna lista za {@code IN} kad opseg nije ograničen — ne koristi se (v. {@code RnService}). */
    private static final List<String> UNUSED_SCOPE_COUNTIES = List.of("");

    /**
     * Granice razdoblja kad filtra nema. Vremenski parametri se upitu uvijek šalju zadani — null {@code Instant}
     * Postgres ne može tipizirati ({@code SuspensionObjectionRepository#ADMIN_FILTERS}).
     */
    static final Instant NO_LOWER_BOUND = Instant.EPOCH;
    static final Instant NO_UPPER_BOUND = Instant.parse("9999-12-31T00:00:00Z");

    private final SuspensionObjectionRepository repository;
    private final Clock clock;

    public AdminObjectionService(SuspensionObjectionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Filtri popisa; {@code null} znači bez filtra, osim {@code resolution} (zadano neriješeni). {@code address}
     * traži ulicu, kućni broj i mjesto objekta ({@code SuspensionObjectionRepository#ADMIN_ADDRESS}).
     */
    public record Filter(String q, String name, String oib, String address, String rb, ObjectionKind kind,
                         ObjectionResolution resolution, Integer olderThanDays) {
    }

    /**
     * @param scopeCounties opseg službenika ({@code accommodation.county} RB-a); {@code null} = cijela RH
     */
    @Transactional(readOnly = true)
    public Page<AdminObjectionRowDto> search(Filter filter, List<String> scopeCounties, Pageable pageable) {
        return run(filter, scopeCounties, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                sort(pageable.getSort())));
    }

    @Transactional(readOnly = true)
    public AdminObjectionStatsDto stats(List<String> scopeCounties) {
        Instant now = clock.instant();
        Instant startOfToday = Zagreb.today(clock).atStartOfDay(Zagreb.ZONE).toInstant();
        String scopeMode = scopeMode(scopeCounties);
        Collection<String> counties = counties(scopeCounties);
        return new AdminObjectionStatsDto(
                repository.countUnresolvedForAdmin(scopeMode, counties, NO_LOWER_BOUND, NO_UPPER_BOUND),
                repository.countUnresolvedForAdmin(scopeMode, counties, startOfToday, NO_UPPER_BOUND),
                repository.countUnresolvedForAdmin(scopeMode, counties, NO_LOWER_BOUND,
                        now.minus(5, ChronoUnit.DAYS)),
                repository.countResolvedForAdmin(scopeMode, counties));
    }

    /** Excel s istim filtrima i redoslijedom kao popis, najviše {@link #MAX_EXPORT_ROWS} redaka. */
    @Transactional(readOnly = true)
    public byte[] exportXlsx(Filter filter, List<String> scopeCounties, Sort sort) {
        List<AdminObjectionRowDto> rows = run(filter, scopeCounties,
                PageRequest.of(0, MAX_EXPORT_ROWS + 1, sort(sort))).getContent();
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw new BusinessException("error.export.too.many.rows");
        }
        DateTimeFormatter dateTimeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm").withZone(Zagreb.ZONE);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Očitovanja i prigovori");
            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);

            String[] headers = {"Ime i prezime / naziv", "OIB / porezni broj", "Registracijski broj",
                    "Naziv objekta", "Županija", "Vrsta pismena", "Postupak", "Datum zaprimanja", "Status",
                    "Datum riješenosti"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                var cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                // Fiksna širina, ne autoSizeColumn: taj mjeri tekst fontovima JVM-a (kontejner ih ne mora imati)
                // i prolazi svim redcima — uz 50.000 redaka znatno usporava izvoz.
                sheet.setColumnWidth(i, COLUMN_WIDTHS[i] * 256);
            }
            int rowIdx = 1;
            for (AdminObjectionRowDto row : rows) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue(nullToEmpty(row.lessorName()));
                r.createCell(1).setCellValue(nullToEmpty(row.lessorOib() != null ? row.lessorOib()
                        : row.lessorTaxNumber()));
                r.createCell(2).setCellValue(row.rn());
                r.createCell(3).setCellValue(nullToEmpty(row.accommodationName()));
                r.createCell(4).setCellValue(nullToEmpty(row.county()));
                r.createCell(5).setCellValue(kindLabel(row.kind()));
                r.createCell(6).setCellValue(row.countyProcedure() ? "Županija" : "Ministarstvo");
                r.createCell(7).setCellValue(row.submittedAt() != null ? dateTimeFmt.format(row.submittedAt()) : "");
                r.createCell(8).setCellValue(row.resolved() ? resolvedLabel(row.status()) : "Neriješeno");
                r.createCell(9).setCellValue(row.resolved() && row.resolvedAt() != null
                        ? dateTimeFmt.format(row.resolvedAt()) : "");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate objections Excel export", e);
        }
    }

    private Page<AdminObjectionRowDto> run(Filter filter, List<String> scopeCounties, Pageable pageable) {
        String[] t = SearchTokens.slots(filter.q());
        ObjectionResolution resolution = filter.resolution() != null ? filter.resolution()
                : ObjectionResolution.UNRESOLVED;
        return repository.searchForAdmin(
                ObjectionKind.rnStatuses(filter.kind()),
                scopeMode(scopeCounties), counties(scopeCounties),
                resolution == ObjectionResolution.ALL ? null : resolution.name(),
                submittedBefore(filter.olderThanDays()),
                Strings.blankToNull(filter.rb()), Strings.blankToNull(filter.name()),
                Strings.blankToNull(filter.oib()),
                Strings.addressSearch(filter.address()),
                t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9],
                pageable);
    }

    /** „Stariji od X dana": predan prije točno X dana od sada, kao kartica „Starije od 5 dana". */
    private Instant submittedBefore(Integer olderThanDays) {
        if (olderThanDays == null) {
            return NO_UPPER_BOUND;
        }
        if (!OLDER_THAN_DAYS.contains(olderThanDays)) {
            throw new BusinessException("error.objections.older.than.days");
        }
        return clock.instant().minus(olderThanDays, ChronoUnit.DAYS);
    }

    /**
     * Samo smjer sortiranja po {@code submittedAt}; svako drugo svojstvo zamjenjuje zadano. Id podneska je
     * drugi ključ, da podnesci istog trenutka ne preskaču ni ponavljaju između stranica, a izvoz ima isti red.
     */
    private static Sort sort(Sort requested) {
        Sort.Order order = requested == null ? null : requested.getOrderFor("submittedAt");
        return (order != null ? Sort.by(order) : DEFAULT_SORT).and(Sort.by("objectionId"));
    }

    private static String scopeMode(List<String> scopeCounties) {
        return scopeCounties == null ? null : "ON";
    }

    private static Collection<String> counties(List<String> scopeCounties) {
        return scopeCounties == null || scopeCounties.isEmpty() ? UNUSED_SCOPE_COUNTIES : scopeCounties;
    }

    private static String kindLabel(ObjectionKind kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case OCITOVANJE_SUSPENZIJE -> "Očitovanje na prijedlog suspenzije";
            case PRIGOVOR_SUSPENZIJE -> "Prigovor na suspenziju";
            case OCITOVANJE_POVLACENJA -> "Očitovanje na prijedlog povlačenja";
            case PRIGOVOR_POVLACENJA -> "Prigovor na povlačenje";
        };
    }

    private static String resolvedLabel(SuspensionObjectionStatus status) {
        return switch (status) {
            case ACCEPTED -> "Riješeno (uvaženo)";
            case REJECTED -> "Riješeno (neuvaženo)";
            case CLOSED -> "Riješeno (zatvoreno)";
            case SUBMITTED -> "Riješeno";
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
