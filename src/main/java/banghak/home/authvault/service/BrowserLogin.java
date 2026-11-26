package banghak.home.authvault.service;

import banghak.home.authvault.client.CdpClient;
import banghak.home.authvault.client.SiteRecipe;
import banghak.home.authvault.config.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class BrowserLogin {
    public enum Status { OK, OTP_REQUIRED, FAILED, ALREADY_LOGGED_IN }
    public record Result(Status status, String detail) {}
    private static final Logger log = LoggerFactory.getLogger(BrowserLogin.class);

    static final int DEBUG_KEEP = 20;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final VaultProperties props;
    private final Path debugDir;
    private final Map<String, CdpClient> awaitingOtp = new ConcurrentHashMap<>();

    BrowserLogin(VaultProperties props) {
        this.props = props;
        this.debugDir = Path.of(props.dataFile()).toAbsolutePath().resolveSibling("debug");     // /data/debug
    }

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

    // <site>-<user>-<yyyyMMdd-HHmmss>.png 로 저장하고 최근 DEBUG_KEEP개만 남긴다. site/user는 LLM이 넘긴 값이라 파일명에 쓸 글자만 남긴다.
    static synchronized String saveScreenshot(Path dir, String site, String user, byte[] png) throws IOException {
        Files.createDirectories(dir);
        String name = "%s-%s-%s.png".formatted(safe(site), safe(user), LocalDateTime.now().format(STAMP));
        Files.write(dir.resolve(name), png);
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> shots = files.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).thenComparing(Comparator.naturalOrder()))
                    .toList();
            for (Path old : shots.subList(0, Math.max(0, shots.size() - DEBUG_KEEP))) {
                Files.deleteIfExists(old);
            }
        }
        return name;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
    }

    // FAILED 직전에 진단 정보를 모은다: URL·title, 제출 버튼 상태, 화면의 오류 문구, 스크린샷 파일명.
    private Result fail(CdpClient cdp, SiteRecipe r, String user, String reason, String submitState, Map<String, String> selectors, String... secrets) {
        String base = failureDetail(cdp, reason, secrets);
        StringBuilder detail = new StringBuilder(base);
        if (submitState != null) {
            detail.append("; submit=").append(submitState);
        }
        if (base.contains("(url=")) {       // 페이지에 닿지 않으면(접속 실패 등) 더 물어봐야 타임아웃만 쌓인다
            selectors.forEach((name, selector) -> {
                try {
                    if (!blank(selector)) {
                        detail.append("; ").append(name).append(' ').append(cdp.matchCounts(selector));
                    }
                } catch (Exception ignored) {}
            });
            try {
                String hints = cdp.errorHints();
                if (!hints.isEmpty()) {
                    detail.append("; page says: ").append(redact(hints, secrets));
                }
            } catch (Exception ignored) {}
            try {
                detail.append("; screenshot=").append(saveScreenshot(debugDir, r.site(), user, cdp.screenshotPng()));
            } catch (Exception e) {
                log.warn("debug screenshot failed: {}", e.getClass().getSimpleName());
            }
        }
        return new Result(Status.FAILED, detail.toString());
    }

    private static Map<String, String> otpSelectors(SiteRecipe r) {
        return blank(r.otpSelector()) ? Map.of() : Map.of("otpSelector", r.otpSelector());
    }

    // 값을 넣은 뒤 길이가 맞는지 보고, 안 맞으면(프레임워크가 값을 되돌린 경우 등) 브라우저 입력으로 다시 넣는다.
    private static void fillChecked(CdpClient cdp, String selector, String value) throws Exception {
        cdp.fill(selector, value);
        if (cdp.valueLength(selector) != value.length()) {
            cdp.typeText(selector, value);
        }
    }

    public Result login(String user, SiteRecipe r, CredentialStore.Credential cred) {
        CdpClient cdp = new CdpClient(props.cdpHost(), portFor(user));
        String[] secrets = {cred.id(), cred.password()};
        Map<String, String> selectors = new LinkedHashMap<>();
        selectors.put("idSelector", r.idSelector());
        selectors.put("passwordSelector", r.passwordSelector());
        String submitState = null;      // 클릭 직전의 제출 버튼 상태
        try {
            cdp.open();
            cdp.navigate(r.loginUrl());
            if (!blank(r.successSelector()) && cdp.waitVisible(r.successSelector(), ALREADY_LOGGED_IN_WAIT_MS)) {
                cdp.close(); return new Result(Status.ALREADY_LOGGED_IN, "session still valid");
            }
            cdp.waitForSelector(r.idSelector(), FORM_WAIT_MS);
            fillChecked(cdp, r.idSelector(), cred.id());
            fillChecked(cdp, r.passwordSelector(), cred.password());
            if (submitStaysDisabled(cdp, r, SUBMIT_ENABLE_WAIT_MS)) {
                // 합성 이벤트를 무시하는 폼: 브라우저 입력으로 다시 넣어 본다
                cdp.typeText(r.idSelector(), cred.id());
                cdp.typeText(r.passwordSelector(), cred.password());
                if (submitStaysDisabled(cdp, r, SUBMIT_ENABLE_WAIT_MS)) {
                    Result res = fail(cdp, r, user, "submit stays disabled after fill", "disabled", selectors, secrets);
                    cdp.close();
                    return res;
                }
            }
            // 빈 폼을 제출하면 사이트의 "입력하세요" 오류가 비밀번호 오류처럼 보인다
            if (cdp.valueLength(r.idSelector()) <= 0 || cdp.valueLength(r.passwordSelector()) <= 0) {
                Result res = fail(cdp, r, user, "visible inputs empty before submit", null, selectors, secrets);
                cdp.close();
                return res;
            }
            if (!blank(r.submitText()) || !blank(r.submitSelector())) {
                submitState = submitStaysDisabled(cdp, r, 0) ? "disabled" : "enabled";
            }
            submit(cdp, r.submitText(), r.submitSelector());
            Status status = awaitOutcome(cdp, r, OUTCOME_WAIT_MS, true);
            if (status == Status.OTP_REQUIRED) {
                awaitingOtp.put(user.toLowerCase(), cdp);
                return new Result(Status.OTP_REQUIRED, "site asks for a one-time code");
            }
            Result res = status == Status.OK ? new Result(Status.OK, "logged in")
                    : fail(cdp, r, user, "login not confirmed after submit (wrong password, captcha, or layout change)", submitState, selectors, secrets);
            cdp.close();
            return res;
        } catch (Exception e) {
            Result res = fail(cdp, r, user, describe(e), submitState, selectors, secrets);
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
                        : fail(cdp, r, user, "OTP rejected or login not confirmed", null, otpSelectors(r), code);
            } catch (Exception e) {
                return fail(cdp, r, user, describe(e), null, otpSelectors(r), code);
            }
        }
    }
}
