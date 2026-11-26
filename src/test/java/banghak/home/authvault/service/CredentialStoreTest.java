package banghak.home.authvault.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialStoreTest {
    private static final String ID = "yunson@example.com";
    private static final String PW = "pl4in-P@ssw0rd";

    @TempDir Path dir;

    private CredentialStore open() throws IOException {
        var props = TestProps.of(TestProps.KEY, dir.resolve("vault.json").toString(), 10);
        return new CredentialStore(props, new CryptoService(props));
    }

    @Test
    void putGetRemove() throws IOException {
        CredentialStore store = open();
        assertThat(store.has("yunson", "catchtable")).isFalse();
        assertThat(store.get("yunson", "catchtable")).isEmpty();

        store.put("Yunson", "CatchTable", ID, PW);       // 키는 대소문자를 구분하지 않는다
        assertThat(store.has("yunson", "catchtable")).isTrue();
        assertThat(store.get("yunson", "catchtable")).contains(new CredentialStore.Credential(ID, PW));
        assertThat(store.sites("yunson")).containsExactly("catchtable");
        assertThat(store.sites("hyemi")).isEmpty();
        assertThat(store.get("hyemi", "catchtable")).isEmpty();

        assertThat(store.remove("yunson", "catchtable")).isTrue();
        assertThat(store.remove("yunson", "catchtable")).isFalse();
        assertThat(store.get("yunson", "catchtable")).isEmpty();
    }

    @Test
    void fileIsEncryptedAndSurvivesRestart() throws IOException {
        open().put("yunson", "catchtable", ID, PW);

        String onDisk = Files.readString(dir.resolve("vault.json"));
        assertThat(onDisk).doesNotContain(ID).doesNotContain(PW);

        assertThat(open().get("yunson", "catchtable")).contains(new CredentialStore.Credential(ID, PW));
    }

    @Test
    void removeIsPersisted() throws IOException {
        CredentialStore store = open();
        store.put("yunson", "catchtable", ID, PW);
        store.remove("yunson", "catchtable");

        assertThat(open().has("yunson", "catchtable")).isFalse();
    }
}
