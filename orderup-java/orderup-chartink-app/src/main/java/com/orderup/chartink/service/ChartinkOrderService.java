package com.orderup.chartink.service;

import com.orderup.auth.KiteAuthService;
import com.orderup.marketdata.InstrumentService;
import com.orderup.notify.TelegramNotifier;
import com.orderup.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-symbol per-day de-dup wrapper around {@link OrderService}. Prevents
 * duplicate fires if Chartink retries the webhook or fires the same scan
 * multiple times in a session. Mirrors the {@code symbolFiredToday} pattern
 * used by orderup-app's scanner.
 *
 * <p>Also guards against pre-auth webhooks: if Kite auth hasn't completed OR
 * the instrument list hasn't populated the token map, the caller sees
 * {@link Result#NOT_READY} so the HTTP layer can return 503.
 */
@Service
public class ChartinkOrderService {

    private static final Logger log = LoggerFactory.getLogger(ChartinkOrderService.class);
    /** IST for the "one order per symbol per trading day" gate. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderService orders;
    private final InstrumentService instruments;
    private final KiteAuthService auth;
    private final TelegramNotifier notifier;

    /** Set of "SYMBOL" that already fired today, cleared on IST day rollover. */
    private final Set<String> firedToday = ConcurrentHashMap.newKeySet();
    private final AtomicReference<LocalDate> firedDay = new AtomicReference<>(LocalDate.now(IST));

    public ChartinkOrderService(OrderService orders, InstrumentService instruments,
                                KiteAuthService auth, TelegramNotifier notifier) {
        this.orders = orders;
        this.instruments = instruments;
        this.auth = auth;
        this.notifier = notifier;
    }

    public enum Result { PLACED, DUPLICATE, UNKNOWN_SYMBOL, NOT_READY, REJECTED }

    public boolean isReady() {
        return auth.isAuthenticated() && !instruments.watchlist().isEmpty();
    }

    /**
     * Fire one BUY order for {@code symbol}. Called by the webhook controller
     * for every comma-separated entry in the Chartink payload.
     */
    public Result fire(String symbol, String alertName, double triggerPrice, int quantity) {
        if (!auth.isAuthenticated()) {
            log.warn("Chartink webhook received but Kite not authenticated — dropping {}", symbol);
            return Result.NOT_READY;
        }
        rolloverIfNewDay();
        String key = symbol.trim().toUpperCase();
        if (key.isEmpty()) return Result.UNKNOWN_SYMBOL;

        Long token = instruments.tokenFor(key);
        if (token == null) {
            log.warn("[CHARTINK] {} - unknown NSE EQ symbol (no instrument token), skipping.", key);
            notifier.send("⚠️ [CHARTINK] Unknown symbol " + key + " (alert " + alertName + ") — skipped.");
            return Result.UNKNOWN_SYMBOL;
        }

        // Atomic gate — one fire per (day, symbol) even across concurrent webhook posts.
        if (!firedToday.add(key)) {
            log.info("[CHARTINK] {} already fired today, skipping duplicate from alert {}.", key, alertName);
            return Result.DUPLICATE;
        }

        boolean ok = orders.placeSignalOrder(key, "BUY", "CHARTINK",
                "Chartink alert '" + alertName + "' @ " + triggerPrice, triggerPrice, quantity);
        if (!ok) {
            // Roll back so a later legitimate retry can go through.
            firedToday.remove(key);
            return Result.REJECTED;
        }
        return Result.PLACED;
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

