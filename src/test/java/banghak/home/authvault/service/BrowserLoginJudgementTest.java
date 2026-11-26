package banghak.home.authvault.service;

import banghak.home.authvault.client.CdpClient;
import banghak.home.authvault.client.SiteRecipe;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserLoginJudgementTest {
    private static final String LOGIN_URL = "https://example.com/login";

    private static SiteRecipe recipe(String successSelector, String successUrlContains) {
        return new SiteRecipe("site", LOGIN_URL, "#id", "#pw", "#go", successSelector, null, null, 0, null, null, successUrlContains);
    }

    private static CdpClient pageAt(String href, boolean markerExists) throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.evalString("location.href")).thenReturn(href);
        when(cdp.visible("#marker")).thenReturn(markerExists);
        return cdp;
    }

    @Test
    void selectorOnly() throws Exception {
        assertThat(BrowserLogin.succeeded(pageAt(LOGIN_URL, true), recipe("#marker", null))).isTrue();
        // 셀렉터가 지정됐으면 URL 이탈만으로는 성공이 아니다
        assertThat(BrowserLogin.succeeded(pageAt("https://example.com/home", false), recipe("#marker", null))).isFalse();
    }

    @Test
    void urlContainsOnly() throws Exception {
        assertThat(BrowserLogin.succeeded(pageAt("https://example.com/home?x=1", false), recipe(null, "/home"))).isTrue();
        assertThat(BrowserLogin.succeeded(pageAt("https://example.com/error", false), recipe(null, "/home"))).isFalse();
    }

    @Test
    void selectorThenUrlWhenBothGiven() throws Exception {
        assertThat(BrowserLogin.succeeded(pageAt(LOGIN_URL, true), recipe("#marker", "/home"))).isTrue();
        assertThat(BrowserLogin.succeeded(pageAt("https://example.com/home", false), recipe("#marker", "/home"))).isTrue();
        assertThat(BrowserLogin.succeeded(pageAt(LOGIN_URL, false), recipe("#marker", "/home"))).isFalse();
    }

    @Test
    void fallsBackToLeavingLoginUrlWhenNeitherGiven() throws Exception {
        assertThat(BrowserLogin.succeeded(pageAt("https://example.com/home", false), recipe(null, null))).isTrue();
        assertThat(BrowserLogin.succeeded(pageAt(LOGIN_URL + "?error=1", false), recipe(" ", ""))).isFalse();
        assertThat(BrowserLogin.succeeded(pageAt(null, false), recipe(null, null))).isFalse();
    }

    @Test
    void failureDetailHasUrlAndTitleButNeverSecrets() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.evalString(anyString())).thenReturn("https://example.com/login/my-id\n로그인 실패 s3cret");

        String detail = BrowserLogin.failureDetail(cdp, "element not found: #login-id", "my-id", "s3cret");

        assertThat(detail).isEqualTo("element not found: #login-id (url=https://example.com/login/***, title=로그인 실패 ***)");
    }

    @Test
    void failureDetailSurvivesBrowserErrors() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.evalString(anyString())).thenThrow(new IllegalStateException("gone"));

        assertThat(BrowserLogin.failureDetail(cdp, "login not confirmed", "pw")).isEqualTo("login not confirmed");
    }

    @Test
    void outcomeIsOtpWhenOtpFieldShowsUp() throws Exception {
        SiteRecipe r = new SiteRecipe("site", LOGIN_URL, "#id", "#pw", "#go", "#marker", "#otp", null, 0, null, null, null);
        CdpClient cdp = pageAt(LOGIN_URL, false);
        when(cdp.visible("#otp")).thenReturn(false, true);

        assertThat(BrowserLogin.awaitOutcome(cdp, r, 5_000, true)).isEqualTo(BrowserLogin.Status.OTP_REQUIRED);
        assertThat(BrowserLogin.awaitOutcome(cdp, r, 0, false)).isEqualTo(BrowserLogin.Status.FAILED);   // OTP 단계에서는 OTP 칸을 보지 않는다
    }

    @Test
    void outcomeIsOkOnceSuccessMarkerShowsUp() throws Exception {
        CdpClient cdp = pageAt(LOGIN_URL, false);
        when(cdp.visible("#marker")).thenReturn(false, false, true);

        assertThat(BrowserLogin.awaitOutcome(cdp, recipe("#marker", null), 5_000, true)).isEqualTo(BrowserLogin.Status.OK);
    }

    @Test
    void outcomeIsFailedWhenNothingHappensBeforeTimeout() throws Exception {
        assertThat(BrowserLogin.awaitOutcome(pageAt(LOGIN_URL, false), recipe("#marker", null), 300, true))
                .isEqualTo(BrowserLogin.Status.FAILED);
    }

    @Test
    void submitStaysDisabledWaitsForButtonToEnable() throws Exception {
        CdpClient cdp = mock(CdpClient.class);
        when(cdp.isDisabled("#go")).thenReturn(true, false);
        assertThat(BrowserLogin.submitStaysDisabled(cdp, recipe(null, null), 2_000)).isFalse();

        when(cdp.isDisabled("#go")).thenReturn(true);
        assertThat(BrowserLogin.submitStaysDisabled(cdp, recipe(null, null), 300)).isTrue();
    }
}
