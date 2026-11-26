package banghak.home.authvault.client;

import banghak.home.authvault.config.VaultProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record SiteRecipe(
    String site,
    String loginUrl,
    String idSelector,
    String passwordSelector,
    String submitSelector,
    String successSelector,
    String otpSelector,
    String otpSubmitSelector,
    int waitMs,                     // OTP 제출 후 판정 전 대기(최소 1500). 로그인 제출 후에는 쓰지 않는다
    String submitText,              // 선택. 있으면 submitSelector 대신 이 글자의 버튼을 클릭
    String otpSubmitText,           // 선택. 있으면 otpSubmitSelector 대신
    String successUrlContains       // 선택. 제출 후 URL에 이 문자열이 있으면 성공
) {
    @Service
    public static class Loader {
        private final Path dir;
        private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

        Loader(VaultProperties p) {
            this.dir = Path.of(p.recipesDir());
        }

        public Optional<SiteRecipe> load(String site) {
            Path f = dir.resolve(site.toLowerCase() + ".yml");
            if (!Files.exists(f)) {
                return Optional.empty();
            }
            try {
                return Optional.of(yaml.readValue(f.toFile(), SiteRecipe.class));
            } catch (IOException e) {
                throw new IllegalStateException("bad recipe " + f, e);
            }
        }
        public List<String> sites() {
            try (var s = Files.list(dir)) {
                return s.map(x -> x.getFileName().toString()).filter(n -> n.endsWith(".yml"))
                        .map(n -> n.substring(0, n.length() - 4))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
        }
    }
}
