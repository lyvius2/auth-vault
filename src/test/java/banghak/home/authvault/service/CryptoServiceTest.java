package banghak.home.authvault.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {
    private final CryptoService crypto = new CryptoService(TestProps.of(TestProps.KEY, "unused", 10));

    @Test
    void roundTrip() {
        String plain = "p@ss word/한글 🔐";
        String enc = crypto.encrypt(plain);
        assertThat(enc).doesNotContain(plain);
        assertThat(crypto.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    void nonceIsRandomPerEncryption() {
        assertThat(crypto.encrypt("same")).isNotEqualTo(crypto.encrypt("same"));
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] raw = Base64.getDecoder().decode(crypto.encrypt("secret"));
        raw[raw.length - 1] ^= 1;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyCannotDecrypt() {
        String otherKey = Base64.getEncoder().encodeToString(new byte[] {
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
        CryptoService other = new CryptoService(TestProps.of(otherKey, "unused", 10));
        String enc = crypto.encrypt("secret");
        assertThatThrownBy(() -> other.decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyMustBe32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new CryptoService(TestProps.of(shortKey, "unused", 10)))
                .isInstanceOf(IllegalStateException.class);
    }
}
