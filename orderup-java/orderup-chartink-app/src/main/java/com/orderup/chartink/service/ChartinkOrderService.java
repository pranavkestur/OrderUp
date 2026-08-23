package com.orderup.chartink.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderup.auth.KiteAuthService;
import com.orderup.notify.TelegramNotifier;
import com.orderup.orders.OrderRecordRepository;
import com.orderup.orders.OrderService;
import com.orderup.orders.OrderService.SignalMeta;
import com.orderup.orders.PotentialOrderRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Per-symbol per-day de-dup wrapper around {@link OrderService}. Prevents
 * duplicate fires if Chartink retries the webhook or fires the same scan
 * multiple times in a session — including across app restarts.
 *
 * <p>Also guards against pre-auth webhooks: if Kite auth hasn't completed,
 * the caller sees {@link Result#NOT_READY} so the HTTP layer can return 503.
 *
 * <p><b>No local symbol validation.</b> Chartink is the source of truth for
 * the ticker (it scanned NSE and published the match). We trust its payload
 * and let Kite reject at the API if the symbol is bad — which surfaces via
 * the existing REJECTED path with a Telegram alert. This lets us skip the
 * 10k-row NSE EQ instrument dump on boot entirely.
 *
 * <p><b>Restart-safe dedup.</b> The in-memory {@link #firedToday} set is
 * re-seeded from H2 on boot by scanning every {@code OrderRecord} and
 * {@code PotentialOrder} with {@code indicator=CHARTINK} whose
 * {@code placedAt} is at/after today's IST midnight. So if the app crashes
 * or is redeployed mid-market, Chartink's next retry of a symbol we
 * already handled is still recognised as {@link Result#DUPLICATE} and
 * neither hits Kite nor spams Telegram.
 */
@Service
public class ChartinkOrderService {

    private static final Logger log = LoggerFactory.getLogger(ChartinkOrderService.class);
    /** IST for the "one order per symbol per trading day" gate. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String STRATEGY = "CHARTINK";

    private final OrderService orders;
    private final KiteAuthService auth;
    @SuppressWarnings("unused") // Retained for potential future Telegram sends from this service.
    private final TelegramNotifier notifier;
    private final OrderRecordRepository orderRecordRepo;
    private final PotentialOrderRepository potentialOrderRepo;

    /** Set of "SYMBOL" that already fired today, cleared on IST day rollover. */
    private final Set<String> firedToday = ConcurrentHashMap.newKeySet();
    private final AtomicReference<LocalDate> firedDay = new AtomicReference<>(LocalDate.now(IST));

    public ChartinkOrderService(OrderService orders,
                                KiteAuthService auth,
                                TelegramNotifier notifier,
                                OrderRecordRepository orderRecordRepo,
                                PotentialOrderRepository potentialOrderRepo) {
        this.orders = orders;
        this.auth = auth;
        this.notifier = notifier;
        this.orderRecordRepo = orderRecordRepo;
        this.potentialOrderRepo = potentialOrderRepo;
    }

    public enum Result { PLACED, DUPLICATE, UNKNOWN_SYMBOL, NOT_READY, REJECTED }

    public boolean isReady() {
        return auth.isAuthenticated();
    }

    /**
     * Rehydrate the in-memory dedup set from persisted state so restarts
     * mid-market don't cause duplicate BUYs / Telegram spam. Runs once at
     * bean init; cheap because trading-day row counts are small.
     */
    @PostConstruct
    void seedFiredTodayFromDb() {
        try {
            Instant startOfDay = LocalDate.now(IST).atStartOfDay(IST).toInstant();
            // Live orders that made it to Kite
            orderRecordRepo.findAll().stream()
                    .filter(o -> STRATEGY.equalsIgnoreCase(o.getIndicator()))
                    .filter(o -> "BUY".equalsIgnoreCase(o.getSide()))
                    .filter(o -> o.getSymbol() != null && o.getPlacedAt() != null)
                    .filter(o -> !o.getPlacedAt().isBefore(startOfDay))
                    .map(o -> o.getSymbol().toUpperCase(Locale.ROOT))
                    .forEach(firedToday::add);
            // Signals that were logged as DRY / rejected today — still count as
            // "seen" so we don't re-fire them post-restart.
            potentialOrderRepo.findAll().stream()
                    .filter(p -> STRATEGY.equalsIgnoreCase(p.getIndicator()))
                    .filter(p -> "BUY".equalsIgnoreCase(p.getSide()))
                    .filter(p -> p.getSymbol() != null && p.getPlacedAt() != null)
                    .filter(p -> !p.getPlacedAt().isBefore(startOfDay))
                    .map(p -> p.getSymbol().toUpperCase(Locale.ROOT))
                    .forEach(firedToday::add);
            if (!firedToday.isEmpty()) {
                log.info("Chartink dedup seeded from DB: {} symbols already handled today ({}).",
                        firedToday.size(),
                        firedToday.stream().sorted().collect(Collectors.joining(", ")));
            } else {
                log.info("Chartink dedup: no prior fires today — starting fresh.");
            }
        } catch (Throwable t) {
            // Never block boot on a seeder failure; worst case is we fall back
            // to pre-fix in-memory-only behavior for this session.
            log.warn("Could not seed firedToday from DB — running with in-memory-only dedup: {}",
                    t.getMessage());
        }
    }

    /**
     * Fire one BUY order for {@code symbol}. Called by the webhook controller
     * for every comma-separated entry in the Chartink payload.
     *
     * @param columns per-row metadata from the alert's "Payload Columns"
     *                (keys = user-defined aliases like {@code symbol},
     *                {@code industry}, {@code sector}). Empty map when the
     *                alert has no columns configured. Rendered into the
     *                order's audit {@code reason} and the Telegram fill
     *                confirmation.
     */
    public Result fire(String symbol, String alertName, double triggerPrice, int quantity,
                       Map<String, Object> columns) {
        if (!auth.isAuthenticated()) {
            log.warn("Chartink webhook received but Kite not authenticated — dropping {}", symbol);
            return Result.NOT_READY;
        }
        rolloverIfNewDay();
        String key = symbol.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) return Result.UNKNOWN_SYMBOL;

        // Atomic gate — one fire per (day, symbol) even across concurrent webhook posts.
        // Seeded from DB in seedFiredTodayFromDb() so this survives restarts.
        if (!firedToday.add(key)) {
            log.info("[CHARTINK] {} already fired today, skipping duplicate from alert {}.", key, alertName);
            return Result.DUPLICATE;
        }

        String colStr = formatColumns(columns);
        String reason = "Chartink alert '" + alertName + "' @ " + triggerPrice
                + (colStr.isEmpty() ? "" : " [" + colStr + "]");
        SignalMeta meta = new SignalMeta(
                alertName,
                strOrNull(columns, "sector"),
                strOrNull(columns, "industry"),
                columnsToJson(columns));
        boolean ok = orders.placeSignalOrderWithBracket(key, "BUY", STRATEGY,
                reason, triggerPrice, quantity, meta);
        if (!ok) {
            // Roll back so a later legitimate retry can go through.
            firedToday.remove(key);
            return Result.REJECTED;
        }
        return Result.PLACED;
    }

    /** Backward-compat overload — used only in tests / direct callers. */
    public Result fire(String symbol, String alertName, double triggerPrice, int quantity) {
        return fire(symbol, alertName, triggerPrice, quantity, java.util.Map.of());
    }

    /**
     * Render the per-row columns map ({@code symbol=100.1, industry=100.2, …})
     * into a compact single-line string suitable for logs, DB audit and the
     * Telegram confirmation. Skips null values, preserves insertion order.
     */
    private static String formatColumns(Map<String, Object> columns) {
        if (columns == null || columns.isEmpty()) return "";
        return columns.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /** Case-insensitive lookup that returns a trimmed string, or {@code null}. */
    private static String strOrNull(Map<String, Object> columns, String key) {
        if (columns == null) return null;
        for (var e : columns.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                Object v = e.getValue();
                if (v == null) return null;
                String s = String.valueOf(v).trim();
                return s.isEmpty() ? null : s;
            }
        }
        return null;
    }

    private static String columnsToJson(Map<String, Object> columns) {
        if (columns == null || columns.isEmpty()) return null;
        try { return JSON.writeValueAsString(columns); }
        catch (Exception e) { return null; }
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        LocalDate prev = firedDay.get();
        if (!today.equals(prev) && firedDay.compareAndSet(prev, today)) {
            firedToday.clear();
            log.info("Chartink fired-today set rolled over: {} → {}", prev, today);
        }
    }
}
