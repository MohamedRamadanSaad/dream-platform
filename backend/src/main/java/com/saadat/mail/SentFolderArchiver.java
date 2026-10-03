package com.saadat.mail;

import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * SMTP does not keep a copy of what it sends, so the mailbox's Sent folder would stay empty. After a successful
 * delivery {@link MailService} hands the exact same {@link MimeMessage} to this archiver, which APPENDs it over IMAP
 * (same account as SMTP) to {@code mail.sent_folder} (Hostinger: {@code INBOX.Sent}), marked as read.
 *
 * <p>Switch: BOOL setting {@code mail.save_to_sent}. IMAP host: env {@code IMAP_HOST}, else the SMTP host with
 * {@code smtp.} replaced by {@code imap.} (smtp.hostinger.com → imap.hostinger.com). Runs on the async pool and
 * never throws: a failed copy is only logged and never affects the delivery result.
 */
@Slf4j
@Component
public class SentFolderArchiver {

    static final String DEFAULT_FOLDER = "INBOX.Sent";

    private final SettingsService settings;
    private final String imapHost;
    private final int imapPort;
    private final String username;
    private final String password;

    public SentFolderArchiver(SettingsService settings,
                              @Value("${spring.mail.host:}") String smtpHost,
                              @Value("${IMAP_HOST:}") String imapHost,
                              @Value("${IMAP_PORT:993}") int imapPort,
                              @Value("${spring.mail.username:}") String username,
                              @Value("${spring.mail.password:}") String password) {
        this.settings = settings;
        this.imapHost = resolveHost(imapHost, smtpHost);
        this.imapPort = imapPort;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
    }

    /** IMAP host: explicit value, else the SMTP host with a leading {@code smtp.} turned into {@code imap.}. */
    static String resolveHost(String imapHost, String smtpHost) {
        return ImapSupport.resolveHost(imapHost, smtpHost);
    }

    /** Whether copies are wanted and possible for this template (sign-in e-mails are never copied). */
    public boolean shouldArchive(String template) {
        if (MailTemplates.MAGIC_LINK.equals(template)) {
            return false;
        }
        if (imapHost.isEmpty() || username.isEmpty()) {
            return false;
        }
        return settings.getBool(SettingKeys.MAIL_SAVE_TO_SENT, true);
    }

    /** Appends the delivered message to the Sent folder. Never throws. */
    @Async
    public void archive(MimeMessage message, String template) {
        String folderName = settings.getString(SettingKeys.MAIL_SENT_FOLDER, DEFAULT_FOLDER);
        if (folderName == null || folderName.isBlank()) {
            folderName = DEFAULT_FOLDER;
        }
        Properties props = ImapSupport.sessionProperties(imapHost, imapPort);
        Store store = null;
        try {
            store = Session.getInstance(props).getStore("imaps");
            store.connect(imapHost, imapPort, username, password);
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                folder.create(Folder.HOLDS_MESSAGES);
            }
            message.setFlag(Flags.Flag.SEEN, true);
            folder.appendMessages(new Message[] {message});
            log.debug("Copied e-mail '{}' to {}", template, folderName);
        } catch (Exception e) {
            log.warn("Could not copy e-mail '{}' to the Sent folder {}: {}", template, folderName, e.getMessage());
        } finally {
            if (store != null) {
                try {
                    store.close();
                } catch (Exception ignored) {
                    // closing a broken connection
                }
            }
        }
    }
}
