package banghak.home.authvault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "vault.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "vault.data-file=${java.io.tmpdir}/auth-vault-context-test/vault.json",
        "vault.recipes-dir=recipes"
})
class AuthVaultApplicationTests {

    @Test
    void contextLoads() {
    }

}
