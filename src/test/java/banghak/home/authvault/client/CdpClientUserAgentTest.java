package banghak.home.authvault.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CdpClientUserAgentTest {
    private static final String HEADLESS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/142.0.0.0 Safari/537.36";
    private static final String REGULAR = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    @Test
    void headlessMarkerIsReplacedAndVersionKept() {
        assertThat(CdpClient.effectiveUserAgent(HEADLESS, null)).isEqualTo(REGULAR);
        assertThat(CdpClient.effectiveUserAgent(HEADLESS, " ")).isEqualTo(REGULAR);
    }

    @Test
    void regularChromeIsLeftAlone() {
        assertThat(CdpClient.effectiveUserAgent(REGULAR, null)).isNull();
        assertThat(CdpClient.effectiveUserAgent(null, "")).isNull();
    }

    @Test
    void configuredOverrideWins() {
        assertThat(CdpClient.effectiveUserAgent(HEADLESS, " Custom/1.0 ")).isEqualTo("Custom/1.0");
        assertThat(CdpClient.effectiveUserAgent(REGULAR, "Custom/1.0")).isEqualTo("Custom/1.0");
    }
}
