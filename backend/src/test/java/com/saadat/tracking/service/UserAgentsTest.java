package com.saadat.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Device;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Browser / system / device class of the devices list (docs/SESSIONS_PROFILE_CONTRACT.md §2). */
class UserAgentsTest {

    @ParameterizedTest(name = "{1} on {2} ({3})")
    @CsvSource(delimiter = '|', textBlock = """
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 | Chrome | Windows | DESKTOP
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.2792.79 | Edge | Windows | DESKTOP
            Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 OPR/114.0.0.0 | Opera | Windows | DESKTOP
            Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0 | Firefox | Windows | DESKTOP
            Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15 | Safari | macOS | DESKTOP
            Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 | Chrome | macOS | DESKTOP
            Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1 | Safari | iOS | MOBILE
            Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/129.0.6668.69 Mobile/15E148 Safari/604.1 | Chrome | iOS | MOBILE
            Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/131.0 Mobile/15E148 Safari/605.1.15 | Firefox | iOS | MOBILE
            Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) EdgiOS/129.0.2792.84 Version/17.0 Mobile/15E148 Safari/604.1 | Edge | iOS | MOBILE
            Mozilla/5.0 (iPad; CPU OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1 | Safari | iPadOS | TABLET
            Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36 | Chrome | Android | MOBILE
            Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0 Chrome/121.0.0.0 Mobile Safari/537.36 | Samsung Internet | Android | MOBILE
            Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 | Chrome | Android | TABLET
            Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0 | Firefox | Android | MOBILE
            Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0 | Firefox | Linux | DESKTOP
            Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 | Chrome | Linux | DESKTOP
            Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36 OPR/84.0.0.0 | Opera | Android | MOBILE
            curl/8.5.0 | Other | Other | DESKTOP
            """)
    void parsesBrowserSystemAndDeviceClass(String userAgent, String browser, String os, Device device) {
        assertThat(UserAgents.browser(userAgent)).isEqualTo(browser);
        assertThat(UserAgents.os(userAgent)).isEqualTo(os);
        assertThat(UserAgents.device(userAgent)).isEqualTo(device);
    }

    @Test
    void missingHeaderIsOtherOnADesktop() {
        assertThat(UserAgents.browser(null)).isEqualTo(UserAgents.OTHER);
        assertThat(UserAgents.os(null)).isEqualTo(UserAgents.OTHER);
        assertThat(UserAgents.device(null)).isEqualTo(Device.DESKTOP);
        assertThat(UserAgents.browser("  ")).isEqualTo(UserAgents.OTHER);
        assertThat(UserAgents.os("")).isEqualTo(UserAgents.OTHER);
    }
}
