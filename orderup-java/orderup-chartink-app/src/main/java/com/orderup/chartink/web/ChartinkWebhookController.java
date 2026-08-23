package com.orderup.chartink.web;

import com.orderup.chartink.config.ChartinkProperties;
import com.orderup.chartink.service.ChartinkOrderService;
import com.orderup.chartink.service.ChartinkOrderService.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chartink alert webhook receiver.
 *
 * <p>Configure the Chartink alert to POST to
 * {@code https://<host>/chartink/webhook/{secret}} where {@code {secret}}
 * matches {@code chartink.webhook-secret}.
 *
 * <p><b>Payload format</b>. Chartink historically sends either
 * {@code application/json} or {@code application/x-www-form-urlencoded}
 * depending on the account tier and any past UI reflows. This controller
 * consumes both by reading the raw request body once and detecting the
 * shape (leading {@code &#123;} → JSON, otherwise form). Expected fields:
 * <pre>
 *   stocks         "TCS,INFY,HDFCBANK"
 *   trigger_prices "3800.5,1650.2,1450"
 *   triggered_at   "2:34 pm"
 *   scan_name      "MyScan URL slug"
 *   alert_name     "My alert display name"
 * </pre>
 *
 * <p><b>Response contract</b>. Always 200 on partial success — Chartink
 * treats non-2xx as a hard failure and retries the whole batch, which we
 * don't want after some symbols have already fired. 403/503 only for
 * pre-flight rejections (bad secret / kite not ready).
 *
 * <p><b>Debug</b>. The last raw request Chartink sent (headers + body) is
 * kept in memory and exposed at {@code GET /chartink/last-received}. Point
 * a browser at that endpoint after your first alert fires to verify the
 * exact wire format Chartink used.
 */
@RestController
@RequestMapping("/chartink")
public class ChartinkWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChartinkWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChartinkProperties props;
    private final ChartinkOrderService orders;

    /** Debug snapshot of the last webhook Chartink actually delivered. */
    private final AtomicReference<LastReceived> lastReceived = new AtomicReference<>();

    public ChartinkWebhookController(ChartinkProperties props, ChartinkOrderService orders) {
        this.props = props;
        this.orders = orders;
    }

    @PostMapping(value = "/webhook/{secret}",
            consumes = {MediaType.APPLICATION_JSON_VALUE,
                        MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                        MediaType.TEXT_PLAIN_VALUE,
                        MediaType.ALL_VALUE})
    public ResponseEntity<?> receive(@PathVariable("secret") String secret,
                                     @RequestBody(required = false) byte[] rawBody,
                                     HttpServletRequest req) throws Exception {

        String bodyStr = rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8);
        String contentType = req.getContentType() == null ? "" : req.getContentType();
        Map<String, String> headers = new LinkedHashMap<>();
        for (var e : Collections.list(req.getHeaderNames())) {
            headers.put(e, req.getHeader(e));
        }

        // Always stash the raw hit for the debug endpoint, even on rejections —
        // that way "why did Chartink get 403?" is answerable from the browser.
        lastReceived.set(new LastReceived(Instant.now(), req.getRemoteAddr(), contentType, headers, bodyStr));

        if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            log.warn("Webhook rejected: chartink.webhook-secret is not configured.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "webhook secret not configured on server"));
        }
        if (!props.webhookSecret().equals(secret)) {
            log.warn("Webhook rejected: bad secret ({} chars) from {}", secret == null ? -1 : secret.length(), req.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad secret"));
        }
        if (!orders.isReady()) {
            log.warn("Webhook rejected: Kite auth / instruments not ready yet.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "kite not authenticated or instruments not loaded — visit /kite/login"));
        }

        ChartinkPayload payload = parse(bodyStr, contentType);
        String[] symbols = split(payload.stocks);
        String[] prices  = split(payload.trigger_prices);
        String alertName = payload.alert_name == null ? "chartink" : payload.alert_name;
        int qty = props.qtyOrOne();

        log.info("[CHARTINK] scan='{}' alert='{}' triggered_at='{}' symbols={} contentType='{}'",
                payload.scan_name, alertName, payload.triggered_at, symbols.length, contentType);

        Map<Result, Integer> tally = new EnumMap<>(Result.class);
        for (Result r : Result.values()) tally.put(r, 0);
        Map<String, String> perSymbol = new LinkedHashMap<>();
        for (int i = 0; i < symbols.length; i++) {
            String sym = symbols[i];
            double px = safeDouble(prices, i);
            Result r = orders.fire(sym, alertName, px, qty);
            tally.merge(r, 1, Integer::sum);
            perSymbol.put(sym, r.name());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", symbols.length);
        out.put("tally", tally);
        out.put("symbols", perSymbol);
        return ResponseEntity.ok(out);
    }

    /** Echoes the last hit Chartink made — headers + body, verbatim. */
    @GetMapping("/last-received")
    public ResponseEntity<?> lastReceived() {
        LastReceived lr = lastReceived.get();
        if (lr == null) {
            return ResponseEntity.ok(Map.of("received", false,
                    "hint", "waiting for the first Chartink webhook…"));
        }
        return ResponseEntity.ok(Map.of(
                "at",          lr.at().toString(),
                "remoteAddr",  lr.remoteAddr(),
                "contentType", lr.contentType(),
                "headers",     lr.headers(),
                "body",        lr.body()
        ));
    }

    /** Parse either JSON or form-urlencoded into our uniform record. */
    private ChartinkPayload parse(String body, String contentType) {
        ChartinkPayload p = new ChartinkPayload();
        if (body == null || body.isBlank()) return p;
        String trimmed = body.trim();
        boolean looksJson = trimmed.startsWith("{")
                || (contentType != null && contentType.toLowerCase().contains("json"));
        try {
            if (looksJson) {
                Map<?, ?> m = JSON.readValue(trimmed, Map.class);
                p.stocks         = str(m.get("stocks"));
                p.trigger_prices = str(m.get("trigger_prices"));
                p.triggered_at   = str(m.get("triggered_at"));
                p.scan_name      = str(m.get("scan_name"));
                p.alert_name     = str(m.get("alert_name"));
            } else {
                for (String pair : trimmed.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    String k = urlDecode(pair.substring(0, eq));
                    String v = urlDecode(pair.substring(eq + 1));
                    switch (k) {
                        case "stocks"         -> p.stocks         = v;
                        case "trigger_prices" -> p.trigger_prices = v;
                        case "triggered_at"   -> p.triggered_at   = v;
                        case "scan_name"      -> p.scan_name      = v;
                        case "alert_name"     -> p.alert_name     = v;
                        default -> {}
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Chartink payload parse failed (contentType={}): {}", contentType, e.getMessage());
        }
        return p;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String[] split(String csv) {
        if (csv == null || csv.isBlank()) return new String[0];
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    private static double safeDouble(String[] arr, int i) {
        if (arr == null || i >= arr.length) return 0.0;
        try { return Double.parseDouble(arr[i].trim()); } catch (Exception ignore) { return 0.0; }
    }

    /**
     * Chartink payload — flat POJO with snake_case field names matching the
     * vendor's on-the-wire schema so callers can also stringify it via
     * {@link ObjectMapper} without a mixin.
     */
    public static class ChartinkPayload {
        public String stocks;
        public String trigger_prices;
        public String triggered_at;
        public String scan_name;
        public String alert_name;
    }

    /** In-memory snapshot of the last raw hit for debugging. */
    private record LastReceived(
            Instant at,
            String remoteAddr,
            String contentType,
            Map<String, String> headers,
            String body
    ) {}
}

