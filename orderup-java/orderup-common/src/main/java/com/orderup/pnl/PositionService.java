package com.orderup.pnl;

import com.orderup.orders.HoldingsService;
import com.orderup.orders.OrderRecord;
import com.orderup.orders.OrderRecordRepository;
import com.orderup.orders.PotentialOrder;
import com.orderup.orders.PotentialOrderRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

        List<OrderRecord> records = repo.findAll();
        Map<String, List<OrderRecord>> byKey = new LinkedHashMap<>();
        for (OrderRecord o : records) {
            if (from != null && o.getPlacedAt().isBefore(from)) continue;
            if (to   != null && o.getPlacedAt().isAfter(to))   continue;
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
                    int remaining = q;
                    while (remaining > 0 && !longs.isEmpty()) {
                        double[] lot = longs.peekFirst();
                        int take = (int) Math.min(lot[0], remaining);
                        double tradePnl = (p - lot[1]) * take;
                        realized += tradePnl;
                        totalTrades += 1;
                        if (tradePnl > 0) winning++; else if (tradePnl < 0) losing++;
                        StrategyStats s = byStrategy.computeIfAbsent(strategy, k -> new StrategyStats());
                        s.realizedPnl += tradePnl;
                        s.trades += 1;
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
        List<OrderRecord> records = repo.findAll();
        Map<String, List<OrderRecord>> byKey = new LinkedHashMap<>();
        for (OrderRecord o : records) {
            if (from != null && o.getPlacedAt().isBefore(from)) continue;
            if (to   != null && o.getPlacedAt().isAfter(to))   continue;
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
                    int rem = q;
                    LocalDate day = o.getPlacedAt().atZone(IST).toLocalDate();
                    while (rem > 0 && !longs.isEmpty()) {
                        double[] lot = longs.peekFirst();
                        int take = (int) Math.min(lot[0], rem);
                        double pnl = (p - lot[1]) * take;
                        byDay.merge(day, pnl, Double::sum);
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
}

