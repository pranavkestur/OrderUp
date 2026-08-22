package com.orderup.orders;

import com.orderup.config.TradingProperties;
import com.orderup.notify.TelegramNotifier;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.GTTParams;
import com.zerodhatech.models.GTTParams.GTTOrderParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;

/**
 * Places orders at Kite, records them in the DB, and — when the operator has clicked
 * "Disable orders", or when placement is rejected — logs the signal as a
 * {@link PotentialOrder} instead so the dashboard can show "what would have happened".
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    /** Persisted flag key for "orders disabled" — see {@link RuntimeFlag}. */
    private static final String FLAG_ORDERS_DISABLED = "ordersDisabled";

    private final KiteConnect kite;
    private final TradingProperties trading;
    private final OrderRecordRepository repo;
    private final PotentialOrderRepository potentialRepo;
    private final TelegramNotifier notifier;
    private final HoldingsService holdings;
    private final RuntimeFlagRepository flags;

    /**
     * Live toggle from the UI. When true, scans/signals still run but no order
     * is sent to Kite — the signal is written to {@link PotentialOrder} and a
     * "[DRY]" Telegram alert is sent so the operator can still see activity.
     *
     * <p><b>Persistence:</b> the value is stored in the {@code RUNTIME_FLAG} table
     * and reloaded in {@link #loadPersistedFlag()}. If no row exists (fresh DB),
     * we default to <b>disabled</b> (safe by default) so an unattended restart
     * mid-market never accidentally fires live orders.</p>
     */
    private volatile boolean orderingDisabled = true;

    public OrderService(KiteConnect kite, TradingProperties trading,
                        OrderRecordRepository repo, PotentialOrderRepository potentialRepo,
                        TelegramNotifier notifier, HoldingsService holdings,
                        RuntimeFlagRepository flags) {
        this.kite = kite;
        this.trading = trading;
        this.repo = repo;
        this.potentialRepo = potentialRepo;
        this.notifier = notifier;
        this.holdings = holdings;
        this.flags = flags;
    }

    @PostConstruct
    void loadPersistedFlag() {
        try {
            var row = flags.findById(FLAG_ORDERS_DISABLED);
            if (row.isPresent()) {
                orderingDisabled = Boolean.parseBoolean(row.get().getValue());
                log.info("Restored ordersDisabled={} from RUNTIME_FLAG (last updated {}).",
                        orderingDisabled, row.get().getUpdatedAt());
            } else {
                log.warn("No persisted ordersDisabled flag found — defaulting to DISABLED (safe). "
                        + "Enable via POST /control/orders/enable when you're ready.");
            }
        } catch (Throwable t) {
            // If the DB read fails for any reason, keep the safe default and press on.
            log.warn("Could not read RUNTIME_FLAG.{} — keeping default (disabled): {}",
                    FLAG_ORDERS_DISABLED, t.getMessage());
        }
    }

    public boolean isOrderingDisabled() { return orderingDisabled; }
    public void disableOrders() {
        orderingDisabled = true;
        persistFlag(true);
        log.info("Order placement DISABLED via API — signals will be logged as potential orders only.");
    }
    public void enableOrders() {
        orderingDisabled = false;
        persistFlag(false);
        log.info("Order placement ENABLED via API.");
    }

    private void persistFlag(boolean disabled) {
        try {
            var row = flags.findById(FLAG_ORDERS_DISABLED)
                    .orElseGet(() -> new RuntimeFlag(FLAG_ORDERS_DISABLED, String.valueOf(disabled)));
            row.setValue(String.valueOf(disabled));
            flags.save(row);
        } catch (Throwable t) {
            // Do NOT block the toggle — in-memory value is still authoritative for this JVM.
            log.warn("Failed to persist ordersDisabled={}: {}", disabled, t.getMessage());
        }
    }

    /**
     * Attempts to place an order. Returns true if the signal was handled (order sent,
     * paper-recorded, or intentionally logged as a potential-only signal); false only
     * when a genuinely retryable failure occurred.
     */
    public boolean placeSignalOrder(String symbol, String side, String indicator,
                                    String reason, double lastPrice, int quantity) {

        // 1. Dry-run mode wins over everything else — respect the "Disable orders" click.
        if (orderingDisabled) {
            savePotential(symbol, side, indicator, lastPrice, quantity, "DRY_RUN", reason);
            notifier.send("🟡 [DRY] " + side + " " + symbol + " qty=" + quantity
                    + " — " + reason + " (order placement disabled)");
            log.info("[DRY] {} {} qty={} on {} — {}", side, symbol, quantity, indicator, reason);
            return true;
        }

        // 2. CNC SELL guard — we can't sell what we don't own.
        if ("SELL".equalsIgnoreCase(side)) {
            int owned = holdings.quantity(symbol);
            if (owned < quantity) {
                savePotential(symbol, side, indicator, lastPrice, quantity,
                        "NO_HOLDINGS_FOR_SELL",
                        "Own " + owned + " < required " + quantity + ". " + reason);
                log.info("Skipping SELL for {} - hold {} < required {}", symbol, owned, quantity);
                return true;
            }
        }

        // 3. Paper mode.
        if (trading.paperMode()) {
            log.info("[PAPER] {} {} qty={} on {} - {}", side, symbol, quantity, indicator, reason);
            OrderRecord rec = new OrderRecord(Instant.now(), symbol, side, indicator,
                    "PAPER", null, null, reason);
            rec.setStatus("PAPER");
            rec.setFilledQty(quantity);
            rec.setAvgFillPrice(lastPrice);
            repo.save(rec);
            notifier.send("[PAPER] " + side + " " + symbol + " - " + reason);
            return true;
        }

        // 4. Real Kite placement.
        try {
            String orderId = placeMarketOrder(symbol, side, quantity);
            log.info("{} order placed for {} - id={} reason={}", side, symbol, orderId, reason);
            OrderRecord rec = new OrderRecord(Instant.now(), symbol, side, indicator,
                    "MARKET", orderId, null, reason);
            rec.setStatus("OPEN");
            repo.save(rec);
            notifier.send("✅ " + side + " " + symbol + " qty=" + quantity
                    + " placed (id " + orderId + ") - " + reason);
            return true;
        } catch (Throwable e) {
            String msg = describe(e);
            if (msg.toLowerCase().contains("market") && msg.toLowerCase().contains("closed")) {
                log.info("Markets closed for {} - placing GTT instead", symbol);
                try {
                    Long gttId = placeGtt(symbol, side, lastPrice, quantity);
                    OrderRecord rec = new OrderRecord(Instant.now(), symbol, side, indicator,
                            "GTT", null, gttId, reason);
                    rec.setStatus("GTT");
                    repo.save(rec);
                    notifier.send("🕒 GTT " + side + " " + symbol + " qty=" + quantity
                            + " placed (id " + gttId + ") - " + reason);
                    return true;
                } catch (Throwable gttErr) {
                    String gttMsg = describe(gttErr);
                    log.error("GTT fallback failed for {}: {}", symbol, gttMsg);
                    savePotential(symbol, side, indicator, lastPrice, quantity,
                            "KITE_REJECTED", "GTT fallback failed: " + gttMsg);
                    notifier.send("❌ Order FAILED for " + symbol + ": " + gttMsg);
                    return false;
                }
            } else {
                log.error("Order failed for {}: {}", symbol, msg);
                savePotential(symbol, side, indicator, lastPrice, quantity,
                        "KITE_REJECTED", msg);
                notifier.send("❌ Order FAILED for " + symbol + ": " + msg);
                return false;
            }
        }
    }

    private void savePotential(String symbol, String side, String indicator,
                               double signalPrice, int quantity,
                               String reason, String detail) {
        try {
            potentialRepo.save(new PotentialOrder(Instant.now(), symbol, side, indicator,
                    signalPrice, quantity, reason, detail));
        } catch (Throwable t) {
            log.warn("Failed to persist PotentialOrder for {}: {}", symbol, t.getMessage());
        }
    }

    private static String describe(Throwable e) {
        if (e instanceof com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ke) {
            return "KiteException code=" + ke.code + " message=" + ke.message;
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private String placeMarketOrder(String symbol, String side, int quantity) throws Throwable {
        OrderParams p = new OrderParams();
        p.exchange = trading.exchange();
        p.tradingsymbol = symbol;
        p.transactionType = side;
        p.quantity = quantity;
        p.orderType = trading.orderType();
        p.product = trading.product();
        p.validity = "DAY";
        p.marketProtection = 1.0;
        Order o = kite.placeOrder(p, trading.variety());
        return o.orderId;
    }

    private Long placeGtt(String symbol, String side, double lastPrice, int quantity) throws Throwable {
        double band = trading.fallbackPricePctBand();
        double trigger, limit;
        if ("BUY".equals(side)) {
            trigger = round(lastPrice * (1 - band));
            limit   = round(lastPrice * (1 + band));
        } else {
            trigger = round(lastPrice * (1 + band));
            limit   = round(lastPrice * (1 - band));
        }

        GTTParams gtt = new GTTParams();
        gtt.triggerType = "single";
        gtt.tradingsymbol = symbol;
        gtt.exchange = trading.exchange();
        gtt.lastPrice = lastPrice;
        gtt.triggerPrices = new ArrayList<>();
        gtt.triggerPrices.add(trigger);

        GTTOrderParams leg = gtt.new GTTOrderParams();
        leg.transactionType = side;
        leg.quantity = quantity;
        leg.orderType = "LIMIT";
        leg.product = trading.product();
        leg.price = limit;
        gtt.orders = new ArrayList<>();
        gtt.orders.add(leg);

        return (long) kite.placeGTT(gtt).id;
    }

    private static double round(double v) {
        return Math.round(v * 20.0) / 20.0; // NSE tick 0.05
    }
}
