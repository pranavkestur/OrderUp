package com.orderup.marketdata;

import com.orderup.config.TradingProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-instrument tick-size lookup for the exchange configured in
 * {@link TradingProperties}. Kite requires all GTT trigger prices to be exact
 * multiples of the instrument's {@code tick_size}, and tick varies per scrip
 * (0.05 for most NSE EQ, but 0.10 for high-priced names like M&M, 5.00 for
 * some niche scrips like POWERINDIA). Guessing wrong yields a 400 rejection
 * <i>after</i> the primary BUY has already filled, leaving the position
 * unprotected.
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>Zero boot cost.</b> {@code orderup-chartink-app} intentionally skips
 *       the NSE EQ dump at startup ({@code allNseEq=false}). We honour that by
 *       loading the dump <b>lazily</b> — only when the first bracket order
 *       needs a tick lookup.</li>
 *   <li><b>Single fetch per session.</b> The dump is ~2k rows and ~2s over the
 *       wire; we do it exactly once and cache the {@code symbol -> tick}
 *       mapping in a {@link ConcurrentHashMap}.</li>
 *   <li><b>Safe fallback.</b> If Kite is unreachable or a symbol is missing
 *       from the dump, we return the {@link #DEFAULT_TICK NSE default of 0.05}.
 *       The caller can still snap to this and rely on
 *       {@code OrderService}'s Kite-error-parsing retry as a last-resort
 *       backstop.</li>
 * </ul>
 */
@Service
public class TickSizeService {

    private static final Logger log = LoggerFactory.getLogger(TickSizeService.class);

    /** Fallback tick when the dump is empty or the symbol is unknown. */
    public static final double DEFAULT_TICK = 0.05;

    private final KiteConnect kite;
    private final TradingProperties trading;

    /** tradingsymbol (UPPER) -> tick_size. Populated on first miss. */
    private final ConcurrentHashMap<String, Double> tickBySymbol = new ConcurrentHashMap<>();
    /** Ensures we don't hammer /instruments on every miss for unknown symbols. */
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public TickSizeService(KiteConnect kite, TradingProperties trading) {
        this.kite = kite;
        this.trading = trading;
    }

    /**
     * Returns the tick size for {@code symbol}, loading the exchange's
     * instrument dump on the first call if needed. Never throws; on any error
     * (Kite unreachable, symbol not found, dump empty) returns {@link #DEFAULT_TICK}
     * and lets the caller's retry backstop handle the rare edge case.
     */
    public double tickFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return DEFAULT_TICK;
        String key = symbol.toUpperCase(Locale.ROOT);

        Double cached = tickBySymbol.get(key);
        if (cached != null) return cached;

        // Miss — trigger a one-time load. Double-checked so only one thread
        // pays the network cost even if two brackets fire concurrently.
        if (loaded.compareAndSet(false, true)) {
            loadDump();
        } else {
            // Another thread is (or was) loading; give the cache a chance.
            // No need to block — worst case we return DEFAULT_TICK below and
            // the OrderService retry catches it.
        }

        Double afterLoad = tickBySymbol.get(key);
        if (afterLoad != null) return afterLoad;

        log.warn("Tick size unknown for {} — using default {}", key, DEFAULT_TICK);
        return DEFAULT_TICK;
    }

    private void loadDump() {
        long t0 = System.currentTimeMillis();
        try {
            List<Instrument> all = kite.getInstruments(trading.exchange());
            int count = 0;
            for (Instrument i : all) {
                if (!"EQ".equalsIgnoreCase(i.instrument_type)) continue;
                if (i.tick_size <= 0) continue;
                tickBySymbol.put(i.tradingsymbol.toUpperCase(Locale.ROOT), i.tick_size);
                count++;
            }
            log.info("TickSizeService: cached tick sizes for {} {} EQ instruments in {} ms",
                    count, trading.exchange(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            // Allow a future retry if this was a transient auth/network failure.
            loaded.set(false);
            log.warn("TickSizeService: failed to load {} instrument dump — will retry on next miss ({})",
                    trading.exchange(), t.getMessage());
        }
    }
}

