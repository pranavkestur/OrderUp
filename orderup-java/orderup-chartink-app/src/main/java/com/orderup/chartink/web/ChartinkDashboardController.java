package com.orderup.chartink.web;

import com.orderup.orders.OrderRecordRepository;
import com.orderup.pnl.BenchmarkService;
import com.orderup.pnl.ExitPerformance;
import com.orderup.pnl.ExitPerformanceRepository;
import com.orderup.pnl.PositionService;
import com.orderup.pnl.PriceHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Read-only dashboard endpoints for the Chartink P&L page. All routes are
 * scoped to {@code indicator = "CHARTINK"} so the dashboard is unaffected by
 * anything the WACE scanner does in {@code orderup-app}'s own DB.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /api/pnl/kpis}              — hero KPI bundle</li>
 *   <li>{@code GET /api/pnl/open-positions}    — current positions with LTP</li>
 *   <li>{@code GET /api/pnl/closed-trades}     — FIFO-matched exits</li>
 *   <li>{@code GET /api/pnl/sector-performance}— sector-level realized rollup</li>
 *   <li>{@code GET /api/pnl/equity-curve}      — cumulative realized daily series</li>
 *   <li>{@code GET /api/pnl/orders}            — raw order log for the range</li>
 * </ul>
 *
 * <p>{@code range} query parameter accepts {@code today|week|month|all}.</p>
 */
@RestController
@RequestMapping("/api/pnl")
public class ChartinkDashboardController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String STRATEGY = "CHARTINK";

    private final PositionService positions;
    private final OrderRecordRepository orders;
    private final ExitPerformanceRepository exitPerf;
    private final PriceHistoryService priceHistory;
    private final BenchmarkService benchmarks;

    public ChartinkDashboardController(PositionService positions, OrderRecordRepository orders,
                                       ExitPerformanceRepository exitPerf,
                                       PriceHistoryService priceHistory,
                                       BenchmarkService benchmarks) {
        this.positions = positions;
        this.orders = orders;
        this.exitPerf = exitPerf;
        this.priceHistory = priceHistory;
        this.benchmarks = benchmarks;
    }

    @GetMapping("/kpis")
    public PositionService.KpiBundle kpis(@RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        return positions.kpis(r[0], r[1], STRATEGY);
    }


    @GetMapping("/open-positions")
    public List<PositionService.OpenPosition> openPositions() {
        return positions.strategyPositions(STRATEGY);
    }

    @GetMapping("/closed-trades")
    public List<PositionService.ClosedTrade> closedTrades(
            @RequestParam(defaultValue = "month") String range,
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String marketCap) {
        Instant[] r = range(range);
        List<PositionService.ClosedTrade> all = positions.closedTrades(r[0], r[1], STRATEGY);
        java.util.stream.Stream<PositionService.ClosedTrade> s = all.stream();
        if (sector != null && !sector.isBlank() && !"all".equalsIgnoreCase(sector)) {
            s = s.filter(t -> t.sector() != null && t.sector().equalsIgnoreCase(sector));
        }
        if (marketCap != null && !marketCap.isBlank() && !"all".equalsIgnoreCase(marketCap)) {
            s = s.filter(t -> t.marketCap() != null && t.marketCap().equalsIgnoreCase(marketCap));
        }
        return s.toList();
    }

    @GetMapping("/sector-performance")
    public List<PositionService.SectorRow> sectorPerformance(
            @RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        return positions.sectorPerformance(r[0], r[1], STRATEGY);
    }

    /**
     * Realized P&L rollup grouped by AMFI market-cap category
     * ({@code LARGE_CAP / MID_CAP / SMALL_CAP / UNKNOWN}). Powers the
     * "Market cap performance" panel — mirrors {@link #sectorPerformance}.
     */
    @GetMapping("/marketcap-performance")
    public List<PositionService.MarketCapRow> marketCapPerformance(
            @RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        return positions.marketCapPerformance(r[0], r[1], STRATEGY);
    }

    @GetMapping("/equity-curve")
    public List<PositionService.DailyPoint> equityCurve(
            @RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        return positions.dailySeries(r[0], r[1], STRATEGY);
    }

    @GetMapping("/orders")
    public List<PositionService.OrderView> orders(
            @RequestParam(defaultValue = "today") String range) {
        Instant[] r = range(range);
        return positions.orders(r[0], r[1], STRATEGY);
    }

    // -------- Exit-classification / post-exit tracking --------

    /** Summary counts + averages by exit type (SL / TGT / MANUAL) over the range. */
    @GetMapping("/exit-stats")
    public PositionService.ExitStats exitStats(@RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        return positions.exitStats(r[0], r[1], STRATEGY);
    }

    /**
     * Per-trade post-exit tracking rows. Joined against ClosedTrade so the
     * dashboard can show exit price / price_1d / price_7d / price_30d and
     * derived verdict badges. Only SELLs whose {@code exitAt} falls inside
     * the range are returned.
     */
    @GetMapping("/exit-performance")
    public List<ExitPerformanceRow> exitPerformance(
            @RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        Instant from = r[0], to = r[1];
        // Index closed trades by SELL id so we can enrich with symbol / P&L
        // when the ExitPerformance row exists in isolation.
        java.util.Map<Long, PositionService.ClosedTrade> tradesById = new java.util.HashMap<>();
        for (PositionService.ClosedTrade t : positions.closedTrades(from, to, STRATEGY)) {
            if (t.sellOrderId() != null) tradesById.put(t.sellOrderId(), t);
        }
        List<ExitPerformanceRow> out = new java.util.ArrayList<>();
        for (ExitPerformance ep : exitPerf.findAll()) {
            if (ep.getExitAt() == null) continue;
            if (ep.getExitAt().isBefore(from) || ep.getExitAt().isAfter(to)) continue;
            PositionService.ClosedTrade ct = tradesById.get(ep.getId());
            out.add(ExitPerformanceRow.of(ep, ct));
        }
        out.sort((a, b) -> b.exitAt.compareTo(a.exitAt));
        return out;
    }

    /** Aggregate "SL discipline / TGT discipline" cards. */
    @GetMapping("/exit-discipline")
    public ExitDiscipline exitDiscipline(@RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        int slN = 0, slSaved = 0, slPremature = 0;
        int tgtN = 0, tgtKeptRunning = 0, tgtReversed = 0;
        double slMissedGainSum = 0, tgtMissedGainSum = 0;
        int slMissedN = 0, tgtMissedN = 0;
        for (ExitPerformance ep : exitPerf.findAll()) {
            if (ep.getExitAt() == null) continue;
            if (ep.getExitAt().isBefore(r[0]) || ep.getExitAt().isAfter(r[1])) continue;
            // Use the 7-day window as the "did it recover / keep running?" horizon.
            Double roi = ep.getRoi7d() != null ? ep.getRoi7d() : ep.getRoi1d();
            if (roi == null) continue;
            String tag = ep.getExitType() == null ? "" : ep.getExitType().toUpperCase();
            if (tag.startsWith("SL")) {
                slN++;
                if (roi < 0) slSaved++;           // stock kept falling → SL saved us
                else {
                    slPremature++;                // stock recovered → SL was premature
                    slMissedGainSum += roi;
                    slMissedN++;
                }
            } else if ("TGT".equals(tag)) {
                tgtN++;
                if (roi > 0) {                    // stock kept running → TGT clipped
                    tgtKeptRunning++;
                    tgtMissedGainSum += roi;
                    tgtMissedN++;
                } else {
                    tgtReversed++;                // TGT well-timed
                }
            }
        }
        return new ExitDiscipline(
                slN, slSaved, slPremature,
                slMissedN > 0 ? slMissedGainSum / slMissedN : 0.0,
                tgtN, tgtKeptRunning, tgtReversed,
                tgtMissedN > 0 ? tgtMissedGainSum / tgtMissedN : 0.0
        );
    }

    public record ExitDiscipline(
            int slExits, int slSavedCount, int slPrematureCount, double avgMissedRecoveryPct,
            int tgtExits, int tgtKeptRunningCount, int tgtReversedCount, double avgMissedRunPct
    ) {}

    /**
     * Daily OHLC candles for the "post-entry price journey" drawer.
     *
     * <p>Returns candles spanning {@code before} trading days of pre-entry
     * context (greyed out on the frontend) plus {@code days} of post-entry
     * data. Each candle carries a {@code preEntry} flag so the client can
     * style pre-entry days differently without an extra date-compare pass.
     *
     * @param symbol   tradingsymbol (case-insensitive)
     * @param fromDate ISO-8601 date (yyyy-MM-dd), the entry pivot
     * @param days     post-entry trading days (default 30, hard-capped at 60)
     * @param before   pre-entry trading days for context (default 10, capped at 20)
     */
    @GetMapping("/price-history")
    public List<PriceHistoryService.Candle> priceHistory(
            @RequestParam String symbol,
            @RequestParam String fromDate,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int before) {
        int capDays   = Math.min(Math.max(days, 1), 60);
        int capBefore = Math.min(Math.max(before, 0), 20);
        LocalDate from = LocalDate.parse(fromDate);
        return priceHistory.fetchWindow(symbol, from, capBefore, capDays);
    }

    /**
     * Benchmark comparison series aligned to the same {@code fromDate}
     * window as {@link #priceHistory}. Powers the "vs NIFTY / vs sector"
     * toggle in the price-journey drawer — answers "did the whole market
     * sell off, or did just my pick blow up?".
     *
     * @param bench "NIFTY 50", "NIFTY BANK", "NIFTY IT", … or one of the
     *              sector aliases understood by {@link BenchmarkService}.
     *              Pass {@code sector:<sectorString>} to auto-resolve the
     *              matching sector index (returns empty list on no match).
     */
    @GetMapping("/benchmark-history")
    public List<PriceHistoryService.Candle> benchmarkHistory(
            @RequestParam String bench,
            @RequestParam String fromDate,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "10") int before) {
        int capDays   = Math.min(Math.max(days, 1), 60);
        int capBefore = Math.min(Math.max(before, 0), 20);
        LocalDate from = LocalDate.parse(fromDate);
        String resolved = bench;
        if (bench != null && bench.toLowerCase().startsWith("sector:")) {
            resolved = benchmarks.benchNameForSector(bench.substring("sector:".length()));
            if (resolved == null) return List.of();
        }
        return priceHistory.fetchBenchmark(resolved, from, capBefore, capDays);
    }

    /** List of benchmark names the client can offer in its dropdown. */
    @GetMapping("/benchmark-list")
    public BenchmarkList benchmarkList(@RequestParam(required = false) String sector) {
        return new BenchmarkList(benchmarks.knownBenchmarks(),
                sector == null ? null : benchmarks.benchNameForSector(sector));
    }

    public record BenchmarkList(List<String> available, String sectorMatch) {}

    /**
     * "Best exit hindsight" summary computed on the backend from the same
     * candles the drawer fetches. Feeds the strip above the chart —
     * "📈 Peak +5.4% on day 3 · 📉 Trough −2.7% on day 4 · 🎯 close-of-day
     * 3 would have given +4.8%" (feature #1).
     */
    @GetMapping("/best-exit-summary")
    public PriceHistoryService.BestExitSummary bestExitSummary(
            @RequestParam String symbol,
            @RequestParam String fromDate,
            @RequestParam double entryPx,
            @RequestParam(required = false) Double sl,
            @RequestParam(required = false) Double tgt,
            @RequestParam(defaultValue = "30") int days) {
        int capped = Math.min(Math.max(days, 1), 60);
        LocalDate from = LocalDate.parse(fromDate);
        List<PriceHistoryService.Candle> candles = priceHistory.fetchWindow(symbol, from, 0, capped);
        return priceHistory.computeBestExit(candles, entryPx, sl, tgt);
    }

    /** DTO for /exit-performance rows. */
    public static class ExitPerformanceRow {
        public Long sellId;
        public String symbol;
        public Instant exitAt;
        public double exitPrice;
        public String exitType;
        public Double price1d, roi1d;
        public Double price7d, roi7d;
        public Double price30d, roi30d;
        // Optional enrichment from ClosedTrade:
        public Double entryPrice;
        public Double tradePnl;
        public Double tradePnlPct;
        public String verdict; // SL_SAVED / SL_PREMATURE / TGT_CLIPPED / TGT_WELL_TIMED / N/A

        static ExitPerformanceRow of(ExitPerformance ep, PositionService.ClosedTrade ct) {
            ExitPerformanceRow r = new ExitPerformanceRow();
            r.sellId = ep.getId();
            r.symbol = ep.getSymbol();
            r.exitAt = ep.getExitAt();
            r.exitPrice = ep.getExitPrice();
            r.exitType = ep.getExitType();
            r.price1d = ep.getPrice1d(); r.roi1d = ep.getRoi1d();
            r.price7d = ep.getPrice7d(); r.roi7d = ep.getRoi7d();
            r.price30d = ep.getPrice30d(); r.roi30d = ep.getRoi30d();
            if (ct != null) {
                r.entryPrice = ct.entryPx();
                r.tradePnl = ct.pnl();
                r.tradePnlPct = ct.pnlPct();
            }
            r.verdict = deriveVerdict(r.exitType, r.roi7d != null ? r.roi7d : r.roi1d);
            return r;
        }

        private static String deriveVerdict(String exitType, Double roi) {
            if (exitType == null || roi == null) return "N/A";
            String t = exitType.toUpperCase();
            if (t.startsWith("SL")) return roi < 0 ? "SL_SAVED" : "SL_PREMATURE";
            if ("TGT".equals(t))    return roi > 0 ? "TGT_CLIPPED" : "TGT_WELL_TIMED";
            return "N/A";
        }
    }

    private static Instant[] range(String label) {
        LocalDate today = LocalDate.now(IST);
        LocalDate from = switch (label == null ? "month" : label.toLowerCase()) {
            case "today" -> today;
            case "week"  -> today.minusDays(7);
            case "all"   -> LocalDate.of(2000, 1, 1);
            default      -> today.minusDays(30); // "month"
        };
        return new Instant[]{
                from.atStartOfDay(IST).toInstant(),
                today.plusDays(1).atStartOfDay(IST).toInstant()
        };
    }
}

