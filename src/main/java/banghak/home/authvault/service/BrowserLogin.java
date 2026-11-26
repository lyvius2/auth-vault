package banghak.home.authvault.service;

import banghak.home.authvault.client.CdpClient;
import banghak.home.authvault.client.SiteRecipe;
import banghak.home.authvault.config.VaultProperties;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BrowserLogin {
    public enum Status { OK, OTP_REQUIRED, FAILED, ALREADY_LOGGED_IN }
    public record Result(Status status, String detail) {}

    private final VaultProperties props;
    private final Map<String, CdpClient> awaitingOtp = new ConcurrentHashMap<>();

    BrowserLogin(VaultProperties props) { this.props = props; }

    private int portFor(String user) {
        Integer p = props.profiles().get(user.toLowerCase());
        if (p == null) {
            throw new IllegalArgumentException("unknown user profile: " + user + " (configured: " + props.profiles().keySet() + ")");
        }
        return p;
    }

    // getMessage()가 null인 예외(연결 거부, 타임아웃 등)도 있다. Map.of는 null 값을 받지 못한다.
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static void submit(CdpClient cdp, String text, String selector) throws Exception {
        if (!blank(text)) {
            cdp.clickByText(text);
        } else if (!blank(selector)) {
            cdp.click(selector);
        }
    }

    // 판정 순서: successSelector → successUrlContains → (둘 다 없을 때만) loginUrl 이탈 여부
    static boolean succeeded(CdpClient cdp, SiteRecipe r) throws Exception {
        boolean hasSelector = !blank(r.successSelector()), hasUrl = !blank(r.successUrlContains());
        if (hasSelector && cdp.exists(r.successSelector())) {
            return true;
        }
        String href = cdp.evalString("location.href");
        if (hasUrl) {
            return href != null && href.contains(r.successUrlContains());
        }
        return !hasSelector && href != null && !href.startsWith(r.loginUrl());
    }

    // 실패 원인 파악용으로 현재 위치만 덧붙인다. 폼이 GET으로 제출되면 쿼리스트링에 입력값이 실릴 수 있어
    // 쿼리·프래그먼트는 버리고, 그래도 남은 비밀값은 가린다.
    static String failureDetail(CdpClient cdp, String reason, String... secrets) {
        String detail = reason;
        try {
            String url = cdp.evalString("location.origin + location.pathname");
            String title = cdp.evalString("document.title");
            if (title != null && title.length() > 80) {
                title = title.substring(0, 80) + "…";
            }
            detail = "%s; url=%s title=%s".formatted(reason, url, title);
        } catch (Exception ignored) {}
        return redact(detail, secrets);
    }

    static String redact(String s, String... secrets) {
        for (String secret : secrets) {
            if (!blank(secret)) {
                s = s.replace(secret, "***");
            }
        }
        return s;
    }

    public Result login(String user, SiteRecipe r, CredentialStore.Credential cred) {
        CdpClient cdp = new CdpClient(props.cdpHost(), portFor(user));
        try {
            cdp.open();
            cdp.navigate(r.loginUrl());
            if (r.successSelector() != null && cdp.exists(r.successSelector())) {
                cdp.close(); return new Result(Status.ALREADY_LOGGED_IN, "session still valid");
            }
            cdp.fill(r.idSelector(), cred.id());
            cdp.fill(r.passwordSelector(), cred.password());
            submit(cdp, r.submitText(), r.submitSelector());
            Thread.sleep(Math.max(r.waitMs(), 1500));
            if (r.otpSelector() != null && cdp.exists(r.otpSelector())) {
                awaitingOtp.put(user.toLowerCase(), cdp);
                return new Result(Status.OTP_REQUIRED, "site asks for a one-time code");
            }
            Result res = succeeded(cdp, r) ? new Result(Status.OK, "logged in")
                    : new Result(Status.FAILED, failureDetail(cdp, "login not confirmed after submit (wrong password, captcha, or layout change)", cred.id(), cred.password()));
            cdp.close();
            return res;
        } catch (Exception e) {
            cdp.close();
            return new Result(Status.FAILED, redact(describe(e), cred.id(), cred.password()));
        }
    }

    public Result submitOtp(String user, SiteRecipe r, String code) {
        CdpClient cdp = awaitingOtp.remove(user.toLowerCase());
        try (cdp) {
            if (cdp == null) {
                return new Result(Status.FAILED, "no login waiting for OTP");
            }
            cdp.fill(r.otpSelector(), code);
            submit(cdp, r.otpSubmitText(), r.otpSubmitSelector());
            Thread.sleep(Math.max(r.waitMs(), 1500));
            return succeeded(cdp, r) ? new Result(Status.OK, "logged in")
                    : new Result(Status.FAILED, failureDetail(cdp, "OTP rejected or login not confirmed", code));
        } catch (Exception e) {
            return new Result(Status.FAILED, redact(describe(e), code));
        }
    }
}
