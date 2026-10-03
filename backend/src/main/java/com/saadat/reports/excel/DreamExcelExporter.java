package com.saadat.reports.excel;

import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.ValidationException;
import com.saadat.common.util.Ages;
import com.saadat.mail.MessageText;
import com.saadat.settings.BusinessZone;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /admin/dreams/export (contract §4): one row per non-draft dream matching the filters, streamed from the
 * database (cursor, fetch size) into a streaming workbook (SXSSF) — never all rows in memory. Header language by
 * locale, frozen header, auto-filter, wrapped long texts, RTL sheet for Arabic, real date cells (business time
 * zone). Row cap: setting {@code reports.excel_max_rows}. Newest submissions first.
 */
@Component
public class DreamExcelExporter {

    /** Rows kept in memory by SXSSF before flushing to a temp file. */
    static final int WINDOW = 100;
    static final int FETCH_SIZE = 500;
    /** Excel's maximum characters per cell. */
    static final int CELL_MAX = 32_767;
    static final String DATE_FORMAT = "yyyy-mm-dd hh:mm";
    static final String FILE_PREFIX = "dreams-";
    static final String FILE_SUFFIX = ".xlsx";
    static final String HEADER_KEY = "excel.dreams.col.";
    static final String SHEET_KEY = "excel.dreams.sheet";
    static final String STATUS_KEY = "report.status.";
    static final String GENDER_KEY = "report.gender.";
    /** Column ids (header keys excel.dreams.col.<id>) and widths in characters. */
    static final String[] COLUMNS = {"id", "submittedAt", "status", "expectedBy", "interpretedAt", "userName",
            "email", "gender", "age", "country", "text", "interpretation", "messages"};
    static final int[] WIDTHS = {38, 18, 18, 18, 18, 24, 30, 10, 8, 18, 60, 60, 12};
    private static final byte[] NIGHT = {0x0A, 0x11, 0x28};
    private static final byte[] GOLD = {(byte) 0xD4, (byte) 0xAF, 0x37};
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");
    private static final String SELECT = """
            select d.id, d.submitted_at, d.status, d.expected_by, d.interpreted_at,
                   u.name, u.email, d.gender, u.birth_date, u.country_code, c.name_ar, c.name_en,
                   d.text, i.text as interpretation,
                   (select count(*) from dream_messages m where m.dream_id = d.id) as messages
              from dreams d
              join users u on u.id = d.user_id
              left join interpretations i on i.dream_id = d.id
              left join countries c on c.code = u.country_code
             where not d.deleted and d.status <> 'DRAFT'
            """;

    /** Validated filters; null = no filter. {@code from}/{@code to} are submitted_at days (business zone). */
    public record Filters(DreamStatus status, LocalDate from, LocalDate to, String country, Gender gender,
                          String q) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final SettingsService settings;
    private final BusinessZone businessZone;
    private final MessageText messageText;
    private final Clock clock;

    public DreamExcelExporter(DataSource dataSource, SettingsService settings, BusinessZone businessZone,
                              MessageText messageText, Clock clock) {
        JdbcTemplate streaming = new JdbcTemplate(dataSource);
        streaming.setFetchSize(FETCH_SIZE);
        this.jdbc = new NamedParameterJdbcTemplate(streaming);
        this.settings = settings;
        this.businessZone = businessZone;
        this.messageText = messageText;
        this.clock = clock;
    }

    /** Normalizes and validates the query parameters (422 on bad values). */
    public Filters filters(DreamStatus status, LocalDate from, LocalDate to, String country, Gender gender,
                           String q) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from must not be after to", "INVALID_RANGE");
        }
        String cc = country == null || country.isBlank() ? null : country.trim().toUpperCase(java.util.Locale.ROOT);
        if (cc != null && !COUNTRY_CODE.matcher(cc).matches()) {
            throw new ValidationException("country must be an ISO 3166 alpha-2 code", "INVALID_COUNTRY");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        return new Filters(status, from, to, cc, gender, query);
    }

    /** {@code dreams-YYYY-MM-DD.xlsx} (today in the business time zone). */
    public String filename() {
        return FILE_PREFIX + LocalDate.now(clock.withZone(businessZone.zone())) + FILE_SUFFIX;
    }

    @Transactional(readOnly = true)
    public void write(Filters filters, Locale locale, OutputStream out) throws IOException {
        Locale loc = locale == null ? Locale.AR : locale;
        ZoneId zone = businessZone.zone();
        StringBuilder sql = new StringBuilder(SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filters.status() != null) {
            sql.append(" and d.status = :status");
            params.addValue("status", filters.status().name());
        }
        if (filters.from() != null) {
            sql.append(" and d.submitted_at >= :from");
            params.addValue("from", startOf(filters.from(), zone));
        }
        if (filters.to() != null) {
            sql.append(" and d.submitted_at < :to");
            params.addValue("to", startOf(filters.to().plusDays(1), zone));
        }
        if (filters.country() != null) {
            sql.append(" and u.country_code = :country");
            params.addValue("country", filters.country());
        }
        if (filters.gender() != null) {
            sql.append(" and d.gender = :gender");
            params.addValue("gender", filters.gender().name());
        }
        if (filters.q() != null) {
            sql.append(" and (u.name ilike :q or u.email ilike :q or d.text ilike :q)");
            params.addValue("q", "%" + escapeLike(filters.q()) + "%");
        }
        sql.append(" order by d.submitted_at desc nulls last, d.id limit :limit");
        params.addValue("limit", settings.getInt(SettingKeys.REPORTS_EXCEL_MAX_ROWS));

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) { // close() also deletes the temp files
            workbook.setCompressTempFiles(true);
            Styles styles = styles(workbook);
            SXSSFSheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(text(loc, SHEET_KEY)));
            sheet.setRightToLeft(loc == Locale.AR);
            Row header = sheet.createRow(0);
            for (int i = 0; i < COLUMNS.length; i++) {
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
                Cell cell = header.createCell(i);
                cell.setCellValue(text(loc, HEADER_KEY + COLUMNS[i]));
                cell.setCellStyle(styles.header());
            }
            sheet.createFreezePane(0, 1);

            Labels labels = labels(loc);
            int[] next = {1};
            RowCallbackHandler writer = rs -> writeRow(sheet.createRow(next[0]++), rs, styles, labels, loc, zone);
            jdbc.query(sql.toString(), params, writer);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(next[0] - 1, 0), 0, COLUMNS.length - 1));
            workbook.write(out);
        }
    }

    // ------------------------------------------------------------------ rows

    private void writeRow(Row row, ResultSet rs, Styles styles, Labels labels, Locale locale, ZoneId zone)
            throws SQLException {
        int c = 0;
        text(row, c++, rs.getString("id"), styles.text());
        date(row, c++, rs.getObject("submitted_at", OffsetDateTime.class), zone, styles.date());
        text(row, c++, labels.status().get(rs.getString("status")), styles.text());
        date(row, c++, rs.getObject("expected_by", OffsetDateTime.class), zone, styles.date());
        date(row, c++, rs.getObject("interpreted_at", OffsetDateTime.class), zone, styles.date());
        text(row, c++, rs.getString("name"), styles.text());
        text(row, c++, rs.getString("email"), styles.text());
        String gender = rs.getString("gender");
        text(row, c++, gender == null ? null : labels.gender().get(gender), styles.text());
        Integer age = Ages.of(rs.getObject("birth_date", LocalDate.class), clock);
        if (age != null) {
            Cell cell = row.createCell(c);
            cell.setCellValue(age.doubleValue());
            cell.setCellStyle(styles.text());
        }
        c++;
        String countryCode = rs.getString("country_code");
        String countryName = locale == Locale.EN ? rs.getString("name_en") : rs.getString("name_ar");
        text(row, c++, countryName != null ? countryName : (countryCode == null ? null : countryCode.trim()),
                styles.text());
        text(row, c++, rs.getString("text"), styles.wrap());
        text(row, c++, rs.getString("interpretation"), styles.wrap());
        Cell messages = row.createCell(c);
        messages.setCellValue((double) rs.getLong("messages"));
        messages.setCellStyle(styles.text());
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        if (value != null && !value.isEmpty()) {
            cell.setCellValue(value.length() > CELL_MAX ? value.substring(0, CELL_MAX) : value);
        }
    }

    private static void date(Row row, int column, OffsetDateTime value, ZoneId zone, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellStyle(style);
        if (value != null) {
            cell.setCellValue(value.atZoneSameInstant(zone).toLocalDateTime());
        }
    }

    // ------------------------------------------------------------------ helpers

    private Labels labels(Locale locale) {
        Map<String, String> status = new java.util.HashMap<>();
        for (DreamStatus s : DreamStatus.values()) {
            status.put(s.name(), text(locale, STATUS_KEY + s.name()));
        }
        Map<String, String> gender = new java.util.HashMap<>();
        for (Gender g : Gender.values()) {
            gender.put(g.name(), text(locale, GENDER_KEY + g.name()));
        }
        return new Labels(Map.copyOf(status), Map.copyOf(gender));
    }

    private Styles styles(SXSSFWorkbook workbook) {
        XSSFFont headerFont = (XSSFFont) workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(new XSSFColor(GOLD, null));
        XSSFCellStyle header = (XSSFCellStyle) workbook.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(new XSSFColor(NIGHT, null));
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle date = workbook.createCellStyle();
        date.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(DATE_FORMAT));
        date.setVerticalAlignment(VerticalAlignment.TOP);

        CellStyle wrap = workbook.createCellStyle();
        wrap.setWrapText(true);
        wrap.setVerticalAlignment(VerticalAlignment.TOP);

        CellStyle text = workbook.createCellStyle();
        text.setVerticalAlignment(VerticalAlignment.TOP);
        return new Styles(header, date, wrap, text);
    }

    private String text(Locale locale, String key) {
        return messageText.get(key, locale, Map.of());
    }

    private static OffsetDateTime startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().atOffset(ZoneOffset.UTC);
    }

    /** Escapes LIKE wildcards (backslash is PostgreSQL's default LIKE escape). */
    static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Styles(CellStyle header, CellStyle date, CellStyle wrap, CellStyle text) {
    }

    private record Labels(Map<String, String> status, Map<String, String> gender) {
    }
}
