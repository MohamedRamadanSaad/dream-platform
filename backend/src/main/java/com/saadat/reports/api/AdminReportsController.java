package com.saadat.reports.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.Downloads;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.reports.ReportService;
import com.saadat.reports.ReportViews.Download;
import com.saadat.reports.excel.DreamExcelExporter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Interpreter downloads: dream / user PDFs and the dreams Excel export (ROLE_INTERPRETER via SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class AdminReportsController {

    private final ReportService reportService;
    private final DreamExcelExporter excelExporter;

    @GetMapping(ApiPaths.Admin.DREAM_PDF)
    public ResponseEntity<byte[]> dreamPdf(@PathVariable UUID id,
                                           @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                                           String acceptLanguage) {
        Download pdf = reportService.adminDream(id, Locale.fromTag(acceptLanguage));
        return Downloads.pdf(pdf.filename(), pdf.bytes());
    }

    @GetMapping(ApiPaths.Admin.USER_PDF)
    public ResponseEntity<byte[]> userPdf(@PathVariable UUID id,
                                          @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                                          String acceptLanguage) {
        Download pdf = reportService.adminUser(id, Locale.fromTag(acceptLanguage));
        return Downloads.pdf(pdf.filename(), pdf.bytes());
    }

    @GetMapping(ApiPaths.Admin.DREAMS_EXPORT)
    public void export(@RequestParam(required = false) DreamStatus status,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) String country,
                       @RequestParam(required = false) Gender gender,
                       @RequestParam(required = false) String q,
                       @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
                       HttpServletResponse response) throws IOException {
        DreamExcelExporter.Filters filters = excelExporter.filters(status, from, to, country, gender, q);
        response.setContentType(Downloads.XLSX_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, Downloads.contentDisposition(excelExporter.filename()));
        excelExporter.write(filters, Locale.fromTag(acceptLanguage), response.getOutputStream());
    }
}
