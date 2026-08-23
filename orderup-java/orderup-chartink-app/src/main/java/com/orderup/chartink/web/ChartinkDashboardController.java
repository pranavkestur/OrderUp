package com.orderup.chartink.web;

import com.orderup.orders.OrderRecordRepository;
import com.orderup.pnl.PositionService;
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

    public ChartinkDashboardController(PositionService positions, OrderRecordRepository orders) {
        this.positions = positions;
        this.orders = orders;
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
            @RequestParam(required = false) String sector) {
        Instant[] r = range(range);
        List<PositionService.ClosedTrade> all = positions.closedTrades(r[0], r[1], STRATEGY);
        if (sector == null || sector.isBlank() || "all".equalsIgnoreCase(sector)) return all;
        return all.stream()
                .filter(t -> t.sector() != null && t.sector().equalsIgnoreCase(sector))
                .toList();
    }

    @GetMapping("/sector-performance")
    public List<PositionService.SectorRow> sectorPerformance(
            @RequestParam(defaultValue = "month") String range) {
        Instant[] r = range(range);
        return positions.sectorPerformance(r[0], r[1], STRATEGY);
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

