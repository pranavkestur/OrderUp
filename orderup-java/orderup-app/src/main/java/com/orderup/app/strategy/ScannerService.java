package com.orderup.app.strategy;

import com.orderup.auth.KiteAuthService;
import com.orderup.config.StrategyConfig;
import com.orderup.config.TradingProperties;
import com.orderup.config.WaceConfig;
import com.orderup.marketdata.Candle;
import com.orderup.marketdata.CandleCacheService;
import com.orderup.marketdata.HistoricalDataService;
import com.orderup.marketdata.InstrumentService;
import com.orderup.marketdata.MarketDataExecutor;
import com.orderup.orders.HoldingsService;
import com.orderup.orders.OrderService;
import com.orderup.orders.WatchlistCandidate;
import com.orderup.orders.WatchlistCandidateRepository;
import com.orderup.app.strategy.MultiIndicatorStrategy.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-timeframe, five-indicator scanner.
 *
 * <p><b>Strategy composition (AND, gated):</b></p>
 * <ol>
 *   <li>Evaluate the <b>DAILY</b> strategy. If it does not fire, the symbol is
 *       ignored — no hourly call, nothing persisted.</li>
 *   <li>If daily fires, upsert the symbol into the {@code WATCHLIST_CANDIDATE}
 *       table for today (with the daily reason).</li>
 *   <li>Evaluate the <b>HOURLY</b> strategy. It must fire on the <i>same side</i>
 *       (BUY-BUY or SELL-SELL) as daily. Otherwise the symbol stays on the
 *       watchlist untouched — no order, no Telegram.</li>
 *   <li>When both agree, place a single order (qty=1) with a combined reason
 *       string and mark the watchlist row {@code triggered=true}. The symbol is
 *       then locked out of further orders for the rest of the IST day.</li>
 * </ol>
 */
@Service
public class ScannerService {

    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Fixed quantity per confirmed order — deliberately hardcoded, not read from strategy config. */
    private static final int ORDER_QUANTITY = 1;

    private final KiteAuthService auth;
    private final InstrumentService instruments;
    private final HistoricalDataService history;
    private final CandleCacheService candleCache;
    private final TradingProperties trading;
    private final OrderService orders;
    private final HoldingsService holdings;
    private final WatchlistCandidateRepository watchlistRepo;
    private final MarketDataExecutor executor;

    /** Thread-safe: parallel scans hit this from multiple worker threads. */
    private final Set<String> symbolFiredToday = ConcurrentHashMap.newKeySet();
    private volatile LocalDate lastFiredDay = LocalDate.MIN;
    private volatile boolean paused = false;

    public ScannerService(KiteAuthService auth, InstrumentService instruments,
                          HistoricalDataService history, CandleCacheService candleCache,
                          TradingProperties trading,
                          OrderService orders, HoldingsService holdings,
                          WatchlistCandidateRepository watchlistRepo,
                          MarketDataExecutor executor) {
        this.auth = auth;
        this.instruments = instruments;
        this.history = history;
        this.candleCache = candleCache;
        this.trading = trading;
        this.orders = orders;
        this.holdings = holdings;
        this.watchlistRepo = watchlistRepo;
        this.executor = executor;
    }

    public boolean isPaused() { return paused; }
    public void pause() {
        paused = true;
        log.info("Scanner PAUSED via API - any in-flight scan will abort at the next symbol.");
    }
    public void resume() { paused = false; log.info("Scanner RESUMED via API"); }

    private final java.util.concurrent.atomic.AtomicBoolean scanInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    public boolean isScanning() { return scanInFlight.get(); }

    /** Separate inflight guard so a slow legacy scan doesn't skip WACE ticks and vice versa. */
    private final java.util.concurrent.atomic.AtomicBoolean waceInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    public boolean isWaceScanning() { return waceInFlight.get(); }

    public void scanOnce() { scanOnce(false); }

    /**
     * @param force if {@code true}, run even when paused (used by the manual "Scan now"
     *              button in the UI so the user gets feedback instead of a silent no-op).
     */
    public void scanOnce(boolean force) {
        if (paused && !force) { log.info("Scan skipped - scanner is paused."); return; }
        if (paused) log.info("Manual scan requested while paused - running anyway.");
        if (!auth.isAuthenticated()) {
            log.warn("Skipping scan - Kite session not authenticated. Visit /kite/login.");
            return;
        }
        if (!scanInFlight.compareAndSet(false, true)) {
            log.info("Scan already in flight - skipping overlapping request.");
            return;
        }
        try {
            resetIfNewDay();
            holdings.refresh();

            var watchlist = instruments.watchlist();
            log.info("Scanning {} symbols (daily-gated, hourly-confirmed) via parallel pool...",
                    watchlist.size());

            AtomicInteger matched = new AtomicInteger();
            List<Callable<Boolean>> tasks = new ArrayList<>(watchlist.size());
            for (String symbol : watchlist) {
                tasks.add(() -> {
                    if (paused) return false;
                    try { return scanSymbol(symbol); }
                    catch (Exception e) { log.warn("Scan error for {}: {}", symbol, e.getMessage()); return false; }
                });
            }
            try {
                List<Future<Boolean>> futs = executor.pool().invokeAll(tasks);
                for (Future<Boolean> f : futs) {
                    try { if (Boolean.TRUE.equals(f.get())) matched.incrementAndGet(); }
                    catch (Exception ignore) {}
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("Scan complete. {} symbols matched daily+hourly criteria.", matched.get());
        } finally {
            scanInFlight.set(false);
        }
    }

    // -------------------------------------------------------------------------
    //  WACE-only scan (5-min cadence). Runs independently of the legacy 15-min
    //  scanOnce() flow. Only invokes tryWaceFlow — no DAILY_MULTI/HOURLY_MULTI
    //  fallback here. The per-symbol daily lockout (symbolFiredToday) is shared
    //  with the legacy path, so a symbol can't be double-fired across the two.
    // -------------------------------------------------------------------------
    public void scanWaceOnce() { scanWaceOnce(false); }

    public void scanWaceOnce(boolean force) {
        if (paused && !force) { log.info("WACE scan skipped - scanner is paused."); return; }
        if (paused) log.info("Manual WACE scan requested while paused - running anyway.");
        if (!auth.isAuthenticated()) {
            log.warn("Skipping WACE scan - Kite session not authenticated. Visit /kite/login.");
            return;
        }
        if (!waceInFlight.compareAndSet(false, true)) {
            log.info("WACE scan already in flight - skipping overlapping request.");
            return;
        }
        try {
            resetIfNewDay();
            // Note: we deliberately do NOT refresh holdings on every 5-min WACE tick
            // (the 15-min legacy scan already does it). Cheap to reuse the cached view.

            var watchlist = instruments.watchlist();
            log.info("WACE scan: {} symbols (5-min cadence, cached candles, parallel pool)...",
                    watchlist.size());

            AtomicInteger matched = new AtomicInteger();
            List<Callable<Boolean>> tasks = new ArrayList<>(watchlist.size());
            for (String symbol : watchlist) {
                tasks.add(() -> {
                    if (paused) return false;
                    Long token = instruments.tokenFor(symbol);
                    if (token == null) return false;
                    if (symbolFiredToday.contains(symbol)) return false;
                    try { return tryWaceFlow(symbol, token); }
                    catch (Exception e) {
                        log.warn("WACE scan error for {}: {}", symbol, e.getMessage());
                        return false;
                    }
                });
            }
            try {
                List<Future<Boolean>> futs = executor.pool().invokeAll(tasks);
                for (Future<Boolean> f : futs) {
                    try { if (Boolean.TRUE.equals(f.get())) matched.incrementAndGet(); }
                    catch (Exception ignore) {}
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            log.info("WACE scan complete. {} symbols fired.", matched.get());
        } finally {
            waceInFlight.set(false);
        }
    }

    private boolean scanSymbol(String symbol) {
        Long token = instruments.tokenFor(symbol);
        if (token == null) {
            log.debug("No instrument token for {}", symbol);
            return false;
        }
        if (symbolFiredToday.contains(symbol)) {
            log.debug("Skip {} - already fired today.", symbol);
            return false;
        }

        // --- 0. WACE (first preference) --------------------------------------
        // WACE runs on its own daily-gate + hourly-confirm pipeline, independent
        // of DAILY_MULTI/HOURLY_MULTI. If WACE places the order, we're done and
        // the symbol is locked out for the rest of the IST day.
        if (tryWaceFlow(symbol, token)) return true;
        if (paused) return false;

        StrategyConfig daily  = trading.strategies().daily();
        StrategyConfig hourly = trading.strategies().hourly();

        // --- 1. DAILY GATE ---------------------------------------------------
        if (daily == null || !daily.enabled()) {
            log.debug("Daily strategy disabled — skipping {}", symbol);
            return false;
        }
        var dailyResult = evaluateStrategy(symbol, token, daily);
        if (dailyResult == null || dailyResult.signal() == Signal.NONE) return false;
        Signal dailySide = dailyResult.signal();
        log.info("[DAILY-QUALIFIED] {} {} — {}", symbol, dailySide, dailyResult.reason());

        // Persist candidacy (idempotent per IST day + side).
        upsertWatchlistCandidate(symbol, dailySide.name(), daily.name(), dailyResult.reason());

        if (paused) return false;

        // --- 2. HOURLY CONFIRMATION ------------------------------------------
        if (hourly == null || !hourly.enabled()) {
            log.debug("Hourly strategy disabled — {} stays on watchlist unconfirmed.", symbol);
            return false;
        }
        var hourlyResult = evaluateStrategy(symbol, token, hourly);
        if (hourlyResult == null || hourlyResult.signal() == Signal.NONE) {
            log.info("[WATCHLIST] {} {} daily-qualified, awaiting hourly confirmation.",
                    symbol, dailySide);
            return false;
        }
        if (hourlyResult.signal() != dailySide) {
            log.info("[WATCHLIST] {} sides disagree: daily={} hourly={} — no order.",
                    symbol, dailySide, hourlyResult.signal());
            return false;
        }

        // --- 3. BOTH AGREE → FIRE --------------------------------------------
        // Atomic gate: only the first worker to add(symbol) proceeds to place
        // the order. Prevents double-fire under parallel scans / concurrent WACE.
        if (!symbolFiredToday.add(symbol)) {
            log.debug("Fire race lost for {} — already claimed.", symbol);
            return false;
        }
        var candles = candleCache.get(token, hourly.interval(), hourly.historyDays());
        double lastClose = candles.get(candles.size() - 1).close();
        String combinedLabel = daily.name() + "+" + hourly.name();
        String combinedReason = "Daily: " + dailyResult.reason()
                              + " | Hourly: " + hourlyResult.reason();
        log.info("[SIGNAL] [source=LEGACY] [{}] {} - {} - {}",
                combinedLabel, symbol, dailySide, combinedReason);

        boolean placed = orders.placeSignalOrder(
                symbol, dailySide.name(), combinedLabel,
                combinedReason, lastClose, ORDER_QUANTITY);
        if (placed) {
            markWatchlistTriggered(symbol, dailySide.name(), hourlyResult.reason());
        } else {
            // Rollback the fire lock so another tick can retry.
            symbolFiredToday.remove(symbol);
        }
        return placed;
    }

    /**
     * Evaluate a single strategy. Returns {@code null} only if we don't have enough
     * candles to run the indicators; a NONE {@link MultiIndicatorStrategy.Result}
     * means "evaluated, no signal".
     */
    private MultiIndicatorStrategy.Result evaluateStrategy(String symbol, long token, StrategyConfig cfg) {
        // Legacy path also routes through the rolling candle cache now, so the
        // 5-min tick only pays for delta fetches after the first pass of the day.
        List<Candle> candles = candleCache.get(token, cfg.interval(), cfg.historyDays());
        int minBars = Math.max(cfg.cciPeriod(), cfg.wrPeriod())
                    + Math.max(cfg.macdSlow() + cfg.macdSignal(), 2 * cfg.adxPeriod());
        if (candles.size() < minBars) {
            log.debug("[{}] Not enough {} candles for {} ({} < {})",
                    cfg.name(), cfg.interval(), symbol, candles.size(), minBars);
            return null;
        }
        return MultiIndicatorStrategy.evaluate(candles, cfg);
    }

    // -------------------------------------------------------------------------
    //  WACE flow (mirrors the DAILY_MULTI → HOURLY_MULTI pipeline but on
    //  WaceStrategy + WaceConfig). BUY-only. Returns true if an order was
    //  placed (so the caller stops before invoking the fallback flow).
    // -------------------------------------------------------------------------
    private boolean tryWaceFlow(String symbol, long token) {
        WaceConfig waceDaily  = trading.strategies().waceDaily();
        WaceConfig waceHourly = trading.strategies().waceHourly();

        if (waceDaily == null || !waceDaily.enabled()) {
            log.debug("WACE daily disabled — skipping {}", symbol);
            return false;
        }

        var dailyResult = evaluateWace(symbol, token, waceDaily);
        if (dailyResult == null || dailyResult.signal() == Signal.NONE) return false;
        Signal dailySide = dailyResult.signal();
        log.info("[WACE-DAILY-QUALIFIED] {} {} — {}", symbol, dailySide, dailyResult.reason());

        upsertWatchlistCandidate(symbol, dailySide.name(), waceDaily.name(), dailyResult.reason());

        if (paused) return false;

        if (waceHourly == null || !waceHourly.enabled()) {
            log.debug("WACE hourly disabled — {} stays on watchlist unconfirmed.", symbol);
            return false;
        }
        var hourlyResult = evaluateWace(symbol, token, waceHourly);
        if (hourlyResult == null || hourlyResult.signal() == Signal.NONE) {
            log.info("[WATCHLIST] {} {} WACE-daily-qualified, awaiting WACE-hourly confirmation.",
                    symbol, dailySide);
            return false;
        }
        if (hourlyResult.signal() != dailySide) {
            log.info("[WATCHLIST] {} WACE sides disagree: daily={} hourly={} — no order.",
                    symbol, dailySide, hourlyResult.signal());
            return false;
        }

        // Atomic fire gate — see legacy path for rationale.
        if (!symbolFiredToday.add(symbol)) {
            log.debug("WACE fire race lost for {} — already claimed.", symbol);
            return false;
        }
        var candles = candleCache.get(token, waceHourly.interval(), waceHourly.historyDays());
        double lastClose = candles.get(candles.size() - 1).close();
        String combinedLabel = waceDaily.name() + "+" + waceHourly.name();
        String combinedReason = "Daily: " + dailyResult.reason()
                              + " | Hourly: " + hourlyResult.reason();
        log.info("[SIGNAL] [source=WACE] [{}] {} - {} - {}",
                combinedLabel, symbol, dailySide, combinedReason);

        boolean placed = orders.placeSignalOrder(
                symbol, dailySide.name(), combinedLabel,
                combinedReason, lastClose, ORDER_QUANTITY);
        if (placed) {
            markWatchlistTriggered(symbol, dailySide.name(), hourlyResult.reason());
        } else {
            symbolFiredToday.remove(symbol);
        }
        return placed;
    }

    /**
     * Evaluate WACE on one timeframe. Returns {@code null} if not enough candles
     * are available; otherwise a {@link MultiIndicatorStrategy.Result} (which may
     * be {@link MultiIndicatorStrategy#NONE NONE}).
     */
    private MultiIndicatorStrategy.Result evaluateWace(String symbol, long token, WaceConfig cfg) {
        // WACE uses the rolling candle cache: first hit of the IST day = full
        // fetch; every subsequent hit = delta fetch (typically 1–2 bars) that
        // refreshes the still-forming bar. Indicator math re-runs from scratch
        // on the returned window and is bit-identical to a fresh full fetch.
        List<Candle> candles = candleCache.get(token, cfg.interval(), cfg.historyDays());
        int minBars = Math.max(
                Math.max(cfg.cciPeriod(), cfg.wrPeriod()),
                Math.max(cfg.emaSlow(), 2 * cfg.adxPeriod() + 1))
                + Math.max(cfg.prevHighLookback(), 1);
        if (candles.size() < minBars) {
            log.debug("[{}] Not enough {} candles for {} ({} < {})",
                    cfg.name(), cfg.interval(), symbol, candles.size(), minBars);
            return null;
        }
        return WaceStrategy.evaluate(candles, cfg);
    }

    /**
     * Insert a watchlist row the first time this (symbol, side) qualifies today.
     * If a row already exists for the current IST day, do nothing (avoids row
     * churn across the 5-minute rescans).
     */
    private void upsertWatchlistCandidate(String symbol, String side, String indicator, String reason) {
        try {
            LocalDate today = LocalDate.now(IST);
            Optional<WatchlistCandidate> latest = watchlistRepo.latest(symbol, side);
            if (latest.isPresent()) {
                LocalDate day = latest.get().getAddedAt().atZone(IST).toLocalDate();
                if (day.equals(today)) return; // already on today's watchlist
            }
            watchlistRepo.save(new WatchlistCandidate(
                    Instant.now(), symbol, side, indicator, reason));
            log.debug("Added {} {} to watchlist ({})", symbol, side, indicator);
        } catch (Throwable t) {
            log.warn("Failed to upsert watchlist candidate for {}: {}", symbol, t.getMessage());
        }
    }

    /** Flip today's watchlist row for (symbol, side) to triggered=true. */
    private void markWatchlistTriggered(String symbol, String side, String hourlyReason) {
        try {
            LocalDate today = LocalDate.now(IST);
            Optional<WatchlistCandidate> latest = watchlistRepo.latest(symbol, side);
            if (latest.isEmpty()) return;
            WatchlistCandidate wc = latest.get();
            if (!wc.getAddedAt().atZone(IST).toLocalDate().equals(today)) return;
            if (wc.isTriggered()) return;
            wc.markTriggered(Instant.now(), hourlyReason);
            watchlistRepo.save(wc);
        } catch (Throwable t) {
            log.warn("Failed to mark watchlist triggered for {}: {}", symbol, t.getMessage());
        }
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(lastFiredDay)) {
            symbolFiredToday.clear();
            lastFiredDay = today;
            log.info("New trading day - signal dedupe map cleared.");
        }
    }
}
