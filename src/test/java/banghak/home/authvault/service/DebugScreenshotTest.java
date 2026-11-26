package banghak.home.authvault.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DebugScreenshotTest {
    @TempDir Path tmp;

    @Test
    void savesUnderDebugDirWithSiteUserTimestampName() throws Exception {
        Path dir = tmp.resolve("debug");
        String name = BrowserLogin.saveScreenshot(dir, "CatchTable", "yunson", new byte[] {1, 2, 3});

        assertThat(name).matches("catchtable-yunson-\\d{8}-\\d{6}\\.png");
        assertThat(Files.readAllBytes(dir.resolve(name))).containsExactly(1, 2, 3);
    }

    @Test
    void fileNameCannotEscapeDebugDir() throws Exception {
        Path dir = tmp.resolve("debug");
        String name = BrowserLogin.saveScreenshot(dir, "../../etc/x", "a/b", new byte[] {1});

        assertThat(name).doesNotContain("/").doesNotContain("..");
        assertThat(dir.resolve(name)).exists();
        try (var files = Files.list(tmp)) {
            assertThat(files.toList()).containsExactly(dir);
        }
    }

    @Test
    void keepsOnlyTheMostRecentShots() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("debug"));
        for (int i = 0; i < BrowserLogin.DEBUG_KEEP + 5; i++) {
            Path old = Files.write(dir.resolve("site-user-20200101-0000%02d.png".formatted(i)), new byte[] {0});
            old.toFile().setLastModified(1_600_000_000_000L + i * 1000L);
        }
        Files.writeString(dir.resolve("notes.txt"), "not a screenshot");

        String name = BrowserLogin.saveScreenshot(dir, "site", "user", new byte[] {1});

        try (var files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names).hasSize(BrowserLogin.DEBUG_KEEP + 1)      // png 20개 + notes.txt
                    .contains(name, "notes.txt", "site-user-20200101-000024.png")
                    .doesNotContain("site-user-20200101-000000.png", "site-user-20200101-000005.png");
        }
    }
}
