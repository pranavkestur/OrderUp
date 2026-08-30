package com.orderup.chartink.web;
import com.orderup.marketdata.HeatmapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
/**
 * Read-only endpoints for the Heatmap tab.
 *
 * <ul>
 *   <li>{@code GET /api/heatmap/sectors} - one tile per canonical NIFTY
 *       sector index with intraday % change vs previous close.</li>
 *   <li>{@code GET /api/heatmap/sector/{name}/stocks} - Nifty-500
 *       constituents of the sector with their own intraday % change.</li>
 * </ul>
 *
 * <p>Both endpoints hit an in-memory 5-minute cache in
 * {@link HeatmapService}; pass {@code ?force=true} to bypass it (the
 * frontend rate-limits this to protect the Kite quota).</p>
 */
@RestController
@RequestMapping("/api/heatmap")
public class HeatmapController {
    private final HeatmapService heatmap;
    public HeatmapController(HeatmapService heatmap) {
        this.heatmap = heatmap;
    }
    @GetMapping("/sectors")
    public HeatmapService.SectorsPayload sectors(
            @RequestParam(defaultValue = "false") boolean force) {
        return heatmap.sectors(force);
    }
    @GetMapping("/sector/{name}/stocks")
    public HeatmapService.StocksPayload stocks(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean force) {
        return heatmap.stocks(name, force);
    }

    /**
     * 5-minute candles for {@code symbol} spanning the previous trading day
     * and today — powers the intraday mini-chart shown when a heatmap
     * stock tile is clicked.
     */
    @GetMapping("/intraday")
    public HeatmapService.IntradayPayload intraday(@RequestParam String symbol) {
        return heatmap.intraday(symbol);
    }
}
