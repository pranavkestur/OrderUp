package com.orderup.pnl;

import com.orderup.marketdata.InstrumentService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches daily OHLC candles from Kite for a symbol over a fixed window,
 * used to power the "post-entry price journey" drawer on the P&L dashboard.
 *
 * <h3>Two-tier cache</h3>
 * <ol>
 *   <li><b>In-memory TTL cache</b> — repeated clicks on the same symbol
 *       within {@value #CACHE_TTL_MINUTES} minutes hit RAM.</li>
 *   <li><b>Persistent {@link PriceCacheEntry} table</b> — daily candles are
 *       immutable once the trading day closes, so we upsert them and reuse
 *       across restarts. This makes the "price journey" for old closed
 *       trades load instantly with zero Kite calls, and lets the nightly
 *       {@link ExitPerformanceService} pre-warm the cache for every symbol
 *       we've traded (see {@link #snapshot}).</li>
 * </ol>
 *
 * <p>Bypasses {@code HistoricalDataService} because that service is disabled
 * (via {@code trading.market-data.enabled=false}) in the Chartink app.
 */
@Service
public class PriceHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int CACHE_TTL_MINUTES = 10;

    private final KiteConnect kite;
    private final InstrumentService instruments;
    private final BenchmarkService benchmarks;
    private final PriceCacheEntryRepository cacheRepo;
    private final Map<String, Entry> ramCache = new ConcurrentHashMap<>();

    public PriceHistoryService(KiteConnect kite,
                               InstrumentService instruments,
                               BenchmarkService benchmarks,
                               PriceCacheEntryRepository cacheRepo) {
        this.kite = kite;
        this.instruments = instruments;
        this.benchmarks = benchmarks;
        this.cacheRepo = cacheRepo;
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Legacy forward-only fetch: {@code tradingDays} of candles starting on
     * {@code fromDate}. Kept for backwards compatibility with any callers
     * that don't need pre-entry context.
     */
    public List<Candle> fetch(String symbol, LocalDate fromDate, int tradingDays) {
        return fetchWindow(symbol, fromDate, 0, tradingDays);
    }

    /**
     * Return candles spanning {@code daysBefore} trading days *before* the
     * anchor date up to {@code daysAfter} trading days *after* the anchor
     * date (inclusive of the anchor day itself, if it's a trading day).
     * Empty list on any failure. Never pads with synthetic points.
     */
    public List<Candle> fetchWindow(String symbol, LocalDate anchor,
                                    int daysBefore, int daysAfter) {
        if (symbol == null || symbol.isBlank() || anchor == null || daysAfter <= 0) return List.of();
        Long token = resolveEquityToken(symbol);
        if (token == null) {
            log.debug("PriceHistoryService: no instrument token for {}", symbol);
            return List.of();
        }
        String norm = symbol.toUpperCase(Locale.ROOT);
        return fetchByToken(norm, token, anchor, daysBefore, daysAfter);
    }

    /**
     * Fetch candles for a benchmark (e.g. {@code "NIFTY 50"}) over the same
     * window shape. Persisted alongside stock candles under a {@code ^}
     * prefix so the cache table stays uniform.
     */
    public List<Candle> fetchBenchmark(String benchName, LocalDate anchor,
                                       int daysBefore, int daysAfter) {
        if (benchName == null || benchName.isBlank() || anchor == null || daysAfter <= 0) return List.of();
        Long token = benchmarks.tokenFor(benchName);
        if (token == null) {
            log.debug("PriceHistoryService: no token for benchmark '{}'", benchName);
            return List.of();
        }
        return fetchByToken("^" + benchName.toUpperCase(Locale.ROOT), token, anchor, daysBefore, daysAfter);
    }

    /**
     * Pre-warm the persistent cache for {@code symbol}'s window around
     * {@code entryDate}. Called by {@link ExitPerformanceService} for every
     * closed-trade symbol during the nightly refresh so the dashboard loads
     * instantly the next morning. No-op / silent on failure.
     */
    public void snapshot(String symbol, LocalDate entryDate, int daysAfter) {
        try {
            fetchWindow(symbol, entryDate, 10, daysAfter);
        } catch (Throwable t) {
            log.debug("PriceHistoryService.snapshot({},{}) failed: {}", symbol, entryDate, t.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Core fetch: RAM cache → DB cache → Kite
    // ---------------------------------------------------------------------

    private List<Candle> fetchByToken(String cacheSymbol, long token,
                                      LocalDate anchor, int daysBefore, int daysAfter) {
        String key = cacheSymbol + "|" + anchor + "|" + daysBefore + "|" + daysAfter;
        Entry cached = ramCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestampMs) < CACHE_TTL_MINUTES * 60_000L) {
            return cached.candles;
        }

        // Compute a *calendar-day* envelope that comfortably covers the
        // requested trading-day counts. We over-shoot and trim by trading-day
        // count client-side; this handles NSE holidays naturally (Kite simply
        // omits non-trading days from the response).
        LocalDate windowStart = anchor.minusDays(calDays(daysBefore));
        LocalDate windowEnd   = anchor.plusDays(calDays(daysAfter));
        LocalDate today = LocalDate.now(IST);
        if (windowEnd.isAfter(today)) windowEnd = today;
        if (!windowEnd.isAfter(windowStart)) return List.of();

        // ---- Try DB first ----
        List<PriceCacheEntry> rows = cacheRepo.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
                cacheSymbol, windowStart, windowEnd);
        boolean dbFresh = isDbFresh(rows, windowEnd, today);

        if (!dbFresh) {
            // Only fetch the tail we're missing (from lastCached+1 → windowEnd)
            // to keep Kite calls small when we already have most of the window.
            LocalDate fetchFrom = rows.isEmpty() ? windowStart
                    : rows.get(rows.size() - 1).getTradeDate().plusDays(1);
            if (!fetchFrom.isAfter(windowEnd)) {
                List<PriceCacheEntry> fresh = fetchFromKite(cacheSymbol, token, fetchFrom, windowEnd);
                if (!fresh.isEmpty()) {
                    cacheRepo.saveAll(fresh);
                    rows = cacheRepo.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(
                            cacheSymbol, windowStart, windowEnd);
                }
            }
        }

        // Trim to the requested trading-day counts using the anchor as pivot.
        List<Candle> out = trimToTradingDays(rows, anchor, daysBefore, daysAfter);
        if (!out.isEmpty()) ramCache.put(key, new Entry(now, out));
        return out;
    }

    /**
     * DB rows are "fresh enough" if the latest cached date is on or after
     * the most recent completed trading day covered by the window. We treat
     * "yesterday or later" as fresh — the current day's candle is incomplete
     * during market hours, and the drawer is designed to work with T-1 data.
     */
    private boolean isDbFresh(List<PriceCacheEntry> rows,
                              LocalDate windowEnd, LocalDate today) {
        if (rows.isEmpty()) return false;
        LocalDate latest = rows.get(rows.size() - 1).getTradeDate();
        LocalDate needed = windowEnd.isBefore(today) ? windowEnd : today.minusDays(1);
        return !latest.isBefore(needed);
    }

    private List<PriceCacheEntry> fetchFromKite(String cacheSymbol, long token,
                                                LocalDate from, LocalDate to) {
        Instant fromT = from.atStartOfDay(IST).toInstant();
        // Kite errors on future to-dates; clamp to now.
        Instant toT   = to.plusDays(1).atStartOfDay(IST).toInstant();
        Instant nowT  = Instant.now();
        if (toT.isAfter(nowT)) toT = nowT;
        if (!toT.isAfter(fromT)) return List.of();
        try {
            HistoricalData hd = fetchWithRetry(Date.from(fromT), Date.from(toT),
                    String.valueOf(token), cacheSymbol);
            if (hd == null || hd.dataArrayList == null) return List.of();
            Instant now = Instant.now();
            List<PriceCacheEntry> out = new ArrayList<>();
            for (HistoricalData d : hd.dataArrayList) {
                if (d.timeStamp == null || d.timeStamp.length() < 10) continue;
                LocalDate date = LocalDate.parse(d.timeStamp.substring(0, 10));
                out.add(new PriceCacheEntry(cacheSymbol, date,
                        d.open, d.high, d.low, d.close, (long) d.volume, now));
            }
            return out;
        } catch (Throwable t) {
            log.warn("PriceHistoryService: Kite fetch failed for {} [{}→{}]: {}",
                    cacheSymbol, from, to, t.getMessage());
            return List.of();
        }
    }

    /**
     * Trim the raw candle list to {@code daysBefore + daysAfter} candles
     * pivoted at {@code anchor} — the anchor's own candle (if present) is
     * kept as the first "post-entry" day. Holidays / weekends drop out
     * naturally because Kite doesn't emit rows for non-trading days.
     */
    private static List<Candle> trimToTradingDays(List<PriceCacheEntry> rows,
                                                  LocalDate anchor,
                                                  int daysBefore, int daysAfter) {
        if (rows.isEmpty()) return List.of();
        int pivot = 0;
        while (pivot < rows.size() && rows.get(pivot).getTradeDate().isBefore(anchor)) pivot++;
        int start = Math.max(0, pivot - daysBefore);
        int end   = Math.min(rows.size(), pivot + daysAfter);
        List<Candle> out = new ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            PriceCacheEntry r = rows.get(i);
            boolean preEntry = r.getTradeDate().isBefore(anchor);
            out.add(new Candle(r.getTradeDate().toString(),
                    r.getOpen(), r.getHigh(), r.getLow(), r.getClose(), r.getVolume(),
                    preEntry));
        }
        return out;
    }

    private static int calDays(int tradingDays) {
        // ~7/5 ratio + a holiday cushion. 30 trading days → ≈ 50 calendar days.
        return tradingDays + (tradingDays / 10 + 1) * 5 + 4;
    }

    private Long resolveEquityToken(String symbol) {
        Long token = instruments.tokenFor(symbol);
        if (token != null) return token;
        // Chartink app boots with an empty instrument map — lazy-load once.
        synchronized (instruments) {
            token = instruments.tokenFor(symbol);
            if (token == null) {
                log.info("PriceHistoryService: instrument map empty, triggering one-time refresh for {}", symbol);
                instruments.refreshInstruments();
                token = instruments.tokenFor(symbol);
            }
        }
        return token;
    }

    /**
     * Small retry loop around {@code kite.getHistoricalData} — Kite occasionally
     * returns null / rate-limits under dashboard-refresh bursts.
     */
    private HistoricalData fetchWithRetry(Date fromDate, Date toDate, String token,
                                          String symbol) throws Throwable {
        Throwable lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HistoricalData hd = kite.getHistoricalData(fromDate, toDate, token, "day", false, false);
                if (hd != null && hd.dataArrayList != null && !hd.dataArrayList.isEmpty()) return hd;
                lastErr = new IllegalStateException("empty payload");
            } catch (Throwable t) {
                lastErr = t;
                log.debug("PriceHistoryService: attempt {} for {} failed: {}", attempt, symbol, t.getMessage());
            }
            try { Thread.sleep(250L * (attempt + 1)); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
        if (lastErr != null) throw lastErr;
        return null;
    }

    // ---------------------------------------------------------------------
    // Best-exit hindsight summary — powers the strip above the chart.
    // ---------------------------------------------------------------------

    /**
     * Compute the "would-have-been-best exit" story for a completed / open
     * trade. Answers the ONE question the drawer exists for: "when should I
     * have exited?" — in a few numbers we can render as a strip above the
     * chart.
     */
    public BestExitSummary computeBestExit(List<Candle> candles,
                                           double entryPx, Double sl, Double tgt) {
        if (candles == null || candles.isEmpty() || entryPx <= 0) return BestExitSummary.empty();

        double peakHigh = Double.NEGATIVE_INFINITY, troughLow = Double.POSITIVE_INFINITY;
        String peakDay = null, troughDay = null;
        double bestClose = Double.NEGATIVE_INFINITY;
        String bestCloseDay = null;
        String slHitDay = null, tgtHitDay = null;

        for (Candle c : candles) {
            if (c.preEntry()) continue;
            if (c.high() > peakHigh)  { peakHigh = c.high();  peakDay = c.date(); }
            if (c.low()  < troughLow) { troughLow = c.low();  troughDay = c.date(); }
            if (c.close() > bestClose){ bestClose = c.close(); bestCloseDay = c.date(); }
            if (sl != null && sl > 0 && slHitDay == null && c.low()  <= sl)  slHitDay  = c.date();
            if (tgt != null && tgt > 0 && tgtHitDay == null && c.high() >= tgt) tgtHitDay = c.date();
        }

        if (peakDay == null) return BestExitSummary.empty();
        double peakPct   = ((peakHigh - entryPx) / entryPx) * 100.0;
        double troughPct = ((troughLow - entryPx) / entryPx) * 100.0;
        double bestClosePct = ((bestClose - entryPx) / entryPx) * 100.0;
        return new BestExitSummary(
                peakHigh, peakPct, peakDay,
                troughLow, troughPct, troughDay,
                bestClose, bestClosePct, bestCloseDay,
                slHitDay, tgtHitDay
        );
    }

    // ---------------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------------

    /**
     * Serialised over the wire → keep field names short and JSON-friendly.
     * {@code preEntry} lets the frontend grey-out candles before the entry
     * pivot (feature #7 — pre-entry context).
     */
    public record Candle(String date, double open, double high, double low,
                         double close, long volume, boolean preEntry) {}

    /**
     * "Would-have-been" exit prices computed from post-entry candles only.
     * Prices in ₹, pct values in percentage points relative to entry. Any
     * of the day fields may be null when the event never happened.
     */
    public record BestExitSummary(
            double peakHigh, double peakPct, String peakDay,
            double troughLow, double troughPct, String troughDay,
            double bestClose, double bestClosePct, String bestCloseDay,
            String slHitDay, String tgtHitDay
    ) {
        static BestExitSummary empty() {
            return new BestExitSummary(0,0,null, 0,0,null, 0,0,null, null, null);
        }
    }

    private record Entry(long timestampMs, List<Candle> candles) {}
}

