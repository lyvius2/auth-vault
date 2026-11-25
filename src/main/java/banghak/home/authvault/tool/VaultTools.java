package banghak.home.authvault.tool;

import banghak.home.authvault.client.SiteRecipe;
import banghak.home.authvault.config.VaultProperties;
import banghak.home.authvault.service.BrowserLogin;
import banghak.home.authvault.service.CredentialStore;
import banghak.home.authvault.service.SlackAudit;
import banghak.home.authvault.service.TokenService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class VaultTools {
    private final CredentialStore store;
    private final TokenService tokens;
    private final SiteRecipe.Loader recipes;
    private final BrowserLogin browser;
    private final VaultProperties props;
    private final SlackAudit audit;

    VaultTools(CredentialStore store, TokenService tokens, SiteRecipe.Loader recipes, BrowserLogin browser, VaultProperties props, SlackAudit audit) {
        this.store = store;
        this.tokens = tokens;
        this.recipes = recipes;
        this.browser = browser;
        this.props = props;
        this.audit = audit;
    }

    @Tool(description = "Log the given user into a site in that user's own browser profile, using credentials stored in the vault. "
            + "Returns status only: ok | already_logged_in | otp_required | no_credentials (then call request_credentials) | failed. Never returns the password.")
    public Map<String, Object> login(@ToolParam(description = "Site key, e.g. catchtable") String site,
                                     @ToolParam(description = "User key: yunson or hyemi") String user) {
        SiteRecipe r = recipes.load(site).orElse(null);
        if (r == null) {
            return Map.of("status", "failed", "detail", "no recipe for site " + site + "; known: " + recipes.sites());
        }
        var cred = store.get(user, site).orElse(null);
        if (cred == null) {
            return Map.of("status", "no_credentials", "detail", "call request_credentials to get a form link for the user");
        }
        var res = browser.login(user, r, cred);
        audit.send("🔑 login user=%s site=%s → %s".formatted(user, site, res.status()));
        return Map.of("status", res.status().name().toLowerCase(), "detail", res.detail());
    }

    @Tool(description = "Create a one-time HTTPS link (valid 10 minutes) where the user types their ID and password for a site. "
            + "Send the link to the user only via their own DM/channel. After they confirm, call login again.")
    public Map<String, Object> request_credentials(@ToolParam(description = "Site key, e.g. catchtable") String site,
                                                   @ToolParam(description = "User key: yunson or hyemi") String user) {
        if (recipes.load(site).isEmpty()) {
            return Map.of("status", "failed", "detail", "no recipe for site " + site + "; known: " + recipes.sites());
        }
        String t = tokens.issue(user, site);
        return Map.of("status", "ok", "url", props.baseUrl() + "/vault/f/" + t, "expires_in_minutes", props.tokenTtlMinutes());
    }

    @Tool(description = "Submit the one-time code the user received (SMS/app) to finish a login that returned otp_required.")
    public Map<String, Object> submit_otp(@ToolParam(description = "Site key") String site, @ToolParam(description = "User key") String user,
                                          @ToolParam(description = "The one-time code the user typed in chat") String code) {
        SiteRecipe r = recipes.load(site).orElse(null);
        if (r == null) {
            return Map.of("status", "failed", "detail", "no recipe");
        }
        var res = browser.submitOtp(user, r, code);
        audit.send("🔑 otp user=%s site=%s → %s".formatted(user, site, res.status()));
        return Map.of("status", res.status().name().toLowerCase(), "detail", res.detail());
    }

    @Tool(description = "List which sites have stored credentials for a user, and which sites have login recipes at all.")
    public Map<String, Object> list_sites(@ToolParam(description = "User key") String user) {
        return Map.of("stored", store.sites(user), "supported", recipes.sites());
    }

    @Tool(description = "Delete a user's stored credentials for a site. Only when the user explicitly asks.")
    public Map<String, Object> forget(@ToolParam(description = "Site key") String site, @ToolParam(description = "User key") String user) throws IOException {
        boolean removed = store.remove(user, site);
        if (removed) audit.send("🗑 자격증명 삭제: user=%s site=%s".formatted(user, site));
        return Map.of("status", removed ? "ok" : "not_found");
    }
}
