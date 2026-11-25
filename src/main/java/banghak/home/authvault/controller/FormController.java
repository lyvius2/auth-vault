package banghak.home.authvault.controller;

import banghak.home.authvault.service.CredentialStore;
import banghak.home.authvault.service.SlackAudit;
import banghak.home.authvault.service.TokenService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/vault")
public class FormController {
    private final TokenService tokens;
    private final CredentialStore store;
    private final SlackAudit audit;

    FormController(TokenService tokens, CredentialStore store, SlackAudit audit) {
        this.tokens = tokens;
        this.store = store;
        this.audit = audit;
    }

    @GetMapping(value = "/f/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String form(@PathVariable String token) {
        return tokens.peek(token).map(p -> page("""
            <h2>Cena AI 로그인 정보 등록</h2>
            <p>사이트: <b>%s</b> &nbsp; 사용자: <b>%s</b></p>
            <form method="post" action="/vault/f/%s" autocomplete="off">
              <label>아이디<br><input name="id" required autofocus></label><br><br>
              <label>비밀번호<br><input name="password" type="password" required></label><br><br>
              <button type="submit">저장</button>
            </form>
            <p class="muted">이 링크는 1회용이며 10분 뒤 만료됩니다. 비밀번호는 암호화되어 Mac mini에만 저장되고 AI 모델에는 전달되지 않습니다.</p>
            """.formatted(esc(p.site()), esc(p.user()), esc(token))))
                .orElse(page("<h2>링크가 만료되었거나 이미 사용되었습니다</h2><p>Slack에서 다시 요청해 주세요.</p>"));
    }

    @PostMapping(value = "/f/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String submit(@PathVariable String token, @RequestParam String id, @RequestParam String password) throws IOException {
        var p = tokens.consume(token).orElse(null);
        if (p == null) return page("<h2>링크가 만료되었거나 이미 사용되었습니다</h2>");
        store.put(p.user(), p.site(), id.trim(), password);
        audit.send("🔐 자격증명 등록: user=%s site=%s".formatted(p.user(), p.site()));
        return page("<h2>저장되었습니다</h2><p>Slack으로 돌아가 \"등록했어\"라고 말씀해 주세요. 이 창은 닫아도 됩니다.</p>");
    }

    private static String page(String body) {
        return """
            <!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Cena AI Vault</title><style>body{font-family:-apple-system,sans-serif;max-width:420px;margin:40px auto;padding:0 16px}
            input{width:100%%;padding:10px;font-size:16px}button{padding:10px 20px;font-size:16px}.muted{color:#777;font-size:13px}</style>
            </head><body>%s</body></html>""".formatted(body);
    }

    private static String esc(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
