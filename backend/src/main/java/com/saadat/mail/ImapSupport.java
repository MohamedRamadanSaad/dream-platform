package com.saadat.mail;

import java.util.Properties;

/**
 * Shared IMAP connection settings of the site mailbox (same account as SMTP: {@code spring.mail.username/password}).
 * Host: env {@code IMAP_HOST}, else the SMTP host with {@code smtp.} replaced by {@code imap.}
 * (smtp.hostinger.com → imap.hostinger.com); port: env {@code IMAP_PORT} (993, implicit TLS). Used by
 * {@link SentFolderArchiver} (APPEND to Sent) and {@link com.saadat.mail.inbound.InboxMessageReader} (read-only
 * INBOX search).
 */
public final class ImapSupport {

    /** Connect / read / write timeout of every IMAP call. */
    public static final int TIMEOUT_MILLIS = 15_000;

    private ImapSupport() {
    }

    /** IMAP host: explicit value, else the SMTP host with a leading {@code smtp.} turned into {@code imap.}. */
    public static String resolveHost(String imapHost, String smtpHost) {
        if (imapHost != null && !imapHost.isBlank()) {
            return imapHost.trim();
        }
        if (smtpHost == null || smtpHost.isBlank()) {
            return "";
        }
        String host = smtpHost.trim();
        return host.startsWith("smtp.") ? "imap." + host.substring("smtp.".length()) : host;
    }

    /** Session properties for {@code imaps} on {@code host:port} with the timeouts above. */
    public static Properties sessionProperties(String host, int port) {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.imaps.timeout", String.valueOf(TIMEOUT_MILLIS));
        props.put("mail.imaps.writetimeout", String.valueOf(TIMEOUT_MILLIS));
        return props;
    }
}
