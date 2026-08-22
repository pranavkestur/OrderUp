package com.orderup.web;

import com.orderup.orders.WatchlistCandidate;
import com.orderup.orders.WatchlistCandidateRepository;
import com.orderup.pnl.PositionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api")
public class PnlController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final PositionService positions;
    private final WatchlistCandidateRepository watchlistRepo;

    public PnlController(PositionService positions, WatchlistCandidateRepository watchlistRepo) {
        this.positions = positions;
        this.watchlistRepo = watchlistRepo;
    }

    @GetMapping("/orders")
    public List<PositionService.OrderView> orders(@RequestParam(defaultValue = "today") String range,
                                                  @RequestParam(required = false) String strategy) {
        Instant[] rng = range(range);
        return positions.orders(rng[0], rng[1], blankAsNull(strategy));
    }

    @GetMapping("/summary")
    public PositionService.Summary summary(@RequestParam(defaultValue = "week") String range,
                                           @RequestParam(required = false) String strategy) {
        Instant[] rng = range(range);
        return positions.summary(rng[0], rng[1], blankAsNull(strategy));
    }

    @GetMapping("/strategy-positions")
    public List<PositionService.OpenPosition> strategyPositions(
            @RequestParam(required = false) String strategy) {
        return positions.strategyPositions(blankAsNull(strategy));
    }

    @GetMapping("/potential-orders")
    public List<PositionService.PotentialView> potentialOrders(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String strategy) {
        Instant[] rng = range(range);
        return positions.potentialOrders(rng[0], rng[1], blankAsNull(strategy));
    }

    @GetMapping("/daily")
    public List<PositionService.DailyPoint> daily(@RequestParam(defaultValue = "month") String range,
                                                  @RequestParam(required = false) String strategy) {
        Instant[] rng = range(range);
        return positions.dailySeries(rng[0], rng[1], blankAsNull(strategy));
    }

    @GetMapping("/watchlist")
    public List<WatchlistCandidate> watchlist(@RequestParam(defaultValue = "today") String range) {
        Instant[] rng = range(range);
        return watchlistRepo.findByAddedAtBetweenOrderByAddedAtDesc(rng[0], rng[1]);
    }

    private static Instant[] range(String label) {
        LocalDate today = LocalDate.now(IST);
        LocalDate from = switch (label == null ? "today" : label.toLowerCase()) {
            case "week"  -> today.minusDays(7);
            case "month" -> today.minusDays(30);
            case "all"   -> LocalDate.of(2000, 1, 1);
            default      -> today;
        };
        Instant fromI = from.atStartOfDay(IST).toInstant();
        Instant toI   = today.plusDays(1).atStartOfDay(IST).toInstant();
        return new Instant[]{ fromI, toI };
    }

    private static String blankAsNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
