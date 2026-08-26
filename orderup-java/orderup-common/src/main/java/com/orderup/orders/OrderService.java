package com.orderup.orders;

import com.orderup.config.TradingProperties;
import com.orderup.auth.KiteAuthService;
import com.orderup.marketdata.TickSizeService;
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
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final TickSizeService tickSizes;
    private final org.springframework.beans.factory.ObjectProvider<KiteAuthService> authProvider;

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
                        RuntimeFlagRepository flags, TickSizeService tickSizes,
                        org.springframework.beans.factory.ObjectProvider<KiteAuthService> authProvider) {
        this.kite = kite;
        this.trading = trading;
        this.repo = repo;
        this.potentialRepo = potentialRepo;
        this.notifier = notifier;
        this.holdings = holdings;
        this.flags = flags;
        this.tickSizes = tickSizes;
        this.authProvider = authProvider;
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
            maybeHandleAuthFailure(e, msg);
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

    /**
     * If the underlying Kite error looks like an auth failure (403 / stale access token),
     * flip {@link KiteAuthService} to unauthenticated and push a fresh login URL to
     * Telegram so the user can re-login from their phone. Rate-limited inside
     * {@link KiteAuthService#pushLoginLinkToTelegram(String)}.
     */
    private void maybeHandleAuthFailure(Throwable e, String describedMsg) {
        boolean looksAuth = false;
        if (e instanceof com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ke) {
            if (ke.code == 403) looksAuth = true;
            String m = ke.message == null ? "" : ke.message.toLowerCase();
            if (m.contains("api_key") || m.contains("access_token") || m.contains("token")) looksAuth = true;
        }
        String lower = describedMsg == null ? "" : describedMsg.toLowerCase();
        if (lower.contains("code=403") || lower.contains("access_token") || lower.contains("api_key")) {
            looksAuth = true;
        }
        if (!looksAuth) return;
        try {
            KiteAuthService auth = authProvider.getIfAvailable();
            if (auth != null) auth.markUnauthenticated("Kite rejected request: " + describedMsg);
        } catch (Throwable t) {
            log.warn("Failed to push Kite login link to Telegram: {}", t.getMessage());
        }
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

    // ================== Chartink bracket-order support ==================

    /**
     * Per-signal metadata for the bracket-order flow. All fields optional; used
     * by the dashboard for sector/industry roll-ups and by the audit trail.
     */
    public record SignalMeta(String alertName, String sector, String industry,
                             String columnsJson) {
        public static SignalMeta empty() { return new SignalMeta(null, null, null, null); }
    }

    /**
     * Same as {@link #placeSignalOrder} but on a filled BUY also places an OCO
     * (two-leg) GTT carrying a stop-loss and target leg. When either leg
     * triggers, Kite auto-cancels the other. On any failure to place the OCO
     * the BUY is left intact (already filled) and a Telegram warning is sent.
     *
     * @param meta      dashboard/audit metadata; may be {@link SignalMeta#empty()}
     * @return {@code true} on any handled outcome (order sent, paper, dry, GTT
     *         fallback). {@code false} only on a retryable failure of the BUY.
     */
    public boolean placeSignalOrderWithBracket(String symbol, String side, String indicator,
                                               String reason, double lastPrice, int quantity,
                                               SignalMeta meta) {
        boolean primary = placeSignalOrder(symbol, side, indicator, reason, lastPrice, quantity);
        if (!primary) return false;

        // Only BUY legs get a bracket, and only if the risk-management block is
        // configured and enabled. SELL / paper / dry / closed-market-GTT paths
        // are all handled by placeSignalOrder itself and don't need brackets.
        TradingProperties.RiskManagement rm = trading.riskManagement();
        if (rm == null || !rm.enabled()) {
            attachMeta(symbol, side, indicator, meta, null, null, null);
            return true;
        }
        if (!"BUY".equalsIgnoreCase(side) || orderingDisabled || trading.paperMode()) {
            attachMeta(symbol, side, indicator, meta, null, null, null);
            return true;
        }

        // Look up the instrument's actual tick size (lazy-loads the NSE EQ
        // dump once, then O(1) forever). This snaps SL/TGT to a Kite-valid
        // multiple *before* placement — no wasted round trip to discover the
        // tick after a rejection.
        BracketResult br = placeBracket(symbol, quantity, lastPrice,
                rm.targetPctOrDefault(), rm.stopLossPctOrDefault());
        attachMeta(symbol, side, indicator, meta, br.ocoId, br.sl, br.tgt);
        return true;
    }

    /**
     * Immutable outcome of a bracket-placement attempt. {@code ocoId} is
     * {@code null} when the OCO failed (position is unprotected — a Telegram
     * warning has already been sent). {@code sl}/{@code tgt} are the snapped
     * prices we *tried* to place at, useful for logging even on failure.
     */
    public record BracketResult(Long ocoId, double sl, double tgt) {}

    /**
     * Place an OCO bracket around an already-filled long position at
     * {@code refPrice} with {@code targetPct}/{@code stopLossPct} distances.
     * Handles tick snapping, Telegram notification, and returns the result so
     * callers can persist the GTT id. Used both by the normal alert flow (via
     * {@link #placeSignalOrderWithBracket}) and by the admin repair endpoint
     * that re-places brackets for positions where the original OCO was
     * rejected (e.g. legacy tick-mismatch failures before this service knew
     * about per-instrument ticks).
     */
    public BracketResult placeBracket(String symbol, int quantity, double refPrice,
                                      double targetPct, double stopLossPct) {
        double tick = tickSizes.tickFor(symbol);
        double tgt = snapUp  (refPrice * (1 + targetPct),   tick);
        double sl  = snapDown(refPrice * (1 - stopLossPct), tick);
        try {
            Long ocoId = placeOcoGttWithTickRetry(symbol, quantity, refPrice, sl, tgt);
            log.info("[OCO] {} — placed GTT id={} SL={} TGT={} tick={} (ref {})",
                    symbol, ocoId, sl, tgt, tick, refPrice);
            notifier.send("🎯 OCO for " + symbol + " — SL " + sl + " · TGT " + tgt
                    + " (gttId " + ocoId + ")");
            return new BracketResult(ocoId, sl, tgt);
        } catch (Throwable t) {
            String msg = describe(t);
            log.error("[OCO] {} — FAILED to place bracket: {}", symbol, msg);
            notifier.send("⚠️ OCO FAILED for " + symbol + ": " + msg
                    + " — position is unprotected, review manually.");
            return new BracketResult(null, sl, tgt);
        }
    }

    /**
     * Backstop for the rare case where {@link TickSizeService} was unable to
     * fetch the instrument dump (auth glitch, network blip) and returned the
     * NSE default 0.05 for a scrip that actually trades on a coarser tick.
     * Parses Kite's rejection message for {@code "multiple of tick size X"}
     * and re-snaps SL/TGT once. In the common case this never runs.
     */
    private static final Pattern TICK_SIZE_ERR =
            Pattern.compile("multiple of tick size\\s+([0-9]+(?:\\.[0-9]+)?)",
                    Pattern.CASE_INSENSITIVE);

    private Long placeOcoGttWithTickRetry(String symbol, int quantity,
                                          double lastPrice, double sl, double tgt) throws Throwable {
        try {
            return placeOcoGtt(symbol, quantity, lastPrice, sl, tgt);
        } catch (Throwable first) {
            String msg = describe(first);
            Matcher m = TICK_SIZE_ERR.matcher(msg);
            if (!m.find()) throw first;
            double tick = Double.parseDouble(m.group(1));
            if (tick <= 0) throw first;
            double sl2  = snapDown(sl,  tick); // don't loosen the stop
            double tgt2 = snapUp(tgt,   tick); // don't cut the target short
            log.warn("[OCO] {} — retrying with tick={} SL {}->{} TGT {}->{}",
                    symbol, tick, sl, sl2, tgt, tgt2);
            return placeOcoGtt(symbol, quantity, lastPrice, sl2, tgt2);
        }
    }

    private static double snapDown(double v, double tick) {
        return Math.floor(v / tick) * tick;
    }

    private static double snapUp(double v, double tick) {
        return Math.ceil(v / tick) * tick;
    }

    /**
     * Place a Kite OCO ("two-leg") GTT with an SL leg (SELL LIMIT at {@code sl})
     * and a TGT leg (SELL LIMIT at {@code tgt}). Kite validates that
     * {@code sl < lastPrice < tgt} for a long position.
     */
    private Long placeOcoGtt(String symbol, int quantity,
                             double lastPrice, double sl, double tgt) throws Throwable {
        GTTParams gtt = new GTTParams();
        gtt.triggerType = "two-leg";
        gtt.tradingsymbol = symbol;
        gtt.exchange = trading.exchange();
        gtt.lastPrice = lastPrice;
        gtt.triggerPrices = new ArrayList<>();
        gtt.triggerPrices.add(sl);   // index 0 = SL leg
        gtt.triggerPrices.add(tgt);  // index 1 = TGT leg

        GTTOrderParams slLeg = gtt.new GTTOrderParams();
        slLeg.transactionType = "SELL";
        slLeg.quantity = quantity;
        slLeg.orderType = "LIMIT";
        slLeg.product = trading.product();
        // Fire at SL trigger; use the same price as the limit so it fills as an
        // effective stop-market within the NSE tick. Slightly less aggressive
        // than a true STOP-MARKET but that's what Kite GTT gives us.
        slLeg.price = sl;

        GTTOrderParams tgtLeg = gtt.new GTTOrderParams();
        tgtLeg.transactionType = "SELL";
        tgtLeg.quantity = quantity;
        tgtLeg.orderType = "LIMIT";
        tgtLeg.product = trading.product();
        tgtLeg.price = tgt;

        gtt.orders = new ArrayList<>();
        gtt.orders.add(slLeg);
        gtt.orders.add(tgtLeg);

        return (long) kite.placeGTT(gtt).id;
    }

    /**
     * Locate the OrderRecord we just wrote (most recent by (symbol,side,indicator))
     * and stamp bracket + metadata onto it. Runs post-persistence so we don't
     * have to re-plumb the BUY flow to return the row.
     */
    private void attachMeta(String symbol, String side, String indicator, SignalMeta meta,
                            Long ocoId, Double slPrice, Double tgtPrice) {
        if (meta == null) meta = SignalMeta.empty();
        try {
            OrderRecord rec = repo.findAll().stream()
                    .filter(o -> Objects.equals(o.getSymbol(), symbol))
                    .filter(o -> Objects.equals(o.getSide(),   side))
                    .filter(o -> Objects.equals(o.getIndicator(), indicator))
                    .max(java.util.Comparator.comparing(OrderRecord::getPlacedAt))
                    .orElse(null);
            if (rec == null) return;
            boolean dirty = false;
            if (meta.alertName()   != null) { rec.setAlertName(meta.alertName()); dirty = true; }
            if (meta.sector()      != null) { rec.setSector(meta.sector());       dirty = true; }
            if (meta.industry()    != null) { rec.setIndustry(meta.industry());   dirty = true; }
            if (meta.columnsJson() != null) { rec.setColumnsJson(meta.columnsJson()); dirty = true; }
            if (ocoId    != null) { rec.setKiteOcoGttId(ocoId);    dirty = true; }
            if (slPrice  != null) { rec.setStopLossPrice(slPrice); dirty = true; }
            if (tgtPrice != null) { rec.setTargetPrice(tgtPrice);  dirty = true; }
            if (dirty) repo.save(rec);
        } catch (Throwable t) {
            log.warn("Failed to attach signal meta to OrderRecord for {}: {}", symbol, t.getMessage());
        }
    }
}
