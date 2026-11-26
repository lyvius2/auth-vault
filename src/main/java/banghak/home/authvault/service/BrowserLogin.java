package banghak.home.authvault.service;

import banghak.home.authvault.client.CdpClient;
import banghak.home.authvault.client.SiteRecipe;
import banghak.home.authvault.config.VaultProperties;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    private static final long ALREADY_LOGGED_IN_WAIT_MS = 2_000;
    private static final long FORM_WAIT_MS = 10_000;
    private static final long SUBMIT_ENABLE_WAIT_MS = 2_000;
    private static final long OUTCOME_WAIT_MS = 15_000;
    // loginUrl을 벗어난 뒤 successSelector/otpSelector가 그려질 때까지 더 기다려 주는 시간
    private static final long AFTER_NAVIGATION_GRACE_MS = 3_000;

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static void submit(CdpClient cdp, String text, String selector) throws Exception {
        if (!blank(text)) {
            cdp.clickByText(text);
        } else if (!blank(selector)) {
            cdp.click(selector);
        }
    }

    // 제출 버튼이 활성화될 때까지 기다린다. 끝까지 disabled면 true.
    static boolean submitStaysDisabled(CdpClient cdp, SiteRecipe r, long timeoutMs) throws Exception {
        if (blank(r.submitText()) && blank(r.submitSelector())) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            boolean disabled = !blank(r.submitText()) ? cdp.isDisabledByText(r.submitText()) : cdp.isDisabled(r.submitSelector());
            if (!disabled) {
                return false;
            }
            if (System.nanoTime() >= deadline) {
                return true;
            }
            Thread.sleep(CdpClient.POLL_MS);
        }
    }

    private static boolean leftLoginUrl(CdpClient cdp, SiteRecipe r) throws Exception {
        String href = cdp.evalString("location.href");
        return href != null && !href.startsWith(r.loginUrl());
    }

    // 판정 순서: successSelector → successUrlContains → (둘 다 없을 때만) loginUrl 이탈 여부
    static boolean succeeded(CdpClient cdp, SiteRecipe r) throws Exception {
        boolean hasSelector = !blank(r.successSelector()), hasUrl = !blank(r.successUrlContains());
        if (hasSelector && cdp.visible(r.successSelector())) {
            return true;
        }
        if (hasUrl) {
            String href = cdp.evalString("location.href");
            return href != null && href.contains(r.successUrlContains());
        }
        return !hasSelector && leftLoginUrl(cdp, r);
    }

    // 제출 후 결과가 정해질 때까지 기다린다: OTP 입력칸이 보이면 OTP_REQUIRED, 성공 판정이 서면 OK.
    // loginUrl을 벗어났는데도 잠시 뒤까지 둘 다 아니거나 timeoutMs가 지나면 FAILED.
    static Status awaitOutcome(CdpClient cdp, SiteRecipe r, long timeoutMs, boolean otpPossible) throws Exception {
        boolean otp = otpPossible && !blank(r.otpSelector());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long leftAt = -1;
        while (true) {
            if (otp && cdp.visible(r.otpSelector())) {
                return Status.OTP_REQUIRED;
            }
            if (succeeded(cdp, r)) {
                // URL만으로 성공을 판정한 경우 OTP 화면이 아직 덜 그려졌을 수 있다
                boolean otpMayFollow = otp && blank(r.successSelector());
                return otpMayFollow && cdp.waitVisible(r.otpSelector(), 1_000) ? Status.OTP_REQUIRED : Status.OK;
            }
            long now = System.nanoTime();
            if (leftAt < 0 && leftLoginUrl(cdp, r)) {
                leftAt = now;
            }
            boolean graceOver = leftAt >= 0 && now - leftAt >= TimeUnit.MILLISECONDS.toNanos(AFTER_NAVIGATION_GRACE_MS);
            if (now >= deadline || graceOver) {
                return Status.FAILED;
            }
            Thread.sleep(CdpClient.POLL_MS);
        }
    }

    // 실패 원인 파악용으로 현재 위치를 덧붙인다. 폼이 GET으로 제출되면 쿼리스트링에 입력값이 실릴 수 있어
    // 쿼리·프래그먼트는 버리고, 그래도 남은 비밀값은 가린다.
    static String failureDetail(CdpClient cdp, String reason, String... secrets) {
        String detail = reason;
        try {
            String[] page = cdp.evalString("location.origin + location.pathname + '\\n' + document.title").split("\n", 2);
            String title = page.length > 1 ? page[1] : "";
            if (title.length() > 80) {
                title = title.substring(0, 80) + "…";
            }
            detail = "%s (url=%s, title=%s)".formatted(reason, page[0], title);
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
        String[] secrets = {cred.id(), cred.password()};
        try {
            cdp.open();
            cdp.navigate(r.loginUrl());
            if (!blank(r.successSelector()) && cdp.waitVisible(r.successSelector(), ALREADY_LOGGED_IN_WAIT_MS)) {
                cdp.close(); return new Result(Status.ALREADY_LOGGED_IN, "session still valid");
            }
            cdp.waitForSelector(r.idSelector(), FORM_WAIT_MS);
            cdp.fill(r.idSelector(), cred.id());
            cdp.fill(r.passwordSelector(), cred.password());
            if (submitStaysDisabled(cdp, r, SUBMIT_ENABLE_WAIT_MS)) {
                // 합성 이벤트를 무시하는 폼: 브라우저 입력으로 다시 넣어 본다
                cdp.typeText(r.idSelector(), cred.id());
                cdp.typeText(r.passwordSelector(), cred.password());
                if (submitStaysDisabled(cdp, r, SUBMIT_ENABLE_WAIT_MS)) {
                    Result res = new Result(Status.FAILED, failureDetail(cdp, "submit stays disabled after fill", secrets));
                    cdp.close();
                    return res;
                }
            }
            submit(cdp, r.submitText(), r.submitSelector());
            Status status = awaitOutcome(cdp, r, OUTCOME_WAIT_MS, true);
            if (status == Status.OTP_REQUIRED) {
                awaitingOtp.put(user.toLowerCase(), cdp);
                return new Result(Status.OTP_REQUIRED, "site asks for a one-time code");
            }
            Result res = status == Status.OK ? new Result(Status.OK, "logged in")
                    : new Result(Status.FAILED, failureDetail(cdp, "login not confirmed after submit (wrong password, captcha, or layout change)", secrets));
            cdp.close();
            return res;
        } catch (Exception e) {
            Result res = new Result(Status.FAILED, failureDetail(cdp, describe(e), secrets));
            cdp.close();
            return res;
        }
    }

    public Result submitOtp(String user, SiteRecipe r, String code) {
        CdpClient cdp = awaitingOtp.remove(user.toLowerCase());
        try (cdp) {
            if (cdp == null) {
                return new Result(Status.FAILED, "no login waiting for OTP");
            }
            try {
                cdp.fill(r.otpSelector(), code);
                submit(cdp, r.otpSubmitText(), r.otpSubmitSelector());
                Thread.sleep(Math.max(r.waitMs(), 1500));
                return awaitOutcome(cdp, r, OUTCOME_WAIT_MS, false) == Status.OK ? new Result(Status.OK, "logged in")
                        : new Result(Status.FAILED, failureDetail(cdp, "OTP rejected or login not confirmed", code));
            } catch (Exception e) {
                return new Result(Status.FAILED, failureDetail(cdp, describe(e), code));
            }
        }
    }
}
