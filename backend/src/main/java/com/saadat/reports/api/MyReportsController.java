package com.saadat.reports.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.Downloads;
import com.saadat.common.domain.Locale;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.reports.ReportService;
import com.saadat.reports.ReportViews.Download;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dream owner's PDF downloads (no e-mail, no payment references). Language: Accept-Language when sent, else
 * the user's own locale.
 */
@RestController
@RequiredArgsConstructor
public class MyReportsController {

    private final ReportService reportService;

    @GetMapping(ApiPaths.Dreams.PDF)
    public ResponseEntity<byte[]> dreamPdf(@PathVariable UUID id,
                                           @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                                           String acceptLanguage) {
        Download pdf = reportService.myDream(AuthPrincipal.current().userId(), id, requested(acceptLanguage));
        return Downloads.pdf(pdf.filename(), pdf.bytes());
    }

    @GetMapping(ApiPaths.Me.DREAMS_PDF)
    public ResponseEntity<byte[]> myDreamsPdf(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                                              String acceptLanguage) {
        Download pdf = reportService.myDreams(AuthPrincipal.current().userId(), requested(acceptLanguage));
        return Downloads.pdf(pdf.filename(), pdf.bytes());
    }

    private static Locale requested(String acceptLanguage) {
        return acceptLanguage == null || acceptLanguage.isBlank() ? null : Locale.fromTag(acceptLanguage);
    }
}
