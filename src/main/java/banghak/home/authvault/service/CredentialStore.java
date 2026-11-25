package banghak.home.authvault.service;

import banghak.home.authvault.config.VaultProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
public class CredentialStore {
    public record Credential(String id, String password) {}
    record Entry(String idEnc, String pwEnc, String updatedAt) {}

    private final Path file;
    private final CryptoService crypto;
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, Entry> entries = new TreeMap<>();

    CredentialStore(VaultProperties p, CryptoService crypto) throws IOException {
        this.file = Path.of(p.dataFile()); this.crypto = crypto;
        if (Files.exists(file)) entries.putAll(om.readValue(file.toFile(), new TypeReference<Map<String, Entry>>() {}));
    }

    private static String key(String user, String site) { return user.toLowerCase() + "|" + site.toLowerCase(); }

    public synchronized boolean has(String user, String site) { return entries.containsKey(key(user, site)); }

    public synchronized Optional<Credential> get(String user, String site) {
        Entry e = entries.get(key(user, site));
        return e == null ? Optional.empty() : Optional.of(new Credential(crypto.decrypt(e.idEnc()), crypto.decrypt(e.pwEnc())));
    }

    public synchronized void put(String user, String site, String id, String pw) throws IOException {
        entries.put(key(user, site), new Entry(crypto.encrypt(id), crypto.encrypt(pw), Instant.now().toString()));
        save();
    }

    public synchronized boolean remove(String user, String site) throws IOException {
        boolean r = entries.remove(key(user, site)) != null; if (r) save(); return r;
    }

    public synchronized List<String> sites(String user) {
        String prefix = user.toLowerCase() + "|";
        return entries.keySet().stream().filter(k -> k.startsWith(prefix)).map(k -> k.substring(prefix.length())).toList();
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        om.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), entries);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
