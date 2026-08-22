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

    public HoldingsService(KiteConnect kite, KiteAuthService auth) {
        this.kite = kite;
        this.auth = auth;
    }

    public synchronized void refresh() {
        if (!auth.isAuthenticated()) return;
        try {
            List<Holding> list = kite.getHoldings();
            bySymbol.clear();
            for (Holding h : list) {
                if (h.tradingSymbol == null) continue;
                int qty = h.quantity - h.usedQuantity;
                if (qty <= 0) continue;
                bySymbol.put(h.tradingSymbol.toUpperCase(Locale.ROOT),
                        new Snapshot(qty, h.averagePrice));
            }
            lastRefreshMs = System.currentTimeMillis();
            log.info("Holdings refreshed — {} symbols in portfolio", bySymbol.size());
        } catch (Throwable e) {
            log.warn("Holdings refresh failed: {}", e.getMessage());
        }
    }

    /** Refresh only if data is older than {@code maxAgeMs}. Cheap for the UI. */
    public void refreshIfStale(long maxAgeMs) {
        if (System.currentTimeMillis() - lastRefreshMs > maxAgeMs) refresh();
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

