package com.saadat.mail.inbound;

import com.saadat.mail.ImapSupport;
import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.HeaderTerm;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the text of one incoming e-mail from the site mailbox's INBOX over IMAP (same account as SMTP, host from
 * {@link ImapSupport}). Strictly read-only: the folder is opened {@code READ_ONLY} (IMAP EXAMINE, bodies fetched with
 * PEEK), no flag is changed, nothing is moved or deleted.
 *
 * <p>The message is found by its Message-ID (IMAP {@code SEARCH HEADER Message-ID}); without one, the newest of the
 * last {@link #FALLBACK_SCAN} messages from the same address with the same subject. Text: the first
 * {@code text/plain} part, else the first {@code text/html} part converted to text; multiparts are walked
 * recursively, attachments and attached e-mails are skipped. Never throws; warnings never contain the text.
 */
@Slf4j
@Component
public class InboxMessageReader {

    static final String INBOX = "INBOX";
    static final int FALLBACK_SCAN = 50;
    private static final int MAX_DEPTH = 10;

    private final String imapHost;
    private final int imapPort;
    private final String username;
    private final String password;

    public InboxMessageReader(@Value("${spring.mail.host:}") String smtpHost,
                              @Value("${IMAP_HOST:}") String imapHost,
                              @Value("${IMAP_PORT:993}") int imapPort,
                              @Value("${spring.mail.username:}") String username,
                              @Value("${spring.mail.password:}") String password) {
        this.imapHost = ImapSupport.resolveHost(imapHost, smtpHost);
        this.imapPort = imapPort;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    /** Whether the mailbox can be read at all (host and account configured). */
    public boolean isConfigured() {
        return !imapHost.isEmpty() && !username.isEmpty();
    }

    /**
     * The cleaned text ({@link MailBodyText#clean}) of the message, empty when IMAP is not configured, the message is
     * not found, it has no readable text, or anything fails.
     */
    public Optional<String> fetchText(String messageId, String fromEmail, String subject) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        boolean hasId = messageId != null && !messageId.isBlank();
        boolean hasSender = fromEmail != null && !fromEmail.isBlank();
        if (!hasId && !hasSender) {
            return Optional.empty();
        }
        Properties props = ImapSupport.sessionProperties(imapHost, imapPort);
        props.put("mail.imaps.peek", "true");
        Store store = null;
        Folder folder = null;
        try {
            store = Session.getInstance(props).getStore("imaps");
            store.connect(imapHost, imapPort, username, password);
            folder = store.getFolder(INBOX);
            folder.open(Folder.READ_ONLY);
            Message message = hasId ? findByMessageId(folder, messageId.trim()) : null;
            if (message == null && hasSender) {
                message = findBySenderAndSubject(folder, fromEmail.trim(), subject);
            }
            if (message == null) {
                log.info("Support message not found in the mailbox (yet)");
                return Optional.empty();
            }
            return Optional.ofNullable(extractText(message));
        } catch (Exception e) {
            log.warn("Could not read the support message from the mailbox: {}", e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()));
            return Optional.empty();
        } finally {
            if (folder != null) {
                try {
                    if (folder.isOpen()) {
                        folder.close(false);
                    }
                } catch (Exception ignored) {
                    // closing a broken connection
                }
            }
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignored) {
                    // closing a broken connection
                }
            }
        }
    }

    private static Message findByMessageId(Folder folder, String messageId) throws MessagingException {
        Message[] found = folder.search(new HeaderTerm("Message-ID", messageId));
        if (found.length == 0) {
            String bare = messageId.replaceAll("^<|>$", "");
            if (!bare.equals(messageId) && !bare.isBlank()) {
                found = folder.search(new HeaderTerm("Message-ID", bare));
            }
        }
        return found.length == 0 ? null : found[found.length - 1];
    }

    private static Message findBySenderAndSubject(Folder folder, String fromEmail, String subject)
            throws MessagingException {
        int count = folder.getMessageCount();
        if (count <= 0) {
            return null;
        }
        int start = Math.max(1, count - FALLBACK_SCAN + 1);
        Message[] recent = folder.getMessages(start, count);
        String wantedSubject = subject == null ? "" : subject.trim();
        for (int i = recent.length - 1; i >= 0; i--) {
            Message m = recent[i];
            if (fromMatches(m.getFrom(), fromEmail)) {
                String s = m.getSubject() == null ? "" : m.getSubject().trim();
                if (s.equals(wantedSubject)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean fromMatches(Address[] from, String email) {
        if (from == null) {
            return false;
        }
        for (Address a : from) {
            if (a instanceof InternetAddress ia && ia.getAddress() != null
                    && ia.getAddress().trim().equalsIgnoreCase(email)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ MIME → text

    /** Text of a message or part (see the class comment), cleaned; null when there is none. */
    public static String extractText(Part part) throws MessagingException, IOException {
        String[] found = new String[2]; // [0] plain, [1] html
        walk(part, found, 0);
        if (found[0] != null) {
            String text = MailBodyText.clean(MailBodyText.looksLikeHtml(found[0])
                    && found[0].trim().startsWith("<") ? MailBodyText.htmlToText(found[0]) : found[0]);
            if (text != null) {
                return text;
            }
        }
        return found[1] == null ? null : MailBodyText.clean(MailBodyText.htmlToText(found[1]));
    }

    private static void walk(Part part, String[] found, int depth) throws MessagingException, IOException {
        if (depth > MAX_DEPTH || (found[0] != null && found[1] != null)) {
            return;
        }
        if (isAttachment(part)) {
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    walk(multipart.getBodyPart(i), found, depth + 1);
                }
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            return; // an attached e-mail is not what the person wrote
        }
        int size = part.getSize();
        if (size > MailBodyText.MAX_INPUT_CHARS * 4) {
            return;
        }
        if (part.isMimeType("text/plain") && found[0] == null) {
            found[0] = asText(part.getContent());
        } else if (part.isMimeType("text/html") && found[1] == null) {
            found[1] = asText(part.getContent());
        }
    }

    private static boolean isAttachment(Part part) throws MessagingException {
        String disposition = part.getDisposition();
        if (disposition != null && Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
            return true;
        }
        return part.getFileName() != null && !part.isMimeType("multipart/*");
    }

    private static String asText(Object content) throws IOException {
        if (content instanceof String s) {
            return s.length() > MailBodyText.MAX_INPUT_CHARS ? s.substring(0, MailBodyText.MAX_INPUT_CHARS) : s;
        }
        if (content instanceof InputStream in) {
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1 && total < MailBodyText.MAX_INPUT_CHARS) {
                    out.write(buffer, 0, read);
                    total += read;
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
