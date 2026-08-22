package com.orderup.app.web;

import com.orderup.config.TradingProperties;
import com.orderup.config.WaceConfig;
import com.orderup.marketdata.Candle;
import com.orderup.marketdata.CandleCacheService;
import com.orderup.marketdata.InstrumentService;
import com.orderup.app.strategy.WaceDiagnostic;
import com.orderup.app.strategy.WaceDiagnostic.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostic endpoint that runs {@link WaceDiagnostic} against WACE_DAILY on
 * every watchlist symbol and returns:
 * <ul>
 *   <li>a funnel — how many symbols pass each individual condition</li>
 *   <li>a distribution — how many symbols pass 0/1/2/.../9 conditions</li>
 *   <li>the top near-misses (symbols passing 7+ of 9 sub-conditions)</li>
 *   <li>the fully-qualified list, if any</li>
 * </ul>
 * Read-only: no orders, no watchlist rows, no side effects beyond cache hits.
 */
@RestController
@RequestMapping("/diag/wace")
public class WaceDiagController {

    private static final Logger log = LoggerFactory.getLogger(WaceDiagController.class);

    private final InstrumentService instruments;
    private final CandleCacheService cache;
    private final TradingProperties trading;

    public WaceDiagController(InstrumentService instruments,
                              CandleCacheService cache,
                              TradingProperties trading) {
        this.instruments = instruments;
        this.cache = cache;
        this.trading = trading;
    }

    /** GET /diag/wace/daily-scan?nearMissMin=7 */
    @GetMapping("/daily-scan")
    public Map<String, Object> dailyScan(@RequestParam(defaultValue = "7") int nearMissMin) {
        WaceConfig cfg = trading.strategies().waceDaily();
        if (cfg == null || !cfg.enabled()) {
            return Map.of("error", "WACE_DAILY not configured or disabled");
        }

        var watchlist = instruments.watchlist();
        int totalConditions = 9;
        int evaluated = 0, insufficient = 0;

        int[] passFunnel = new int[totalConditions];     // per-condition pass counts
        int[] passHistogram = new int[totalConditions + 1]; // 0..9 how many symbols passed exactly K
        String[] conditionNames = null;

        List<Qualifier> qualified = new ArrayList<>();
        List<Qualifier> nearMisses = new ArrayList<>();

        for (String symbol : watchlist) {
            Long token = instruments.tokenFor(symbol);
            if (token == null) continue;
            List<Candle> candles = cache.get(token, cfg.interval(), cfg.historyDays());
            int minBars = Math.max(
                    Math.max(cfg.cciPeriod(), cfg.wrPeriod()),
                    Math.max(cfg.emaSlow(), 2 * cfg.adxPeriod() + 1))
                    + Math.max(cfg.prevHighLookback(), 1);
            if (candles.size() < minBars) { insufficient++; continue; }

            Report r = WaceDiagnostic.evaluate(candles, cfg);
            if (r.conditions().isEmpty()) { insufficient++; continue; }
            evaluated++;

            if (conditionNames == null) {
                conditionNames = r.conditions().stream()
                        .map(WaceDiagnostic.ConditionResult::name)
                        .toArray(String[]::new);
            }
            for (int j = 0; j < r.conditions().size(); j++) {
                if (r.conditions().get(j).pass()) passFunnel[j]++;
            }
            passHistogram[r.passedCount()]++;

            if (r.qualified()) {
                qualified.add(new Qualifier(symbol, r.passedCount(), r));
            } else if (r.passedCount() >= nearMissMin) {
                nearMisses.add(new Qualifier(symbol, r.passedCount(), r));
            }
        }

        // Sort near-misses by passedCount desc then symbol asc.
        nearMisses.sort(Comparator.<Qualifier>comparingInt(q -> -q.passed).thenComparing(q -> q.symbol));

        Map<String, Object> funnel = new LinkedHashMap<>();
        if (conditionNames != null) {
            for (int j = 0; j < conditionNames.length; j++) {
                funnel.put(conditionNames[j], passFunnel[j]);
            }
        }
        Map<Integer, Integer> histogram = new TreeMap<>();
        for (int k = 0; k < passHistogram.length; k++) if (passHistogram[k] > 0) histogram.put(k, passHistogram[k]);

        log.info("[WACE-DIAG] evaluated={} insufficient={} qualified={} nearMiss(>={})={} ",
                evaluated, insufficient, qualified.size(), nearMissMin, nearMisses.size());

        return Map.of(
                "totalWatchlist", watchlist.size(),
                "evaluated", evaluated,
                "insufficientHistory", insufficient,
                "totalSubConditions", totalConditions,
                "perConditionPassCount", funnel,
                "passCountHistogram", histogram,
                "qualified", qualified.stream().map(this::render).toList(),
                "nearMisses", nearMisses.stream().map(this::render).toList()
        );
    }

    private Map<String, Object> render(Qualifier q) {
        Map<String, Object> failed = new LinkedHashMap<>();
        Map<String, Object> passed = new LinkedHashMap<>();
        for (var c : q.report.conditions()) {
            String desc = String.format("%.3f vs %.3f", c.lhs(), c.rhs());
            (c.pass() ? passed : failed).put(c.name(), desc);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", q.symbol);
        out.put("passed", q.passed);
        out.put("failedChecks", failed);
        out.put("passedChecks", passed);
        return out;
    }

    private record Qualifier(String symbol, int passed, Report report) {}
}

