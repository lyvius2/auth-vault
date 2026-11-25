package banghak.home.authvault.service;

import banghak.home.authvault.config.VaultProperties;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    public record Pending(String user, String site, Instant expires) {}
    private final Map<String, Pending> tokens = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final int ttlMin;

    TokenService(VaultProperties p) {
        this.ttlMin = p.tokenTtlMinutes();
    }

    public String issue(String user, String site) {
        byte[] b = new byte[24]; rng.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.put(t, new Pending(user, site, Instant.now().plusSeconds(ttlMin * 60L)));
        return t;
    }

    public Optional<Pending> consume(String token) {
        Pending p = tokens.remove(token);
        return (p == null || p.expires().isBefore(Instant.now())) ? Optional.empty() : Optional.of(p);
    }

    public Optional<Pending> peek(String token) {
        Pending p = tokens.get(token);
        return (p == null || p.expires().isBefore(Instant.now())) ? Optional.empty() : Optional.of(p);
    }
}
