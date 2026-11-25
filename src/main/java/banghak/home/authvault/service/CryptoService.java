package banghak.home.authvault.service;

import banghak.home.authvault.config.VaultProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CryptoService {
    private final SecretKeySpec key;
    private final SecureRandom rng = new SecureRandom();

    CryptoService(VaultProperties p) {
        byte[] k = Base64.getDecoder().decode(p.masterKey().trim());
        if (k.length != 32) throw new IllegalStateException("VAULT_MASTER_KEY must be 32 bytes (base64). openssl rand -base64 32");
        this.key = new SecretKeySpec(k, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] nonce = new byte[12]; rng.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(nonce).put(ct).array());
        } catch (Exception e) { throw new IllegalStateException("encrypt failed", e); }
    }

    public String decrypt(String b64) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64));
            byte[] nonce = new byte[12]; buf.get(nonce);
            byte[] ct = new byte[buf.remaining()]; buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("decrypt failed (wrong key or corrupted data)", e); }
    }
}
