package com.orderup.orders;

import com.orderup.auth.KiteAuthService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Holding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the user's CNC holdings so we can gate SELL signals on actual ownership
 * and drive the "Open positions" view from the real portfolio (not from our own
 * OrderRecords, which can drift when the user trades outside OrderUp).
 * Refreshed once before every scan tick and on-demand from the UI.
 */
@Service
public class HoldingsService {

    private static final Logger log = LoggerFactory.getLogger(HoldingsService.class);

    private final KiteConnect kite;
    private final KiteAuthService auth;
    private final Map<String, Snapshot> bySymbol = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs = 0L;
    /**
     * Timestamp of the last {@link #refresh()} call where {@code kite.getHoldings()}
     * actually returned a value (even an empty list). Distinguishes "user has
     * zero holdings" (safe to trust an empty {@link #snapshot()}) from "we
     * never got a good answer from Kite" (empty {@link #snapshot()} is
     * meaningless and MUST NOT be treated as authoritative — the reconciler
     * would otherwise synthesise phantom SELLs for every persisted BUY).
     * Zero until the first successful call.
     */
    private volatile long lastSuccessfulRefreshMs = 0L;

    public HoldingsService(KiteConnect kite, KiteAuthService auth) {
        this.kite = kite;
        this.auth = auth;
    }

    public synchronized void refresh() {
        if (!auth.isAuthenticated()) return;
        try {
            List<Holding> list = kite.getHoldings();
            // Build the new snapshot in a temp map so a partial parse never
            // exposes a half-populated view to concurrent readers. Only
            // swap once the whole payload has been consumed.
            Map<String, Snapshot> next = new HashMap<>();
            for (Holding h : list) {
                if (h.tradingSymbol == null) continue;
                // Effective ownership must include T+1 quantities: a CNC BUY
                // from the previous trading day is financially settled and can
                // be sold, but Kite reports it as {quantity=0, t1Quantity=N}
                // until it lands in the demat account the next day. Ignoring
                // t1Quantity here causes the OCO reconciler to treat a
                // just-bought position as "not held" the very next morning
                // and synthesise a phantom SL_APPROX SELL against it. This
                // is exactly the failure mode that keeps closing BHEL / any
                // Aug 27 BUY around 10:20 IST on Aug 28.
                int qty = h.quantity + h.t1Quantity - h.usedQuantity;
                if (qty <= 0) continue;
                // Average price is meaningful only when `quantity` is non-zero.
                // For pure T+1 holdings Kite still reports the buy avg on the
                // holding, so pass it through as-is; downstream code just uses
                // it for the Open-Positions display.
                next.put(h.tradingSymbol.toUpperCase(Locale.ROOT),
                        new Snapshot(qty, h.averagePrice));
            }
            bySymbol.clear();
            bySymbol.putAll(next);
            long now = System.currentTimeMillis();
            lastRefreshMs = now;
            lastSuccessfulRefreshMs = now;
            log.info("Holdings refreshed — {} symbols in portfolio", bySymbol.size());
        } catch (Throwable e) {
            // Deliberately do NOT touch bySymbol on failure. A stale cache is
            // strictly safer than an empty one for the reconciler.
            log.warn("Holdings refresh failed: {}", e.getMessage());
        }
    }

    /** Refresh only if data is older than {@code maxAgeMs}. Cheap for the UI. */
    public void refreshIfStale(long maxAgeMs) {
        if (System.currentTimeMillis() - lastRefreshMs > maxAgeMs) refresh();
    }

    /**
     * True once {@link #refresh()} has completed at least one successful call
     * to {@code kite.getHoldings()}. Consumers that make destructive decisions
     * based on {@link #snapshot()} being empty (e.g. the OCO reconciler) MUST
     * check this first and bail out otherwise.
     */
    public boolean hasEverLoaded() {
        return lastSuccessfulRefreshMs > 0L;
    }

    public int quantity(String symbol) {
        Snapshot s = bySymbol.get(symbol.toUpperCase(Locale.ROOT));
        return s == null ? 0 : s.quantity;
    }

    public double avgPrice(String symbol) {
        Snapshot s = bySymbol.get(symbol.toUpperCase(Locale.ROOT));
        return s == null ? 0.0 : s.avgPrice;
    }

    /** Snapshot of held symbols (upper-case tradingsymbol → qty/avgPrice). */
    public Map<String, Snapshot> snapshot() {
        return Collections.unmodifiableMap(bySymbol);
    }

    public record Snapshot(int quantity, double avgPrice) {}
}

