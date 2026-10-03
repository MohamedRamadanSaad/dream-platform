package com.saadat.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** MIME → text and the "not configured" path; no IMAP server is needed. */
class InboxMessageReaderTest {

    private final Session session = Session.getInstance(new Properties());

    @Test
    void prefersThePlainTextPartAndSkipsAttachments() throws Exception {
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("السلام عليكم\r\nلم يصلني التفسير", StandardCharsets.UTF_8.name());
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>HTML version</p>", "text/html; charset=UTF-8");
        MimeBodyPart alternativePart = new MimeBodyPart();
        alternativePart.setContent(new MimeMultipart("alternative", plain, html));
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText("attachment text", StandardCharsets.UTF_8.name());
        attachment.setFileName("notes.txt");
        attachment.setDisposition(Part.ATTACHMENT);
        MimeMessage message = new MimeMessage(session);
        message.setContent(new MimeMultipart("mixed", attachment, alternativePart));
        message.saveChanges();

        assertThat(InboxMessageReader.extractText(message)).isEqualTo("السلام عليكم\nلم يصلني التفسير");
    }

    @Test
    void convertsTheHtmlPartWhenThereIsNoPlainText() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setContent("<div>Hello<br>World &amp; all</div><style>.x{}</style>", "text/html; charset=UTF-8");
        message.saveChanges();

        assertThat(InboxMessageReader.extractText(message)).isEqualTo("Hello\nWorld & all");
    }

    @Test
    void withoutAnAccountNothingIsRead() {
        InboxMessageReader reader = new InboxMessageReader("", "", 993, "", "");
        assertThat(reader.isConfigured()).isFalse();
        assertThat(reader.fetchText("<a@b>", "a@example.com", "Hi")).isEmpty();

        InboxMessageReader noUser = new InboxMessageReader("smtp.hostinger.com", "", 993, "", "");
        assertThat(noUser.isConfigured()).isFalse();
    }
}
