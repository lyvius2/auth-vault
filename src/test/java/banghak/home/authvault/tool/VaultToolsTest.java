package banghak.home.authvault.tool;

import banghak.home.authvault.service.BrowserLogin;
import banghak.home.authvault.service.BrowserLogin.Result;
import banghak.home.authvault.service.BrowserLogin.Status;
import banghak.home.authvault.service.CredentialStore;
import banghak.home.authvault.service.SlackAudit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

/** 절대 원칙 1: 도구 응답·예외·감사 로그 어디에도 평문 ID/PW가 나오면 안 된다. */
@SpringBootTest(properties = {
        "vault.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "vault.recipes-dir=recipes",
        "vault.base-url=https://vault.test",
        "vault.cdp-host=127.0.0.1",
        "vault.profiles.yunson=1",          // 닫힌 포트: 실제 BrowserLogin 실패 경로를 탄다
        "vault.profiles.hyemi=1"
})
class VaultToolsTest {
    private static final String SITE = "catchtable";
    private static final String ID = "secret-login-id@example.com";
    private static final String PW = "Pl4in-S3cret!비밀번호";

    @DynamicPropertySource
    static void dataFile(DynamicPropertyRegistry r) throws IOException {
        Path dir = Files.createTempDirectory("auth-vault-tools-test");
        r.add("vault.data-file", () -> dir.resolve("vault.json").toString());
    }

    @Autowired VaultTools tools;
    @Autowired CredentialStore store;
    @MockitoSpyBean BrowserLogin browser;
    @MockitoSpyBean SlackAudit audit;

    @BeforeEach
    void storeCredential() throws IOException {
        store.put("yunson", SITE, ID, PW);
    }

    private static void assertNoSecret(Object o) {
        assertThat(String.valueOf(o)).doesNotContain(ID).doesNotContain(PW);
    }

    private static void assertNoSecret(Map<String, Object> res) {
        assertThat(res).isNotNull();
        res.forEach((k, v) -> {
            assertNoSecret(k);
            assertNoSecret(v);
        });
    }

    @Test
    void loginNeverReturnsSecretsForAnyBrowserOutcome() {
        for (Status s : Status.values()) {
            doReturn(new Result(s, "detail for " + s)).when(browser).login(eq("yunson"), any(), any());
            Map<String, Object> res = tools.login(SITE, "yunson");
            assertThat(res).containsEntry("status", s.name().toLowerCase());
            assertNoSecret(res);
        }
    }

    @Test
    void loginWithUnreachableBrowserFailsWithoutSecrets() {
        Map<String, Object> res = tools.login(SITE, "yunson");     // 실제 BrowserLogin → CDP 접속 실패
        assertThat(res).containsEntry("status", "failed");
        assertThat(res.get("detail")).isNotNull();
        assertNoSecret(res);
    }

    @Test
    void loginWithoutCredentialsOrRecipe() {
        assertThat(tools.login(SITE, "hyemi")).containsEntry("status", "no_credentials");
        assertNoSecret(tools.login(SITE, "hyemi"));

        Map<String, Object> res = tools.login("no-such-site", "yunson");
        assertThat(res).containsEntry("status", "failed");
        assertNoSecret(res);
    }

    @Test
    void loginWithUnknownProfileDoesNotLeakInException() throws IOException {
        store.put("stranger", SITE, ID, PW);
        Throwable t = catchThrowable(() -> assertNoSecret(tools.login(SITE, "stranger")));
        for (Throwable c = t; c != null; c = c.getCause()) {
            assertNoSecret(c.getMessage());
        }
    }

    @Test
    void requestCredentialsReturnsOnlyLink() {
        Map<String, Object> res = tools.request_credentials(SITE, "yunson");
        assertThat(res).containsEntry("status", "ok");
        assertThat((String) res.get("url")).startsWith("https://vault.test/vault/f/");
        assertNoSecret(res);
        assertNoSecret(tools.request_credentials("no-such-site", "yunson"));
    }

    @Test
    void submitOtpNeverReturnsSecrets() {
        assertNoSecret(tools.submit_otp(SITE, "yunson", "123456"));    // 대기 중인 로그인 없음
        assertNoSecret(tools.submit_otp("no-such-site", "yunson", "123456"));

        doReturn(new Result(Status.OK, "logged in")).when(browser).submitOtp(eq("yunson"), any(), any());
        assertNoSecret(tools.submit_otp(SITE, "yunson", "123456"));
    }

    @Test
    void listSitesAndForgetNeverReturnSecrets() throws IOException {
        Map<String, Object> listed = tools.list_sites("yunson");
        assertThat(listed.get("stored")).asList().contains(SITE);
        assertNoSecret(listed);

        assertThat(tools.forget(SITE, "yunson")).containsEntry("status", "ok");
        assertThat(tools.forget(SITE, "yunson")).containsEntry("status", "not_found");
        assertNoSecret(tools.list_sites("yunson"));
    }

    @Test
    void auditMessagesNeverContainSecrets() throws IOException {
        tools.login(SITE, "yunson");
        tools.submit_otp(SITE, "yunson", "123456");
        tools.forget(SITE, "yunson");

        ArgumentCaptor<String> msgs = ArgumentCaptor.forClass(String.class);
        verify(audit, atLeastOnce()).send(msgs.capture());
        assertThat(msgs.getAllValues()).isNotEmpty().allSatisfy(VaultToolsTest::assertNoSecret);
    }
}
