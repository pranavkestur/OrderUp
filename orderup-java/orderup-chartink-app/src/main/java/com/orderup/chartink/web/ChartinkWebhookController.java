package com.orderup.chartink.web;

import com.orderup.chartink.config.ChartinkProperties;
import com.orderup.chartink.service.ChartinkOrderService;
import com.orderup.chartink.service.ChartinkOrderService.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chartink alert webhook receiver.
 *
 * <p>Configure the Chartink alert to POST JSON to
 * {@code http://<host>:8080/chartink/webhook/{secret}} where {@code {secret}}
 * matches {@code chartink.webhook-secret}.
 *
 * <p>Chartink payload shape:
 * <pre>
 *   {
 *     "stocks":          "TCS,INFY,HDFCBANK",
 *     "trigger_prices":  "3800.5,1650.2,1450",
 *     "triggered_at":    "2:34 pm",
 *     "scan_name":       "MyScan",
 *     "alert_name":      "MyAlert"
 *   }
 * </pre>
 *
 * <p>Response body summarizes per-symbol outcome so Chartink retries surface
 * usefully. Always returns 200 on partial success — Chartink treats non-2xx
 * as a hard failure and retries the whole batch, which we don't want after
 * some symbols already placed.
 */
@RestController
@RequestMapping("/chartink")
public class ChartinkWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChartinkWebhookController.class);

    private final ChartinkProperties props;
    private final ChartinkOrderService orders;

    public ChartinkWebhookController(ChartinkProperties props, ChartinkOrderService orders) {
        this.props = props;
        this.orders = orders;
    }

    @PostMapping("/webhook/{secret}")
    public ResponseEntity<?> receive(@PathVariable("secret") String secret,
                                     @RequestBody ChartinkPayload payload) {
        if (props.webhookSecret() == null || props.webhookSecret().isBlank()) {
            log.warn("Webhook rejected: chartink.webhook-secret is not configured.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "webhook secret not configured on server"));
        }
        if (!props.webhookSecret().equals(secret)) {
            log.warn("Webhook rejected: bad secret from scan '{}'", payload == null ? "?" : payload.scan_name);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad secret"));
        }
        if (!orders.isReady()) {
            log.warn("Webhook rejected: Kite auth / instruments not ready yet.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "kite not authenticated or instruments not loaded — visit /kite/login"));
        }

        String[] symbols = split(payload == null ? null : payload.stocks);
        String[] prices  = split(payload == null ? null : payload.trigger_prices);
        String alertName = payload == null || payload.alert_name == null ? "chartink" : payload.alert_name;
        int qty = props.qtyOrOne();

        log.info("[CHARTINK] scan='{}' alert='{}' triggered_at='{}' symbols={}",
                payload == null ? null : payload.scan_name, alertName,
                payload == null ? null : payload.triggered_at, symbols.length);

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
     * Chartink JSON payload. Field names match the vendor's on-the-wire names
     * (snake_case). Kept as a public POJO so Jackson can populate it.
     */
    public static class ChartinkPayload {
        public String stocks;
        public String trigger_prices;
        public String triggered_at;
        public String scan_name;
        public String alert_name;
    }
}

