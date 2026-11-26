package banghak.home.authvault.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class CdpClient implements AutoCloseable {
    private static final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String host;
    private final int port;
    private WebSocket ws;
    private String targetId;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    public static final long POLL_MS = 250;
    private final StringBuilder partial = new StringBuilder();

    public CdpClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void open() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://%s:%d/json/new?about:blank".formatted(host, port)))
                .header("Host", "localhost")
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        JsonNode tab = om.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
        targetId = tab.get("id").asText();
        String wsUrl = tab.get("webSocketDebuggerUrl").asText().replaceFirst("ws://[^/]+", "ws://%s:%d".formatted(host, port));
        ws = http.newWebSocketBuilder().header("Host", "localhost").connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            String msg = partial.toString();
                            partial.setLength(0); dispatch(msg);
                        }
                        w.request(1);
                        return null;
                    }
                }).get();
        call("Page.enable", Map.of());
        call("Runtime.enable", Map.of());
    }

    private void dispatch(String msg) {
        try {
            JsonNode n = om.readTree(msg);
            if (n.has("id")) {
                var f = pending.remove(n.get("id").asInt());
                if (f != null) f.complete(n);
            }
        }
        catch (Exception ignored) {}
    }

    public JsonNode call(String method, Map<String, Object> params) throws Exception {
        return call(method, params, 30);
    }

    public JsonNode call(String method, Map<String, Object> params, int timeoutSeconds) throws Exception {
        int id = seq.incrementAndGet();
        ObjectNode m = om.createObjectNode();
        m.put("id", id);
        m.put("method", method);
        m.set("params", om.valueToTree(params));
        var f = new CompletableFuture<JsonNode>();
        pending.put(id, f);
        ws.sendText(om.writeValueAsString(m), true).get();
        JsonNode r = f.get(timeoutSeconds, TimeUnit.SECONDS);
        if (r.has("error")) {
            throw new IllegalStateException("CDP " + method + ": " + r.get("error"));
        }
        return r.get("result");
    }

    public void navigate(String url) throws Exception {
        call("Page.navigate", Map.of("url", url));
        for (int i = 0; i < 60; i++) {
            if ("complete".equals(evalString("document.readyState"))) {
                return;
            }
            Thread.sleep(250);
        }
    }

    public String evalString(String js) throws Exception {
        JsonNode r = call("Runtime.evaluate", Map.of("expression", js, "returnByValue", true, "awaitPromise", true));
        JsonNode v = r.path("result").path("value");
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    public void fill(String selector, String value) throws Exception {
        String js = """
            (() => { const el = document.querySelector(%s); if (!el) return 'missing';
              const set = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              el.focus(); set.call(el, %s); el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); return 'ok'; })()
            """.formatted(om.writeValueAsString(selector), om.writeValueAsString(value));
        if (!"ok".equals(evalString(js))) {
            throw new IllegalStateException("element not found: " + selector);
        }
    }

    // offsetParent는 position:fixed 요소에서 null이라, 그 경우는 getClientRects로 보이는지 판단한다.
    private static final String VIS = "const vis = el => !!el && (el.offsetParent != null || el.getClientRects().length > 0);";

    private static String bySelector(String selector) throws Exception {
        return "document.querySelector(%s)".formatted(om.writeValueAsString(selector));
    }

    // innerText가 text와 같은 button. 보이는 것을 우선하고, 없으면 첫 번째.
    private static String byText(String text) throws Exception {
        return "(t => { const bs = [...document.querySelectorAll('button')].filter(b => (b.innerText || '').trim() === t); return bs.find(vis) || bs[0]; })(%s)"
                .formatted(om.writeValueAsString(text));
    }

    private String onElement(String elementJs, String bodyJs) throws Exception {
        return evalString("(() => { %s const el = %s; if (!el) return 'missing'; %s })()".formatted(VIS, elementJs, bodyJs));
    }

    private static final String CLICK = "el.click(); return 'ok';";
    private static final String DISABLED = "return (el.disabled || el.getAttribute('aria-disabled') === 'true') ? 'disabled' : 'enabled';";

    public void click(String selector) throws Exception {
        if (!"ok".equals(onElement(bySelector(selector), CLICK))) {
            throw new IllegalStateException("element not found: " + selector);
        }
    }

    public void clickByText(String text) throws Exception {
        if (!"ok".equals(onElement(byText(text), CLICK))) {
            throw new IllegalStateException("button not found by text: " + text);
        }
    }

    /** 요소가 있고 disabled(또는 aria-disabled)일 때만 true. */
    public boolean isDisabled(String selector) throws Exception {
        return "disabled".equals(onElement(bySelector(selector), DISABLED));
    }

    public boolean isDisabledByText(String text) throws Exception {
        return "disabled".equals(onElement(byText(text), DISABLED));
    }

    /** fill()의 합성 이벤트를 무시하는 폼용: 입력칸을 비우고 focus한 뒤 브라우저 입력(Input.insertText)으로 넣는다. */
    public void typeText(String selector, String value) throws Exception {
        try {
            call("Emulation.setFocusEmulationEnabled", Map.of("enabled", true));    // 백그라운드 탭에서도 focus 유지
        } catch (Exception ignored) {}
        String prepare = """
            const set = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set || Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
            set.call(el, ''); el.focus(); return 'ok';""";
        if (!"ok".equals(onElement(bySelector(selector), prepare))) {
            throw new IllegalStateException("element not found: " + selector);
        }
        // 글자 단위로 keydown → 입력 → keyup. keyup/keydown에만 반응하는 폼도 있다.
        for (int codePoint : value.codePoints().toArray()) {
            String ch = Character.toString(codePoint);
            call("Input.dispatchKeyEvent", Map.of("type", "rawKeyDown", "key", ch));
            call("Input.insertText", Map.of("text", ch));
            call("Input.dispatchKeyEvent", Map.of("type", "keyUp", "key", ch));
        }
        onElement(bySelector(selector), "el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); return 'ok';");
    }

    /** 현재 화면의 PNG. 입력칸에 적힌 글자(ID 등)는 찍히지 않도록 가린 뒤 찍는다. */
    public byte[] screenshotPng() throws Exception {
        evalString("""
            (() => { const s = document.createElement('style');
              s.textContent = 'input,textarea{color:transparent!important;text-shadow:none!important;caret-color:transparent!important;-webkit-text-fill-color:transparent!important}';
              document.documentElement.appendChild(s); return 'ok'; })()""");
        JsonNode r = call("Page.captureScreenshot", Map.of("format", "png"), 10);
        return Base64.getDecoder().decode(r.get("data").asText());
    }

    /** 화면에 보이는 요소 중 오류·인증·캡차 관련 문구를 담은 것의 텍스트(각 100자, 최대 3개). 없으면 빈 문자열. */
    public String errorHints() throws Exception {
        String r = evalString("""
            (() => { %s const re = /오류|올바르지|실패|인증|captcha|robot/i; const out = [];
              const skip = ['SCRIPT','STYLE','NOSCRIPT','INPUT','TEXTAREA','SELECT','OPTION'];
              for (const el of document.querySelectorAll('body *')) {
                if (out.length >= 3) break;
                if (skip.includes(el.tagName)) continue;
                const t = (el.innerText || '').trim();
                if (!t || !re.test(t) || !vis(el)) continue;
                if ([...el.children].some(c => re.test(c.innerText || ''))) continue;   // 가장 안쪽 요소만
                const cut = t.replace(/\\s+/g, ' ').slice(0, 100);
                if (!out.includes(cut)) out.push(cut);
              }
              return out.join(' | '); })()""".formatted(VIS));
        return r == null ? "" : r;
    }

    public boolean exists(String selector) throws Exception {
        return "1".equals(evalString("%s ? '1' : '0'".formatted(bySelector(selector))));
    }

    /** 요소가 있고 화면에 보이는지. */
    public boolean visible(String selector) throws Exception {
        return "1".equals(evalString("(() => { %s return vis(%s) ? '1' : '0'; })()".formatted(VIS, bySelector(selector))));
    }

    /** 요소가 보일 때까지 250ms 간격으로 기다린다. 시간 안에 보이면 true. */
    public boolean waitVisible(String selector, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (visible(selector)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(POLL_MS);
        }
    }

    public void waitForSelector(String selector) throws Exception {
        waitForSelector(selector, 10_000);
    }

    public void waitForSelector(String selector, long timeoutMs) throws Exception {
        if (!waitVisible(selector, timeoutMs)) {
            throw new TimeoutException("element not found: " + selector);
        }
    }

    @Override public void close() {
        try {
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {}
        try {
            if (targetId != null) {
                http.send(HttpRequest.newBuilder(URI.create("http://%s:%d/json/close/%s".formatted(host, port, targetId)))
                        .header("Host", "localhost")
                        .GET()
                        .build(),
                        HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception ignored) {}
    }
}
