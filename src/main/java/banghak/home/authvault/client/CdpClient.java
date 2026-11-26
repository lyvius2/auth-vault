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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
        int id = seq.incrementAndGet();
        ObjectNode m = om.createObjectNode();
        m.put("id", id);
        m.put("method", method);
        m.set("params", om.valueToTree(params));
        var f = new CompletableFuture<JsonNode>();
        pending.put(id, f);
        ws.sendText(om.writeValueAsString(m), true).get();
        JsonNode r = f.get(30, TimeUnit.SECONDS);
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

    public void click(String selector) throws Exception {
        String r = evalString("(() => { const el = document.querySelector(%s); if (!el) return 'missing'; el.click(); return 'ok'; })()".formatted(om.writeValueAsString(selector)));
        if (!"ok".equals(r)) {
            throw new IllegalStateException("element not found: " + selector);
        }
    }

    /** innerText가 text와 같고 화면에 보이는 첫 button을 클릭한다. */
    public void clickByText(String text) throws Exception {
        String js = """
            (() => { const t = %s;
              const el = [...document.querySelectorAll('button')].find(b => b.offsetParent != null && (b.innerText || '').trim() === t);
              if (!el) return 'missing'; el.click(); return 'ok'; })()
            """.formatted(om.writeValueAsString(text));
        if (!"ok".equals(evalString(js))) {
            throw new IllegalStateException("visible button not found by text: " + text);
        }
    }

    public boolean exists(String selector) throws Exception {
        return "1".equals(evalString("document.querySelector(%s) ? '1' : '0'".formatted(om.writeValueAsString(selector))));
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
