package com.orderup.pnl;

import com.orderup.orders.HoldingsService;
import com.orderup.orders.OrderRecord;
import com.orderup.orders.OrderRecordRepository;
import com.orderup.orders.PotentialOrder;
import com.orderup.orders.PotentialOrderRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.GTT;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Central read model for the dashboard.
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li>Sync Kite's live orderbook back onto persisted {@link OrderRecord}s so fill
 *       data (avg price, filled qty, terminal status) survives across trading days.</li>
 *   <li>Compute realized P&amp;L via FIFO matching over persisted fills.</li>
 *   <li>Build the "Strategy Positions" view — union of Kite Positions + Holdings,
 *       filtered to symbols that OrderUp actually placed.</li>
 *   <li>Serve the "Potential Orders" list (signals that never made it to Kite).</li>
 * </ol>
 */
@Service
public class PositionService {

    private static final Logger log = LoggerFactory.getLogger(PositionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderRecordRepository repo;
    private final PotentialOrderRepository potentialRepo;
    private final KiteConnect kite;
    private final HoldingsService holdings;

    public PositionService(OrderRecordRepository repo,
                           PotentialOrderRepository potentialRepo,
                           KiteConnect kite,
                           HoldingsService holdings) {
        this.repo = repo;
        this.potentialRepo = potentialRepo;
        this.kite = kite;
        this.holdings = holdings;
    }

    public void syncTodaysFills() {
        Map<String, Fill> fills = fetchFillMap();
        if (fills.isEmpty()) return;
        List<OrderRecord> all = repo.findAll();
        int updated = 0;
        for (OrderRecord o : all) {
            if (o.getKiteOrderId() == null) continue;
            Fill f = fills.get(o.getKiteOrderId());
            if (f == null) continue;
            boolean dirty = false;
            if (f.filledQty > 0 && (o.getFilledQty() == null || o.getFilledQty() != f.filledQty)) {
                o.setFilledQty(f.filledQty); dirty = true;
            }
            if (f.avgPrice > 0 && (o.getAvgFillPrice() == null || Math.abs(o.getAvgFillPrice() - f.avgPrice) > 1e-9)) {
                o.setAvgFillPrice(f.avgPrice); dirty = true;
            }
            if (f.status != null && !f.status.equalsIgnoreCase(o.getStatus())) {
                o.setStatus(f.status); dirty = true;
            }
            if (dirty) { repo.save(o); updated++; }
        }
        if (updated > 0) log.debug("Synced Kite fills onto {} OrderRecords", updated);
    }

    private Map<String, Fill> fetchFillMap() {
        Map<String, Fill> out = new HashMap<>();
        try {
            List<Order> orders = kite.getOrders();
            for (Order o : orders) {
                if (o.orderId == null) continue;
                double avg = parseD(o.averagePrice);
                int filled = (int) parseD(o.filledQuantity);
                out.put(o.orderId, new Fill(avg, filled, o.status));
            }
        } catch (Throwable t) {
            log.warn("Fetching orderbook failed: {}", t.getMessage());
        }
        return out;
    }

    public Summary summary(Instant from, Instant to, String strategyFilter) {
        holdings.refreshIfStale(30_000);
        syncTodaysFills();

        // Build FIFO over the FULL order history and only *emit* realized P&L
        // for lot-slices whose SELL falls inside [from, to]. Range-filtering
        // BOTH sides (as the previous impl did) silently drops SELLs whose BUY
        // is out of range — e.g. Aug-24 BUY, Aug-26 SELL, range=today → BUY is
        // filtered out first, longs is empty when the SELL arrives, and the
        // whole loss vanishes from realizedPnl / winning / losing.
        // This mirrors the SELL-anchored logic already used by closedTrades().
        List<OrderRecord> records = repo.findAll();
        Map<String, List<OrderRecord>> byKey = new LinkedHashMap<>();
        for (OrderRecord o : records) {
            String strategy = nullSafe(o.getIndicator());
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(strategy)) continue;
            byKey.computeIfAbsent(strategy + "|" + o.getSymbol(), k -> new ArrayList<>()).add(o);
        }

        Map<String, StrategyStats> byStrategy = new LinkedHashMap<>();
        double realized = 0;
        int totalTrades = 0, winning = 0, losing = 0;

        for (var e : byKey.entrySet()) {
            String strategy = e.getKey().split("\\|", 2)[0];
            List<OrderRecord> orders = e.getValue();
            orders.sort(Comparator.comparing(OrderRecord::getPlacedAt));
            Deque<double[]> longs = new ArrayDeque<>();
            for (OrderRecord o : orders) {
                Integer q = o.getFilledQty();
                Double  p = o.getAvgFillPrice();
                if (q == null || p == null || q <= 0 || p <= 0) continue;
                if ("BUY".equalsIgnoreCase(o.getSide())) {
                    longs.addLast(new double[]{ q, p });
                } else if ("SELL".equalsIgnoreCase(o.getSide())) {
                    Instant exitAt = o.getPlacedAt();
                    boolean inRange = (from == null || !exitAt.isBefore(from))
                                   && (to   == null || !exitAt.isAfter(to));
                    int remaining = q;
                    while (remaining > 0 && !longs.isEmpty()) {
                        double[] lot = longs.peekFirst();
                        int take = (int) Math.min(lot[0], remaining);
                        double tradePnl = (p - lot[1]) * take;
                        if (inRange) {
                            realized += tradePnl;
                            totalTrades += 1;
                            if (tradePnl > 0) winning++;
                            else if (tradePnl < 0) losing++;
                            StrategyStats s = byStrategy.computeIfAbsent(strategy, k -> new StrategyStats());
                            s.realizedPnl += tradePnl;
                            s.trades += 1;
                        }
                        lot[0] -= take;
                        remaining -= take;
                        if (lot[0] == 0) longs.pollFirst();
                    }
                }
            }
        }

        List<OpenPosition> openPositions = strategyPositions(strategyFilter);
        double unrealized = openPositions.stream().mapToDouble(pp -> pp.unrealizedPnl).sum();
        List<StrategyRow> stratRows = new ArrayList<>();
        byStrategy.forEach((k, v) -> stratRows.add(new StrategyRow(k, v.realizedPnl, v.trades)));
        return new Summary(realized, unrealized, totalTrades, winning, losing, stratRows, openPositions);
    }

    public List<OpenPosition> strategyPositions(String strategyFilter) {
        // Two freshness calls that were previously only done by summary(...) —
        // without them, hitting /api/pnl/open-positions on a fresh JVM before
        // any other endpoint would show ONLY today's Kite day-positions and
        // silently drop every held-from-earlier-day CNC buy, because
        // HoldingsService.snapshot() was still an empty cache.
        // syncTodaysFills() also keeps just-placed BUYs' filledQty / status in
        // step with Kite's orderbook so the dashboard reflects reality.
        holdings.refreshIfStale(30_000);
        syncTodaysFills();
        // Pull in any OCO stop-loss / target exits that Kite executed on our
        // behalf. Without this, symbols the bracket auto-sold appear to have
        // "vanished" — Kite no longer reports them in positions/holdings, but
        // OrderUp has no SELL record either, so they drop out of Open
        // Positions silently instead of moving to Closed Trades. Idempotent.
        try {
            syncExternalSells(strategyFilter);
        } catch (Throwable t) {
            log.warn("strategyPositions: syncExternalSells swallowed: {}", t.getMessage());
        }
        // Same-day OCO fills go through syncExternalSells (Kite's /orders
        // orderbook is intraday-only). For OCOs that triggered on a PREVIOUS
        // trading day, walk each latestBuy whose symbol is no longer in Kite
        // holdings/positions and reconstruct the exit from the OCO GTT's
        // triggered leg. Also idempotent (skips symbols that already have a
        // SELL record).
        try {
            reconcileExternallyClosed(strategyFilter);
        } catch (Throwable t) {
            log.warn("strategyPositions: reconcileExternallyClosed swallowed: {}", t.getMessage());
        }

        Map<String, OrderRecord> latestBuy = latestBuyBySymbol(repo.findAll());
        if (latestBuy.isEmpty()) return List.of();

        Map<String, OpenPosition> rows = new LinkedHashMap<>();

        try {
            Map<String, List<Position>> pos = kite.getPositions();
            List<Position> net = pos == null ? List.of() : pos.getOrDefault("net", List.of());
            for (Position p : net) {
                if (p.tradingSymbol == null) continue;
                String sym = p.tradingSymbol.toUpperCase(Locale.ROOT);
                OrderRecord src = latestBuy.get(sym);
                if (src == null) continue;
                double buyPx  = p.buyPrice  != null && p.buyPrice > 0 ? p.buyPrice
                              : p.averagePrice > 0 ? p.averagePrice : 0.0;
                double sellPx = p.sellPrice != null && p.sellPrice > 0 && p.sellQuantity > 0 ? p.sellPrice : 0.0;
                double ltp    = p.lastPrice != null ? p.lastPrice : 0.0;
                int    qty    = p.netQuantity != 0 ? p.netQuantity : p.buyQuantity - p.sellQuantity;
                rows.put(sym, new OpenPosition(sym, nullSafe(src.getIndicator()),
                        qty, buyPx, sellPx, ltp, 0.0, 0.0));
            }
        } catch (Throwable t) {
            log.warn("Fetching Kite positions failed: {}", t.getMessage());
        }

        for (var entry : holdings.snapshot().entrySet()) {
            String sym = entry.getKey();
            if (rows.containsKey(sym)) continue;
            OrderRecord src = latestBuy.get(sym);
            if (src == null) continue;
            HoldingsService.Snapshot h = entry.getValue();
            rows.put(sym, new OpenPosition(sym, nullSafe(src.getIndicator()),
                    h.quantity(), h.avgPrice(), 0.0, 0.0, 0.0, 0.0));
        }

        List<String> needLtp = new ArrayList<>();
        for (OpenPosition p : rows.values()) if (p.ltp <= 0) needLtp.add("NSE:" + p.symbol);
        if (!needLtp.isEmpty()) {
            try {
                Map<String, LTPQuote> q = kite.getLTP(needLtp.toArray(String[]::new));
                for (OpenPosition p : rows.values()) {
                    if (p.ltp > 0) continue;
                    LTPQuote lq = q.get("NSE:" + p.symbol);
                    if (lq != null) p.ltp = lq.lastPrice;
                }
            } catch (Throwable t) {
                log.warn("LTP lookup failed for strategy positions: {}", t.getMessage());
            }
        }

        List<OpenPosition> out = new ArrayList<>();
        for (OpenPosition p : rows.values()) {
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(p.strategy)) continue;
            double refPx = p.sellPrice > 0 ? p.sellPrice : p.ltp;
            if (p.buyPrice > 0 && refPx > 0) {
                p.pnlPct = ((refPx - p.buyPrice) / p.buyPrice) * 100.0;
                p.unrealizedPnl = (refPx - p.buyPrice) * Math.max(p.quantity, 0);
            }
            // Enrich with bracket + Chartink metadata from the opening BUY.
            OrderRecord buy = latestBuy.get(p.symbol);
            if (buy != null) {
                p.sector        = buy.getSector();
                p.industry      = buy.getIndustry();
                p.alertName     = buy.getAlertName();
                p.stopLossPrice = buy.getStopLossPrice();
                p.targetPrice   = buy.getTargetPrice();
                p.kiteOcoGttId  = buy.getKiteOcoGttId();
                p.kiteOrderId   = buy.getKiteOrderId();
                p.openedAt      = buy.getPlacedAt();
            }
            out.add(p);
        }
        return out;
    }

    private static Map<String, OrderRecord> latestBuyBySymbol(List<OrderRecord> records) {
        Map<String, OrderRecord> out = new HashMap<>();
        for (OrderRecord o : records) {
            if (!"BUY".equalsIgnoreCase(o.getSide()) || o.getSymbol() == null) continue;
            String key = o.getSymbol().toUpperCase(Locale.ROOT);
            OrderRecord cur = out.get(key);
            if (cur == null || o.getPlacedAt().isAfter(cur.getPlacedAt())) out.put(key, o);
        }
        return out;
    }

    public List<OrderView> orders(Instant from, Instant to, String strategyFilter) {
        syncTodaysFills();
        return repo.findAll().stream()
                .filter(o -> from == null || !o.getPlacedAt().isBefore(from))
                .filter(o -> to   == null || !o.getPlacedAt().isAfter(to))
                .filter(o -> strategyFilter == null
                        || strategyFilter.equalsIgnoreCase(nullSafe(o.getIndicator())))
                .sorted(Comparator.comparing(OrderRecord::getPlacedAt).reversed())
                .map(OrderView::of)
                .toList();
    }

    public List<PotentialView> potentialOrders(Instant from, Instant to, String strategyFilter) {
        List<PotentialOrder> all = potentialRepo.findAll();
        List<PotentialOrder> inRange = new ArrayList<>();
        Set<String> symbols = new HashSet<>();
        for (PotentialOrder p : all) {
            if (from != null && p.getPlacedAt().isBefore(from)) continue;
            if (to   != null && p.getPlacedAt().isAfter(to))   continue;
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(nullSafe(p.getIndicator()))) continue;
            inRange.add(p);
            if (p.getSymbol() != null) symbols.add(p.getSymbol());
        }
        Map<String, Double> ltps = fetchLtpBatch(symbols);
        List<PotentialView> out = new ArrayList<>();
        for (PotentialOrder p : inRange) {
            double ltp = ltps.getOrDefault(p.getSymbol(), 0.0);
            double signalPx = p.getSignalPrice() == null ? 0.0 : p.getSignalPrice();
            double pct = 0.0;
            if (signalPx > 0 && ltp > 0) {
                double raw = ((ltp - signalPx) / signalPx) * 100.0;
                pct = "SELL".equalsIgnoreCase(p.getSide()) ? -raw : raw;
            }
            out.add(new PotentialView(p, ltp, pct));
        }
        out.sort(Comparator.comparing((PotentialView v) -> v.placedAt).reversed());
        return out;
    }

    private Map<String, Double> fetchLtpBatch(Set<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        Map<String, Double> out = new HashMap<>();
        try {
            String[] keys = symbols.stream().map(s -> "NSE:" + s).toArray(String[]::new);
            Map<String, LTPQuote> q = kite.getLTP(keys);
            for (String s : symbols) {
                LTPQuote lq = q.get("NSE:" + s);
                if (lq != null) out.put(s, lq.lastPrice);
            }
        } catch (Throwable t) {
            log.warn("LTP batch failed: {}", t.getMessage());
        }
        return out;
    }

    public List<DailyPoint> dailySeries(Instant from, Instant to, String strategyFilter) {
        syncTodaysFills();
        // Same SELL-anchored FIFO logic as summary() / closedTrades(): walk the
        // full history to build lots, but only emit the daily P&L point on the
        // SELL's date, and only if that SELL is inside [from, to]. Filtering
        // BUYs by range would silently drop the cost basis of positions bought
        // before the window, badly skewing Sharpe / max-DD downstream.
        List<OrderRecord> records = repo.findAll();
        Map<String, List<OrderRecord>> byKey = new LinkedHashMap<>();
        for (OrderRecord o : records) {
            String strategy = nullSafe(o.getIndicator());
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(strategy)) continue;
            byKey.computeIfAbsent(strategy + "|" + o.getSymbol(), k -> new ArrayList<>()).add(o);
        }
        Map<LocalDate, Double> byDay = new TreeMap<>();
        for (var e : byKey.entrySet()) {
            List<OrderRecord> orders = e.getValue();
            orders.sort(Comparator.comparing(OrderRecord::getPlacedAt));
            Deque<double[]> longs = new ArrayDeque<>();
            for (OrderRecord o : orders) {
                Integer q = o.getFilledQty();
                Double  p = o.getAvgFillPrice();
                if (q == null || p == null || q <= 0 || p <= 0) continue;
                if ("BUY".equalsIgnoreCase(o.getSide())) {
                    longs.addLast(new double[]{ q, p });
                } else if ("SELL".equalsIgnoreCase(o.getSide())) {
                    Instant exitAt = o.getPlacedAt();
                    boolean inRange = (from == null || !exitAt.isBefore(from))
                                   && (to   == null || !exitAt.isAfter(to));
                    int rem = q;
                    LocalDate day = exitAt.atZone(IST).toLocalDate();
                    while (rem > 0 && !longs.isEmpty()) {
                        double[] lot = longs.peekFirst();
                        int take = (int) Math.min(lot[0], rem);
                        double pnl = (p - lot[1]) * take;
                        if (inRange) byDay.merge(day, pnl, Double::sum);
                        lot[0] -= take;
                        rem -= take;
                        if (lot[0] == 0) longs.pollFirst();
                    }
                }
            }
        }
        List<DailyPoint> out = new ArrayList<>();
        byDay.forEach((d, v) -> out.add(new DailyPoint(d.toString(), v)));
        return out;
    }

    private static String nullSafe(String s) { return s == null ? "UNKNOWN" : s; }
    private static double parseD(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
    }

    private record Fill(double avgPrice, int filledQty, String status) {}
    public static class StrategyStats { double realizedPnl; int trades; }
    public record StrategyRow(String strategy, double realizedPnl, int trades) {}

    public static class OpenPosition {
        public String symbol;
        public String strategy;
        public int quantity;
        public double buyPrice;
        public double sellPrice;
        public double ltp;
        public double unrealizedPnl;
        public double pnlPct;
        // Chartink-bracket fields, populated from the opening BUY's OrderRecord. Nullable.
        public String sector;
        public String industry;
        public String alertName;
        public Double stopLossPrice;
        public Double targetPrice;
        public Long   kiteOcoGttId;
        public String kiteOrderId;
        public Instant openedAt;
        public OpenPosition(String s, String st, int q, double b, double sell, double l, double u, double pct) {
            symbol = s; strategy = st; quantity = q;
            buyPrice = b; sellPrice = sell; ltp = l;
            unrealizedPnl = u; pnlPct = pct;
        }
    }

    public record DailyPoint(String date, double pnl) {}

    public record Summary(double realizedPnl, double unrealizedPnl, int totalTrades,
                          int winning, int losing, List<StrategyRow> byStrategy,
                          List<OpenPosition> openPositions) {}

    public static class OrderView {
        public Long id;
        public Instant placedAt;
        public String symbol;
        public String side;
        public String indicator;
        public String orderType;
        public String kiteOrderId;
        public Long kiteGttId;
        public String reason;
        public String status;
        public Integer filledQty;
        public Double  avgPrice;
        // Chartink-bracket fields
        public String alertName;
        public String sector;
        public String industry;
        public Double stopLossPrice;
        public Double targetPrice;
        public Long   kiteOcoGttId;
        public static OrderView of(OrderRecord o) {
            OrderView v = new OrderView();
            v.id = o.getId();
            v.placedAt = o.getPlacedAt();
            v.symbol = o.getSymbol();
            v.side = o.getSide();
            v.indicator = o.getIndicator();
            v.orderType = o.getOrderType();
            v.kiteOrderId = o.getKiteOrderId();
            v.kiteGttId = o.getKiteGttId();
            v.reason = o.getReason();
            v.status = o.getStatus() == null ? "—" : o.getStatus();
            v.filledQty = o.getFilledQty();
            v.avgPrice = o.getAvgFillPrice();
            v.alertName = o.getAlertName();
            v.sector    = o.getSector();
            v.industry  = o.getIndustry();
            v.stopLossPrice = o.getStopLossPrice();
            v.targetPrice   = o.getTargetPrice();
            v.kiteOcoGttId  = o.getKiteOcoGttId();
            return v;
        }
    }

    public static class PotentialView {
        public Long id;
        public Instant placedAt;
        public String symbol;
        public String side;
        public String indicator;
        public Double signalPrice;
        public Integer quantity;
        public String reason;
        public String detail;
        public double ltp;
        public double pnlPct;
        PotentialView(PotentialOrder p, double ltp, double pnlPct) {
            this.id = p.getId();
            this.placedAt = p.getPlacedAt();
            this.symbol = p.getSymbol();
            this.side = p.getSide();
            this.indicator = p.getIndicator();
            this.signalPrice = p.getSignalPrice();
            this.quantity = p.getQuantity();
            this.reason = p.getReason();
            this.detail = p.getDetail();
            this.ltp = ltp;
            this.pnlPct = pnlPct;
        }
    }

    // =============== Chartink dashboard extensions ===============

    /**
     * FIFO-matched closed trade (BUY→SELL pair, quantity possibly less than the
     * BUY lot if it was partially closed by an earlier SELL). Emitted only for
     * fully-filled legs on both sides so P&L numbers are honest.
     */
    public record ClosedTrade(
            String symbol,
            String sector,
            String industry,
            String alertName,
            Instant entryAt, double entryPx,
            Instant exitAt,  double exitPx,
            int quantity,
            double pnl, double pnlPct,
            long holdingHours
    ) {}

    /** Sector realized-P&L rollup for the sector-performance panel. */
    public record SectorRow(String sector, double realizedPnl, int trades, int wins, int losses) {}

    /**
     * FIFO-match every BUY→SELL pair within the range for the given strategy,
     * emitting one row per matched quantity slice. Metadata (sector/alertName)
     * is inherited from the opening BUY.
     */
    public List<ClosedTrade> closedTrades(Instant from, Instant to, String strategyFilter) {
        syncTodaysFills();
        List<OrderRecord> records = repo.findAll();
        Map<String, List<OrderRecord>> bySym = new LinkedHashMap<>();
        for (OrderRecord o : records) {
            String strategy = nullSafe(o.getIndicator());
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(strategy)) continue;
            bySym.computeIfAbsent(o.getSymbol() == null ? "" : o.getSymbol().toUpperCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(o);
        }
        List<ClosedTrade> out = new ArrayList<>();
        for (var e : bySym.entrySet()) {
            List<OrderRecord> orders = e.getValue();
            orders.sort(Comparator.comparing(OrderRecord::getPlacedAt));
            Deque<OpenLot> longs = new ArrayDeque<>();
            for (OrderRecord o : orders) {
                Integer q = o.getFilledQty();
                Double  p = o.getAvgFillPrice();
                if (q == null || p == null || q <= 0 || p <= 0) continue;
                if ("BUY".equalsIgnoreCase(o.getSide())) {
                    longs.addLast(new OpenLot(q, p, o));
                } else if ("SELL".equalsIgnoreCase(o.getSide())) {
                    int remaining = q;
                    Instant exitAt = o.getPlacedAt();
                    double exitPx = p;
                    while (remaining > 0 && !longs.isEmpty()) {
                        OpenLot lot = longs.peekFirst();
                        int take = Math.min(lot.qty, remaining);
                        double tradePnl = (exitPx - lot.price) * take;
                        double tradePnlPct = lot.price > 0 ? ((exitPx - lot.price) / lot.price) * 100.0 : 0.0;
                        long hours = Duration.between(lot.src.getPlacedAt(), exitAt).toHours();
                        // Only emit for entries in [from, to]. Anchoring on the
                        // SELL date is what "closed within range" naturally means.
                        if ((from == null || !exitAt.isBefore(from))
                                && (to == null || !exitAt.isAfter(to))) {
                            out.add(new ClosedTrade(
                                    e.getKey(),
                                    lot.src.getSector(),
                                    lot.src.getIndustry(),
                                    lot.src.getAlertName(),
                                    lot.src.getPlacedAt(), lot.price,
                                    exitAt, exitPx,
                                    take, tradePnl, tradePnlPct, hours
                            ));
                        }
                        lot.qty -= take;
                        remaining -= take;
                        if (lot.qty == 0) longs.pollFirst();
                    }
                }
            }
        }
        out.sort(Comparator.comparing(ClosedTrade::exitAt).reversed());
        return out;
    }

    /** Group {@link #closedTrades} by sector, summing realized P&L and counts. */
    public List<SectorRow> sectorPerformance(Instant from, Instant to, String strategyFilter) {
        Map<String, double[]> agg = new LinkedHashMap<>(); // key -> [pnl, trades, wins, losses]
        for (ClosedTrade t : closedTrades(from, to, strategyFilter)) {
            String sec = (t.sector() == null || t.sector().isBlank()) ? "UNKNOWN" : t.sector();
            double[] r = agg.computeIfAbsent(sec, k -> new double[4]);
            r[0] += t.pnl();
            r[1] += 1;
            if (t.pnl() > 0) r[2] += 1; else if (t.pnl() < 0) r[3] += 1;
        }
        List<SectorRow> out = new ArrayList<>();
        agg.forEach((k, v) -> out.add(new SectorRow(k, v[0], (int) v[1], (int) v[2], (int) v[3])));
        out.sort(Comparator.comparingDouble(SectorRow::realizedPnl).reversed());
        return out;
    }

    /**
     * When our bracket OCO fires, the resulting SELL originates on the Kite
     * side (not from OrderUp) so it never gets written to {@link OrderRecord}.
     * Scan Kite's orderbook for COMPLETE SELLs whose symbol matches one of our
     * tracked BUYs for the strategy and materialize them as {@link OrderRecord}
     * rows so the P&L pipeline and closed-trades table pick them up.
     *
     * <p>Idempotent — we key on Kite's {@code order_id} and skip if we've
     * already imported it.
     */
    public int syncExternalSells(String strategyFilter) {
        List<OrderRecord> existing = repo.findAll();
        Set<String> ourOrderIds = new HashSet<>();
        Set<String> trackedSymbols = new HashSet<>();
        for (OrderRecord o : existing) {
            if (o.getKiteOrderId() != null) ourOrderIds.add(o.getKiteOrderId());
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(nullSafe(o.getIndicator()))) continue;
            if (o.getSymbol() != null) trackedSymbols.add(o.getSymbol().toUpperCase(Locale.ROOT));
        }
        if (trackedSymbols.isEmpty()) return 0;
        int imported = 0;
        try {
            List<Order> kiteOrders = kite.getOrders();
            for (Order o : kiteOrders) {
                if (o.orderId == null || o.transactionType == null || o.tradingSymbol == null) continue;
                if (!"SELL".equalsIgnoreCase(o.transactionType)) continue;
                if (!"COMPLETE".equalsIgnoreCase(o.status)) continue;
                if (ourOrderIds.contains(o.orderId)) continue;
                String sym = o.tradingSymbol.toUpperCase(Locale.ROOT);
                if (!trackedSymbols.contains(sym)) continue;
                double avg = parseD(o.averagePrice);
                int filled = (int) parseD(o.filledQuantity);
                if (avg <= 0 || filled <= 0) continue;
                Instant when = o.orderTimestamp != null
                        ? o.orderTimestamp.toInstant()
                        : Instant.now();
                OrderRecord rec = new OrderRecord(when, sym, "SELL",
                        strategyFilter == null ? "IMPORTED" : strategyFilter,
                        "MARKET", o.orderId, null,
                        "Auto-imported from Kite orderbook (bracket OCO exit)");
                rec.setStatus("COMPLETE");
                rec.setFilledQty(filled);
                rec.setAvgFillPrice(avg);
                // Inherit sector/industry/alert from the matching BUY for tidy audit.
                existing.stream()
                        .filter(x -> "BUY".equalsIgnoreCase(x.getSide()))
                        .filter(x -> sym.equalsIgnoreCase(x.getSymbol()))
                        .max(Comparator.comparing(OrderRecord::getPlacedAt))
                        .ifPresent(buy -> {
                            rec.setSector(buy.getSector());
                            rec.setIndustry(buy.getIndustry());
                            rec.setAlertName(buy.getAlertName());
                        });
                repo.save(rec);
                imported++;
            }
        } catch (Throwable t) {
            log.warn("syncExternalSells failed: {}", t.getMessage());
        }
        if (imported > 0) log.info("Imported {} external SELLs from Kite orderbook", imported);
        return imported;
    }

    /**
     * Reconcile positions the operator can see on Kite as "no longer held" but
     * for which OrderUp still has a BUY {@link OrderRecord} and no matching
     * SELL. The typical cause: the bracket OCO's stop-loss (or target) leg
     * triggered on a previous trading day, so Kite's intraday {@code /orders}
     * feed no longer surfaces the sell — {@link #syncExternalSells(String)}
     * can never pick it up on day+N.
     *
     * <p>For each such symbol we call {@code kite.getGTT(kiteOcoGttId)} on the
     * OCO recorded at BUY time. Kite persists the two-leg GTT with its final
     * state indefinitely: the triggered leg's {@code result.orderResult.orderId}
     * points at the SELL order Kite generated. We then hit
     * {@code getOrderHistory(orderId)} to get the actual fill price + timestamp
     * for that SELL, and materialize an {@link OrderRecord} with
     * {@code status=EXTERNALLY_CLOSED}. If the GTT lookup fails (Kite retention,
     * network, wrong id), we fall back to the recorded SL price as an
     * approximation so the position at least stops "silently vanishing".
     *
     * <p>Idempotent: symbols that already have any SELL record are skipped.
     */
    public int reconcileExternallyClosed(String strategyFilter) {
        List<OrderRecord> all = repo.findAll();
        Map<String, OrderRecord> latestBuy = latestBuyBySymbol(all);
        if (latestBuy.isEmpty()) return 0;

        Set<String> hasSell = new HashSet<>();
        for (OrderRecord o : all) {
            if ("SELL".equalsIgnoreCase(o.getSide()) && o.getSymbol() != null) {
                hasSell.add(o.getSymbol().toUpperCase(Locale.ROOT));
            }
        }

        // Union of "currently owned" symbols per Kite: net day-positions + holdings.
        Set<String> owned = new HashSet<>();
        try {
            Map<String, List<Position>> pos = kite.getPositions();
            List<Position> net = pos == null ? List.of() : pos.getOrDefault("net", List.of());
            for (Position p : net) {
                if (p.tradingSymbol == null) continue;
                if (p.netQuantity > 0) owned.add(p.tradingSymbol.toUpperCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
            // Non-fatal — falling through to holdings-only is fine.
        }
        for (String s : holdings.snapshot().keySet()) owned.add(s.toUpperCase(Locale.ROOT));

        int imported = 0;
        for (var e : latestBuy.entrySet()) {
            String sym = e.getKey();
            OrderRecord buy = e.getValue();
            if (owned.contains(sym)) continue;
            if (hasSell.contains(sym)) continue;
            if (strategyFilter != null
                    && !strategyFilter.equalsIgnoreCase(nullSafe(buy.getIndicator()))) continue;

            int qty = buy.getFilledQty() != null ? buy.getFilledQty() : 0;
            if (qty <= 0) continue;

            Double exitPrice = null;
            String exitOrderId = null;
            Instant exitTime = Instant.now();
            String exitTag = "UNKNOWN";
            String note = null;

            Long gttId = buy.getKiteOcoGttId();
            if (gttId != null) {
                try {
                    GTT gtt = kite.getGTT(gttId.intValue());
                    if (gtt != null && gtt.orders != null) {
                        for (int i = 0; i < gtt.orders.size(); i++) {
                            GTT.GTTOrder leg = gtt.orders.get(i);
                            if (leg == null || leg.result == null) continue;
                            GTT.GTTOrderResult or = leg.result.orderResult;
                            if (or == null || or.orderId == null || or.orderId.isBlank()) continue;
                            exitOrderId = or.orderId;
                            exitTag = (i == 0) ? "SL" : "TGT";
                            // Prefer triggeredAtPrice (what Kite recorded at trigger).
                            if (leg.result.triggeredAtPrice > 0) {
                                exitPrice = leg.result.triggeredAtPrice;
                            } else if (leg.price > 0) {
                                exitPrice = (double) leg.price;
                            }
                            // Enrich with the actual COMPLETE fill from order history.
                            try {
                                List<Order> hist = kite.getOrderHistory(exitOrderId);
                                for (Order h : hist) {
                                    if (!"COMPLETE".equalsIgnoreCase(h.status)) continue;
                                    double avg = parseD(h.averagePrice);
                                    if (avg > 0) exitPrice = avg;
                                    if (h.orderTimestamp != null) {
                                        exitTime = h.orderTimestamp.toInstant();
                                    }
                                    break;
                                }
                            } catch (Throwable ohEx) {
                                log.debug("reconcile {}: getOrderHistory({}) failed: {}",
                                        sym, exitOrderId, ohEx.getMessage());
                            }
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log.warn("reconcile: getGTT({}) failed for {}: {}",
                            gttId, sym, t.getMessage());
                }
            }

            if (exitPrice == null || exitPrice <= 0) {
                // Fallback: assume the SL leg fired (statistically more common
                // than TGT in a losing session) and use the recorded SL price.
                Double sl = buy.getStopLossPrice();
                if (sl != null && sl > 0) {
                    exitPrice = sl;
                    exitTag = "SL_APPROX";
                    note = "Exit price approximated from recorded SL — GTT lookup unavailable.";
                } else {
                    exitPrice = 0.0;
                    exitTag = "UNKNOWN";
                    note = "Exit price unknown — GTT not found and no SL recorded.";
                }
            }

            String reason = "Auto-reconciled: OCO " + exitTag
                    + " triggered on Kite; position no longer in holdings/positions."
                    + (note == null ? "" : " " + note);
            OrderRecord sell = new OrderRecord(exitTime, sym, "SELL",
                    nullSafe(buy.getIndicator()),
                    "MARKET", exitOrderId, null, reason);
            sell.setStatus("EXTERNALLY_CLOSED");
            sell.setFilledQty(qty);
            sell.setAvgFillPrice(exitPrice);
            sell.setSector(buy.getSector());
            sell.setIndustry(buy.getIndustry());
            sell.setAlertName(buy.getAlertName());
            repo.save(sell);
            imported++;
            log.info("Reconciled externally-closed position: {} qty={} exit={} tag={} (gtt={})",
                    sym, qty, exitPrice, exitTag, gttId);
        }
        if (imported > 0) log.info("Reconciled {} externally-closed positions.", imported);
        return imported;
    }

    /**
     * Aggregate KPI bundle for the dashboard hero strip. Wraps the existing
     * summary + computed ROI %, Sharpe and max-drawdown numbers.
     */
    public KpiBundle kpis(Instant from, Instant to, String strategyFilter) {
        syncExternalSells(strategyFilter);
        Summary s = summary(from, to, strategyFilter);
        double deployed = 0.0;
        int openCount = 0;
        for (OpenPosition op : s.openPositions()) {
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(op.strategy)) continue;
            deployed += op.buyPrice * Math.max(op.quantity, 0);
            openCount++;
        }
        // Cost basis of realized closed trades in-range = denominator for realized ROI %.
        double realizedCost = 0.0;
        for (ClosedTrade t : closedTrades(from, to, strategyFilter)) {
            realizedCost += t.entryPx() * t.quantity();
        }
        double realizedRoiPct   = realizedCost > 0 ? (s.realizedPnl()   / realizedCost) * 100.0 : 0.0;
        double unrealizedRoiPct = deployed > 0     ? (s.unrealizedPnl() / deployed)     * 100.0 : 0.0;
        double totalPnl = s.realizedPnl() + s.unrealizedPnl();
        // Denominator = the greater of "still-tied-up cost" (open positions)
        // and "cost that cycled through closed trades in this range". Using
        // the SUM as the previous impl did double-counts recycled capital:
        // if you buy X for ₹5k, close at a loss, buy Y for ₹5k, capital at
        // risk was always ₹5k — never ₹10k — so ROI on ₹10k halves the number.
        double roiBase = Math.max(deployed, realizedCost);
        double totalRoiPct = roiBase > 0 ? (totalPnl / roiBase) * 100.0 : 0.0;
        int total = s.winning() + s.losing();
        double winRate = total > 0 ? (s.winning() * 100.0 / total) : 0.0;
        List<DailyPoint> daily = dailySeries(from, to, strategyFilter);
        double sharpe = sharpeRatio(daily);
        // Max DD normalized against deployed capital rather than "peak profit",
        // so a series that never crosses 0 still reports the real drawdown.
        double maxDD  = maxDrawdown(daily, Math.max(deployed, realizedCost));
        // Count only BUY orders in-range for uniqueSymbolsCount so a reconciled
        // SELL-only row (whose BUY was earlier) doesn't inflate the number.
        Set<String> uniqSymbols = new HashSet<>();
        for (OrderRecord o : repo.findAll()) {
            if (!"BUY".equalsIgnoreCase(o.getSide())) continue;
            if (strategyFilter != null && !strategyFilter.equalsIgnoreCase(nullSafe(o.getIndicator()))) continue;
            if (o.getPlacedAt() != null && from != null && o.getPlacedAt().isBefore(from)) continue;
            if (o.getPlacedAt() != null && to   != null && o.getPlacedAt().isAfter(to))   continue;
            if (o.getSymbol() != null) uniqSymbols.add(o.getSymbol().toUpperCase(Locale.ROOT));
        }
        return new KpiBundle(
                deployed, s.unrealizedPnl(), unrealizedRoiPct,
                s.realizedPnl(), realizedRoiPct,
                totalPnl, totalRoiPct,
                s.totalTrades(), s.winning(), s.losing(), winRate,
                openCount, uniqSymbols.size(),
                sharpe, maxDD
        );
    }

    public record KpiBundle(
            double capitalDeployed,
            double unrealizedPnl, double unrealizedRoiPct,
            double realizedPnl,   double realizedRoiPct,
            double totalPnl,      double totalRoiPct,
            int tradesCount, int winning, int losing, double winRatePct,
            int openPositionsCount, int uniqueSymbolsCount,
            double sharpe, double maxDrawdownPct
    ) {}

    /** Annualized Sharpe from a daily P&L series. Returns 0 if fewer than 5 days. */
    private static double sharpeRatio(List<DailyPoint> daily) {
        if (daily == null || daily.size() < 5) return 0.0;
        double sum = 0.0;
        for (DailyPoint d : daily) sum += d.pnl();
        double mean = sum / daily.size();
        double var = 0.0;
        for (DailyPoint d : daily) var += Math.pow(d.pnl() - mean, 2);
        var /= daily.size();
        double sd = Math.sqrt(var);
        if (sd == 0) return 0.0;
        // ~252 trading days/yr
        return (mean / sd) * Math.sqrt(252);
    }

    /**
     * Max drawdown as a % of {@code capitalBase} (typically the deployed
     * capital / peak cost tied up). Unlike a "peak-profit" normaliser, this
     * still reports a meaningful number when cumulative P&L never crosses
     * zero — a portfolio that only loses money should still show its worst
     * trough as a % of the money at risk, not silently report 0%.
     *
     * <p>Formula:
     * <pre>
     *   cum_i = Σ pnl[0..i]
     *   peak_i = max(0, cum_0..i)                // baseline = starting equity of 0
     *   dd_i   = min(0, cum_i - peak_i)          // always ≤ 0
     *   maxDD_₹ = min(dd_i)
     *   maxDD_% = maxDD_₹ / max(capitalBase, 1) × 100
     * </pre>
     */
    private static double maxDrawdown(List<DailyPoint> daily, double capitalBase) {
        if (daily == null || daily.isEmpty()) return 0.0;
        double cum = 0.0, peak = 0.0, maxDdRupees = 0.0;
        for (DailyPoint d : daily) {
            cum += d.pnl();
            if (cum > peak) peak = cum;
            double dd = cum - peak; // ≤ 0
            if (dd < maxDdRupees) maxDdRupees = dd;
        }
        double base = Math.max(capitalBase, 1.0);
        return (maxDdRupees / base) * 100.0;
    }

    /** Mutable open-lot for the FIFO walk; carries the source BUY for metadata. */
    private static final class OpenLot {
        int qty; final double price; final OrderRecord src;
        OpenLot(int q, double p, OrderRecord src) { this.qty = q; this.price = p; this.src = src; }
    }
}

