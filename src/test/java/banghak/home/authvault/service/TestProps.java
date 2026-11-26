package banghak.home.authvault.service;

import banghak.home.authvault.config.VaultProperties;

import java.util.Map;

final class TestProps {
    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";   // 0x00 * 32

    static VaultProperties of(String masterKey, String dataFile, int ttlMinutes) {
        return new VaultProperties("https://vault.test", masterKey, dataFile, "recipes", ttlMinutes,
                "127.0.0.1", null, Map.of("yunson", 18800), new VaultProperties.Slack("", ""));
    }

    private TestProps() {}
}
