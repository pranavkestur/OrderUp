package com.orderup.marketdata;

import com.orderup.auth.KiteAuthService;
import com.orderup.config.TradingProperties;
import com.orderup.config.WaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-warms the {@link CandleCacheService} on application startup. If a disk
 * snapshot was already loaded by {@link CandleCacheSnapshot}, this pass mostly
 * turns into cheap delta-refreshes (typically 1–2 candles per entry). Cold
 * start (no snapshot) does full-window fetches for every symbol.
 *
 * <p>Fetches run through {@link MarketDataExecutor}; global throughput is capped
 * by {@link KiteRateLimiter}. Non-blocking to Spring boot-up (daemon thread).
 */
@Component
public class CandleCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(CandleCacheWarmer.class);

    private static final long AUTH_POLL_MILLIS = 30_000L;
    private static final long AUTH_TIMEOUT_MILLIS = 30L * 60_000L;

    private final KiteAuthService auth;
    private final InstrumentService instruments;
    private final CandleCacheService cache;
    private final TradingProperties trading;
    private final MarketDataExecutor executor;

    public CandleCacheWarmer(KiteAuthService auth,
                             InstrumentService instruments,
                             CandleCacheService cache,
                             TradingProperties trading,
                             MarketDataExecutor executor) {
        this.auth = auth;
        this.instruments = instruments;
        this.cache = cache;
        this.trading = trading;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(this::warm, "candle-cache-warmer");
        t.setDaemon(true);
        t.start();
    }

    public void warm() {
        if (!waitForAuth()) {
            log.warn("Candle cache warm-up skipped — Kite not authenticated within timeout.");
            return;
        }

        // Wait briefly for InstrumentService to load tokens after auth completes
        // (they race on the ApplicationReadyEvent bus).
        waitForInstruments();

        WaceConfig waceDaily  = trading.strategies().waceDaily();
        WaceConfig waceHourly = trading.strategies().waceHourly();
        Set<String> watchlist = instruments.watchlist();

        log.info("Candle cache warm-up starting: {} symbols × 2 intervals via {} workers @ {} rps",
                watchlist.size(),
                trading.marketData().workerThreads(),
                trading.marketData().rateLimitPerSec());

        long startedAt = System.currentTimeMillis();
        AtomicInteger warmed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>(watchlist.size());

        for (String symbol : watchlist) {
            tasks.add(() -> {
                Long token = instruments.tokenFor(symbol);
                if (token == null) { skipped.incrementAndGet(); return null; }
                try {
                    if (waceDaily != null && waceDaily.enabled()) {
                        cache.get(token, waceDaily.interval(), waceDaily.historyDays());
                    }
                    if (waceHourly != null && waceHourly.enabled()) {
                        cache.get(token, waceHourly.interval(), waceHourly.historyDays());
                    }
                    warmed.incrementAndGet();
                } catch (Exception e) {
                    log.debug("Warm-up failed for {}: {}", symbol, e.getMessage());
                }
                return null;
            });
        }

        try {
            List<Future<Void>> futs = executor.pool().invokeAll(tasks);
            for (Future<Void> f : futs) { try { f.get(); } catch (Exception ignore) {} }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.info("Candle cache warm-up interrupted.");
            return;
        }

        long secs = (System.currentTimeMillis() - startedAt) / 1000L;
        log.info("Candle cache warm-up complete: warmed={} skipped={} elapsed={}s",
                warmed.get(), skipped.get(), secs);
    }

    private boolean waitForAuth() {
        if (auth.isAuthenticated()) return true;
        log.info("Candle cache warmer waiting for Kite authentication...");
        long deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(AUTH_POLL_MILLIS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            if (auth.isAuthenticated()) return true;
        }
        return false;
    }

    /**
     * After auth completes, the InstrumentService still needs a moment to load
     * ~10k NSE EQ symbols from Kite. Poll until (a) the watchlist is non-empty
     * (in all-nse-eq mode it's populated by refreshInstruments post-auth) and
     * (b) a canary token lookup returns non-null. Wait up to ~60s.
     */
    private void waitForInstruments() {
        for (int i = 0; i < 60; i++) {
            Set<String> wl = instruments.watchlist();
            if (!wl.isEmpty()) {
                String canary = wl.iterator().next();
                if (instruments.tokenFor(canary) != null) return;
            }
            try { Thread.sleep(1000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("Instrument tokens still not loaded after 60s; warm-up may skip many symbols.");
    }
}

