package com.saadat.common.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Binary download responses ({@code Content-Disposition: attachment} with an RFC 5987 UTF-8 file name). */
public final class Downloads {

    public static final String XLSX_VALUE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final MediaType XLSX = MediaType.parseMediaType(XLSX_VALUE);

    private Downloads() {
    }

    /** {@code attachment; filename="<ascii>"; filename*=UTF-8''<percent-encoded>} */
    public static String contentDisposition(String filename) {
        String ascii = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    public static ResponseEntity<byte[]> pdf(String filename, byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(filename))
                .contentLength(body.length)
                .body(body);
    }
}
