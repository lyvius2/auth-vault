package banghak.home.authvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "vault")
public record VaultProperties(String baseUrl, String masterKey, String dataFile, String recipesDir,
                              int tokenTtlMinutes, String cdpHost, String uaOverride, Map<String, Integer> profiles, Slack slack) {
    public record Slack(String botToken, String channel) {}
}