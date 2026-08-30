package com.orderup.marketdata;

import com.orderup.pnl.BenchmarkService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.OHLCQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sector / stock intraday-movement feed for the Heatmap tab.
 *
 * <p>Answers the question "where is money flowing today?" independently of
 * the user's own trades. The Heatmap tab has two views:
 * <ol>
 *   <li><b>Sectors view</b> — one tile per canonical NIFTY sector index
 *       (13 tiles) coloured by intraday % change vs previous close.</li>
 *   <li><b>Stocks view</b> — clicking a sector tile drills into its
 *       Nifty-500 constituents, each tile coloured by its own intraday %.</li>
 * </ol>
 *
 * <p>Both views are backed by a short-TTL in-memory cache (5 minutes) so
 * multiple browsers / rapid clicks don't hammer Kite. The cadence is
 * intentionally low — the frontend polls at the same 5-minute interval —
 * because a heatmap is a "big picture" view, not a live ticker.
 *
 * <p>Sector → constituent-symbol mapping is built at first use by inverting
 * {@link ClassificationService#allSymbolSectors()} through the same keyword
 * table {@link BenchmarkService#sectorKeywordTable()} uses to route free-form
 * sector strings to canonical index names, so a tile labelled "NIFTY IT"
 * drills into every Nifty-500 stock whose NSE Industry contains
 * "software" / "it" / "technology".
 */
@Service
public class HeatmapService {

    private static final Logger log = LoggerFactory.getLogger(HeatmapService.class);

    /** Cache TTL — matches the frontend refresh cadence. */
    private static final long CACHE_TTL_MS = 60 * 1000L;

    /** Kite getOHLC/getLTP has a documented batch size cap; chunk to stay under it. */
    private static final int QUOTE_BATCH = 200;

    /**
     * Explicit constituent overrides for sector indices whose membership
     * cannot be inferred from the free-form NSE "Industry" field alone.
     *
     * <p>Example: the Industry column in {@code sector-nse500.csv} classifies
     * both SBI and HDFC Bank as {@code "Banks"}. Keyword routing therefore
     * lumps every bank into {@code NIFTY BANK} and leaves
     * {@code NIFTY PSU BANK} / {@code NIFTY PVT BANK} empty. To fix that,
     * we ship a stable, hand-maintained membership list for those indices.
     * These match the NSE Index Committee's semi-annual review — refresh
     * once every 6 months alongside the sector-nse500.csv update.
     *
     * <p>A symbol listed here is <em>added</em> to the sector (dedup'd) — it
     * does NOT get removed from any other sector it also matched via
     * keywords, which correctly mirrors reality (HDFCBANK is in both
     * NIFTY BANK and NIFTY PVT BANK).
     */
    private static final Map<String, List<String>> EXPLICIT_CONSTITUENTS;
    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // NIFTY BANK — top 12 banks (union of private & PSU heavyweights).
        // Needed because the bundled sector-nse500.csv uses NSE's broad
        // Sector taxonomy, whose only bank-related bucket is
        // "Financial Services" — that alone can't distinguish banks from
        // NBFCs/insurers/AMCs, so keyword routing misses NIFTY BANK entirely.
        m.put("NIFTY BANK", List.of(
                "HDFCBANK", "ICICIBANK", "KOTAKBANK", "SBIN", "AXISBANK",
                "INDUSINDBK", "BANKBARODA", "PNB", "IDFCFIRSTB",
                "FEDERALBNK", "AUBANK", "BANDHANBNK"
        ));
        // NIFTY PSU BANK — 12 public-sector bank constituents.
        m.put("NIFTY PSU BANK", List.of(
                "SBIN", "PNB", "BANKBARODA", "CANBK", "UNIONBANK", "INDIANB",
                "IOB", "CENTRALBK", "MAHABANK", "UCOBANK", "PSB", "BANKINDIA"
        ));
        // NIFTY PVT BANK — 10 private-sector bank constituents.
        m.put("NIFTY PVT BANK", List.of(
                "HDFCBANK", "ICICIBANK", "KOTAKBANK", "AXISBANK", "INDUSINDBK",
                "IDFCFIRSTB", "FEDERALBNK", "RBLBANK", "BANDHANBNK", "YESBANK"
        ));
        EXPLICIT_CONSTITUENTS = Map.copyOf(m);
    }

    private final KiteConnect kite;
    private final BenchmarkService benchmarks;
    private final ClassificationService classifier;
    private final InstrumentService instruments;

    /** Lazily-built sector-index → uppercase-symbols map. */
    private volatile Map<String, List<String>> sectorToSymbols;

    /** Cache of the most recent sectors payload. */
    private volatile CachedSectors sectorsCache;
    /** Per-sector cache of the most recent stocks payload. */
    private final Map<String, CachedStocks> stocksCache = new ConcurrentHashMap<>();
    /** Per-symbol cache of intraday 5-min candles. */
    private final Map<String, CachedIntraday> intradayCache = new ConcurrentHashMap<>();

    public HeatmapService(KiteConnect kite,
                          BenchmarkService benchmarks,
                          ClassificationService classifier,
                          InstrumentService instruments) {
        this.kite = kite;
        this.benchmarks = benchmarks;
        this.classifier = classifier;
        this.instruments = instruments;
    }

    // ================================================================
    // Public API
    // ================================================================

    /**
     * @param force bypass the cache (used by the manual "↻ Refresh now" button)
     * @return a tile per canonical NIFTY sector index, sorted by name
     */
    public SectorsPayload sectors(boolean force) {
        CachedSectors c = sectorsCache;
        long now = System.currentTimeMillis();
        if (!force && c != null && (now - c.builtAtMs) < CACHE_TTL_MS) {
            return c.payload;
        }
        List<String> names = benchmarks.sectorBenchmarks();
        List<String> keys = new ArrayList<>(names.size());
        for (String n : names) keys.add("NSE:" + n);
        Map<String, OHLCQuote> quotes = fetchQuotes(keys);
        List<SectorTile> tiles = new ArrayList<>(names.size());
        for (String name : names) {
            OHLCQuote q = quotes.get("NSE:" + name);
            tiles.add(SectorTile.of(name, q));
        }
        SectorsPayload payload = new SectorsPayload(Instant.now().toString(), tiles);
        sectorsCache = new CachedSectors(now, payload);
        return payload;
    }

    /**
     * @param sectorName canonical index name (e.g. {@code "NIFTY IT"})
     * @param force bypass cache
     */
    public StocksPayload stocks(String sectorName, boolean force) {
        String key = sectorName == null ? "" : sectorName.toUpperCase(Locale.ROOT).trim();
        CachedStocks c = stocksCache.get(key);
        long now = System.currentTimeMillis();
        if (!force && c != null && (now - c.builtAtMs) < CACHE_TTL_MS) {
            return c.payload;
        }
        Map<String, List<String>> map = ensureSectorMap();
        List<String> symbols = map.getOrDefault(key, List.of());
        List<StockTile> tiles;
        if (symbols.isEmpty()) {
            tiles = List.of();
        } else {
            List<String> keys = new ArrayList<>(symbols.size());
            for (String s : symbols) keys.add("NSE:" + s);
            Map<String, OHLCQuote> quotes = fetchQuotes(keys);
            tiles = new ArrayList<>(symbols.size());
            for (String s : symbols) {
                OHLCQuote q = quotes.get("NSE:" + s);
                tiles.add(StockTile.of(s, q));
            }
            // Sort by absolute % change desc so movers surface first; nulls last.
            tiles.sort((a, b) -> {
                double ax = a.pctChange == null ? -1 : Math.abs(a.pctChange);
                double bx = b.pctChange == null ? -1 : Math.abs(b.pctChange);
                return Double.compare(bx, ax);
            });
        }
        StocksPayload payload = new StocksPayload(key, Instant.now().toString(), tiles);
        stocksCache.put(key, new CachedStocks(now, payload));
        return payload;
    }

    // ================================================================
    // Internals
    // ================================================================

    /**
     * 5-minute OHLC candles for {@code symbol} spanning the previous
     * trading day and today, used by the heatmap stock-tile mini-chart.
     *
     * <p>Cached per-symbol for {@link #CACHE_TTL_MS} so rapid tile clicks
     * don't hammer Kite. Returns an empty list on any failure (unknown
     * symbol, no session, holiday, …) — the frontend renders an empty
     * state instead of erroring.
     */
    public IntradayPayload intraday(String symbol) {
        String sym = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT).trim();
        if (sym.isEmpty()) return new IntradayPayload(sym, Instant.now().toString(), List.of());
        CachedIntraday c = intradayCache.get(sym);
        long now = System.currentTimeMillis();
        if (c != null && (now - c.builtAtMs) < CACHE_TTL_MS) return c.payload;

        Long token = instruments.tokenFor(sym);
        if (token == null) {
            synchronized (instruments) {
                token = instruments.tokenFor(sym);
                if (token == null) {
                    log.info("HeatmapService.intraday: instrument map miss, triggering refresh for {}", sym);
                    instruments.refreshInstruments();
                    token = instruments.tokenFor(sym);
                }
            }
        }
        if (token == null) {
            log.debug("HeatmapService.intraday: no token for {}", sym);
            return new IntradayPayload(sym, Instant.now().toString(), List.of());
        }

        // Fetch 5-min candles over a comfortably wide window (7 calendar days
        // back covers weekends + Mon/Tue holidays), then keep only bars from
        // the two most recent distinct trading dates that appear in the data.
        List<IntradayCandle> candles = List.of();
        try {
            ZoneId ist = ZoneId.of("Asia/Kolkata");
            ZonedDateTime to = ZonedDateTime.now(ist);
            ZonedDateTime from = to.minusDays(7);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            fmt.setTimeZone(TimeZone.getTimeZone(ist));
            HistoricalData hd = kite.getHistoricalData(
                    Date.from(from.toInstant()), Date.from(to.toInstant()),
                    String.valueOf(token), "5minute", false, false);
            if (hd != null && hd.dataArrayList != null && !hd.dataArrayList.isEmpty()) {
                // Bucket by trade date, then keep only the last two dates.
                TreeSet<LocalDate> dates = new TreeSet<>();
                List<IntradayCandle> all = new ArrayList<>(hd.dataArrayList.size());
                for (HistoricalData d : hd.dataArrayList) {
                    if (d.timeStamp == null || d.timeStamp.length() < 10) continue;
                    LocalDate date = LocalDate.parse(d.timeStamp.substring(0, 10));
                    dates.add(date);
                    // Emit ISO instant so JS can parse without timezone quirks.
                    Instant t;
                    try {
                        t = fmt.parse(d.timeStamp.replace('T', ' ').substring(0, 19)).toInstant();
                    } catch (Exception ex) { t = Instant.now(); }
                    all.add(new IntradayCandle(t.toString(), date.toString(),
                            d.open, d.high, d.low, d.close, (long) d.volume));
                }
                // Keep last two trading dates.
                LocalDate today = dates.last();
                LocalDate prev  = dates.size() >= 2 ? dates.lower(today) : today;
                List<IntradayCandle> kept = new ArrayList<>(all.size());
                for (IntradayCandle k : all) {
                    LocalDate d = LocalDate.parse(k.date);
                    if (d.equals(today) || d.equals(prev)) kept.add(k);
                }
                candles = kept;
            }
        } catch (Throwable t) {
            log.warn("HeatmapService.intraday({}) failed: {}", sym, t.getMessage());
        }

        IntradayPayload payload = new IntradayPayload(sym, Instant.now().toString(), candles);
        intradayCache.put(sym, new CachedIntraday(now, payload));
        return payload;
    }

    /**
     * Build the reverse {@code sector-index → symbols} map by routing every
     * Nifty-500 symbol's NSE Industry through the same keyword table used
     * by BenchmarkService. Cached for process lifetime — reload semantics
     * follow ClassificationService.reload() (which we don't observe here;
     * a JVM restart is required if the sector CSV is rotated).
     */
    private Map<String, List<String>> ensureSectorMap() {
        Map<String, List<String>> cached = sectorToSymbols;
        if (cached != null) return cached;
        synchronized (this) {
            if (sectorToSymbols != null) return sectorToSymbols;
            Map<String, String[]> keywords = benchmarks.sectorKeywordTable();
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (String idx : keywords.keySet()) out.put(idx, new ArrayList<>());
            for (Map.Entry<String, String> e : classifier.allSymbolSectors().entrySet()) {
                String symbol   = e.getKey();
                String industry = e.getValue();
                if (industry == null) continue;
                String industryLc = industry.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, String[]> kw : keywords.entrySet()) {
                    boolean hit = false;
                    for (String k : kw.getValue()) {
                        if (industryLc.contains(k)) { hit = true; break; }
                    }
                    if (hit) {
                        out.get(kw.getKey()).add(symbol);
                        break; // first match wins — same policy as BenchmarkService
                    }
                }
            }
            // freeze & sort each list alphabetically for a stable UI ordering
            Map<String, List<String>> frozen = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : out.entrySet()) {
                java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>(e.getValue());
                // Merge explicit overrides for this sector (if any). Symbols
                // are added regardless of whether keyword routing already
                // picked them up — dedup handles the overlap.
                List<String> override = EXPLICIT_CONSTITUENTS.get(e.getKey());
                if (override != null) {
                    for (String s : override) dedup.add(s.toUpperCase(Locale.ROOT));
                }
                List<String> list = new ArrayList<>(dedup);
                Collections.sort(list);
                frozen.put(e.getKey(), Collections.unmodifiableList(list));
            }
            log.info("HeatmapService: sector→symbols map built for {} sectors, total {} symbols",
                    frozen.size(), frozen.values().stream().mapToInt(List::size).sum());
            // Diagnostic: log per-sector counts once so empty buckets are
            // obvious in the boot log — makes future "why is X empty?" bugs
            // trivial to spot without adding an admin endpoint.
            frozen.forEach((k, v) -> log.info("  {} → {} symbols", k, v.size()));
            sectorToSymbols = Collections.unmodifiableMap(frozen);
            return sectorToSymbols;
        }
    }

    /**
     * Batched call to {@code kite.getOHLC(...)}. Returns an empty map on
     * failure (unauthenticated session, network hiccup, …) so tiles render
     * as neutral "—" instead of failing the entire request.
     */
    private Map<String, OHLCQuote> fetchQuotes(List<String> instrumentKeys) {
        Map<String, OHLCQuote> out = new java.util.HashMap<>();
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return out;
        for (int i = 0; i < instrumentKeys.size(); i += QUOTE_BATCH) {
            List<String> chunk = instrumentKeys.subList(i, Math.min(i + QUOTE_BATCH, instrumentKeys.size()));
            try {
                Map<String, OHLCQuote> q = kite.getOHLC(chunk.toArray(new String[0]));
                if (q != null) out.putAll(q);
            } catch (Throwable t) {
                log.warn("Heatmap getOHLC chunk failed ({} keys): {}", chunk.size(), t.getMessage());
            }
        }
        return out;
    }

    // ================================================================
    // DTOs
    // ================================================================

    public record SectorsPayload(String asOf, List<SectorTile> sectors) {}
    public record StocksPayload(String sector, String asOf, List<StockTile> stocks) {}

    /**
     * Sector-index tile. Carries full OHLC so the frontend can flag the
     * "Open == High" (bearish, sold from open) and "Open == Low" (bullish,
     * bought from open) tape signals without another network round-trip.
     */
    public record SectorTile(String name, Double lastPrice, Double prevClose,
                             Double pctChange, Double open, Double high, Double low) {
        static SectorTile of(String name, OHLCQuote q) {
            if (q == null || q.ohlc == null)
                return new SectorTile(name, null, null, null, null, null, null);
            Double last  = q.lastPrice   != 0.0 ? q.lastPrice   : null;
            Double close = q.ohlc.close  != 0.0 ? q.ohlc.close  : null;
            Double open  = q.ohlc.open   != 0.0 ? q.ohlc.open   : null;
            Double high  = q.ohlc.high   != 0.0 ? q.ohlc.high   : null;
            Double low   = q.ohlc.low    != 0.0 ? q.ohlc.low    : null;
            Double pct   = (last != null && close != null && close > 0)
                    ? ((last - close) / close) * 100.0 : null;
            return new SectorTile(name, last, close, pct, open, high, low);
        }
    }

    /** Stock tile — same OHLC extension as {@link SectorTile}. */
    public record StockTile(String symbol, Double lastPrice, Double prevClose,
                            Double pctChange, Double open, Double high, Double low) {
        static StockTile of(String symbol, OHLCQuote q) {
            if (q == null || q.ohlc == null)
                return new StockTile(symbol, null, null, null, null, null, null);
            Double last  = q.lastPrice   != 0.0 ? q.lastPrice   : null;
            Double close = q.ohlc.close  != 0.0 ? q.ohlc.close  : null;
            Double open  = q.ohlc.open   != 0.0 ? q.ohlc.open   : null;
            Double high  = q.ohlc.high   != 0.0 ? q.ohlc.high   : null;
            Double low   = q.ohlc.low    != 0.0 ? q.ohlc.low    : null;
            Double pct   = (last != null && close != null && close > 0)
                    ? ((last - close) / close) * 100.0 : null;
            return new StockTile(symbol, last, close, pct, open, high, low);
        }
    }

    private record CachedSectors(long builtAtMs, SectorsPayload payload) {}
    private record CachedStocks(long builtAtMs, StocksPayload payload) {}

    /** Intraday 5-minute candle for the heatmap stock mini-chart. */
    public record IntradayCandle(String time, String date,
                                 double open, double high, double low, double close, long volume) {}
    public record IntradayPayload(String symbol, String asOf, List<IntradayCandle> candles) {}
    private record CachedIntraday(long builtAtMs, IntradayPayload payload) {}
}

