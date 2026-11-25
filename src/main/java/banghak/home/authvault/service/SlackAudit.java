package banghak.home.authvault.service;

import banghak.home.authvault.config.VaultProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@Service
public class SlackAudit {
    private static final Logger log = LoggerFactory.getLogger(SlackAudit.class);
    private final VaultProperties.Slack slack;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();

    SlackAudit(VaultProperties p) {
        this.slack = p.slack();
    }

    public void send(String text) {
        log.info("AUDIT {}", text);
        if (slack == null || slack.botToken() == null || slack.botToken().isBlank() || slack.channel() == null || slack.channel().isBlank()) {
            return;
        }
        try {
            String body = om.writeValueAsString(Map.of("channel", slack.channel(), "text", "[vault] " + text));
            http.sendAsync(HttpRequest.newBuilder(URI.create("https://slack.com/api/chat.postMessage"))
                    .header("Authorization", "Bearer " + slack.botToken()).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("slack audit failed: {}", e.toString());
        }
    }
}
