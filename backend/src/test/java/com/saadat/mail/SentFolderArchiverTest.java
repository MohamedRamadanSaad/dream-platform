package com.saadat.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import org.junit.jupiter.api.Test;

class SentFolderArchiverTest {

    @Test
    void imapHostComesFromTheSmtpHostUnlessGiven() {
        assertThat(SentFolderArchiver.resolveHost("", "smtp.hostinger.com")).isEqualTo("imap.hostinger.com");
        assertThat(SentFolderArchiver.resolveHost(null, " smtp.example.org ")).isEqualTo("imap.example.org");
        assertThat(SentFolderArchiver.resolveHost("mail.example.org", "smtp.hostinger.com")).isEqualTo("mail.example.org");
        assertThat(SentFolderArchiver.resolveHost("", "mail.example.org")).isEqualTo("mail.example.org");
        assertThat(SentFolderArchiver.resolveHost("", "")).isEmpty();
    }

    @Test
    void signInEmailsAreNeverCopiedAndTheSwitchIsRespected() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getBool(eq(SettingKeys.MAIL_SAVE_TO_SENT), anyBoolean())).thenReturn(true);
        SentFolderArchiver archiver = new SentFolderArchiver(settings, "smtp.hostinger.com", "", 993,
                "support@example.com", "secret");

        assertThat(archiver.shouldArchive(MailTemplates.MAGIC_LINK)).isFalse();
        assertThat(archiver.shouldArchive("support-in-progress")).isTrue();

        when(settings.getBool(eq(SettingKeys.MAIL_SAVE_TO_SENT), anyBoolean())).thenReturn(false);
        assertThat(archiver.shouldArchive("support-in-progress")).isFalse();
    }

    @Test
    void nothingIsCopiedWithoutSmtpCredentials() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getBool(eq(SettingKeys.MAIL_SAVE_TO_SENT), anyBoolean())).thenReturn(true);
        SentFolderArchiver archiver = new SentFolderArchiver(settings, "", "", 993, "", "");

        assertThat(archiver.shouldArchive("support-closed")).isFalse();
    }
}
