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
            cdp.click(r.submitSelector());
            Thread.sleep(Math.max(r.waitMs(), 1500));
            if (r.otpSelector() != null && cdp.exists(r.otpSelector())) {
                awaitingOtp.put(user.toLowerCase(), cdp);
                return new Result(Status.OTP_REQUIRED, "site asks for a one-time code");
            }
            boolean ok = r.successSelector() == null || cdp.exists(r.successSelector());
            cdp.close();
            return ok ? new Result(Status.OK, "logged in") : new Result(Status.FAILED, "success marker not found after submit (wrong password, captcha, or layout change)");
        } catch (Exception e) {
            cdp.close();
            return new Result(Status.FAILED, e.getMessage());
        }
    }

    public Result submitOtp(String user, SiteRecipe r, String code) {
        CdpClient cdp = awaitingOtp.remove(user.toLowerCase());
        if (cdp == null) {
            return new Result(Status.FAILED, "no login waiting for OTP");
        }
        try {
            cdp.fill(r.otpSelector(), code);
            if (r.otpSubmitSelector() != null) {
                cdp.click(r.otpSubmitSelector());
            }
            Thread.sleep(Math.max(r.waitMs(), 1500));
            boolean ok = r.successSelector() == null || cdp.exists(r.successSelector());
            return ok ? new Result(Status.OK, "logged in") : new Result(Status.FAILED, "OTP rejected or success marker not found");
        } catch (Exception e) {
            return new Result(Status.FAILED, e.getMessage());
        }
        finally {
            cdp.close();
        }
    }
}
