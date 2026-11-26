package banghak.home.authvault.client;

import banghak.home.authvault.config.VaultProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SiteRecipeTest {
    @TempDir Path dir;

    private SiteRecipe.Loader loader(Path recipesDir) {
        return new SiteRecipe.Loader(new VaultProperties("", "", "", recipesDir.toString(), 10, "", null, Map.of(), null));
    }

    private SiteRecipe parse(String yaml) throws IOException {
        Files.writeString(dir.resolve("site.yml"), yaml);
        return loader(dir).load("site").orElseThrow();
    }

    @Test
    void legacyRecipeWithoutNewFieldsStillLoads() throws IOException {
        SiteRecipe r = parse("""
                site: legacy
                loginUrl: https://example.com/login
                idSelector: 'input[name="id"]'
                passwordSelector: 'input[type="password"]'
                submitSelector: 'button[type="submit"]'
                successSelector: 'a[href*="/my"]'
                otpSelector: 'input[name="authCode"]'
                otpSubmitSelector: 'button[type="submit"]'
                waitMs: 2500
                """);

        assertThat(r.submitSelector()).isEqualTo("button[type=\"submit\"]");
        assertThat(r.waitMs()).isEqualTo(2500);
        assertThat(r.submitText()).isNull();
        assertThat(r.otpSubmitText()).isNull();
        assertThat(r.successUrlContains()).isNull();
    }

    @Test
    void newFieldsAreReadAndSelectorsBecomeOptional() throws IOException {
        SiteRecipe r = parse("""
                site: modern
                loginUrl: https://example.com/login
                idSelector: '#id'
                passwordSelector: '#pw'
                submitText: 로그인
                successUrlContains: /home
                otpSelector: '#otp'
                otpSubmitText: 확인
                """);

        assertThat(r.submitText()).isEqualTo("로그인");
        assertThat(r.otpSubmitText()).isEqualTo("확인");
        assertThat(r.successUrlContains()).isEqualTo("/home");
        assertThat(r.submitSelector()).isNull();
        assertThat(r.successSelector()).isNull();
        assertThat(r.otpSubmitSelector()).isNull();
        assertThat(r.waitMs()).isZero();
    }

    @Test
    void bundledRecipesStillLoad() {
        SiteRecipe.Loader bundled = loader(Path.of("recipes"));
        assertThat(bundled.sites()).contains("catchtable");
        for (String site : bundled.sites()) {
            assertThat(bundled.load(site)).as(site).isPresent();
        }
    }
}
