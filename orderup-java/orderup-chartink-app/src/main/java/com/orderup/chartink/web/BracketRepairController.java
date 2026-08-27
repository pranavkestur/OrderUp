package com.orderup.chartink.web;

import com.orderup.config.TradingProperties;
import com.orderup.orders.OrderRecord;
import com.orderup.orders.OrderRecordRepository;
import com.orderup.orders.OrderService;
import com.orderup.orders.OrderService.BracketResult;
import com.orderup.pnl.PositionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot recovery endpoint for the very specific situation where the primary
 * BUY has filled but the OCO bracket was rejected (e.g. legacy tick-mismatch
 * failures before {@code TickSizeService} was wired in). Scans today's
 * Chartink orders where {@code kiteOcoGttId is null && status==COMPLETE &&
 * side==BUY}, and re-attempts the bracket for each using the persisted
 * {@code avgFillPrice} as the reference. Each successful placement stamps the
 * new GTT id back onto the OrderRecord.
 *
 * <p>Idempotent: rows that already carry a {@code kiteOcoGttId} are skipped,
 * so re-hitting the endpoint after a partial success is safe.
 *
 * <p>Intentionally not gated behind auth — this app has no user model and is
 * only reachable via the trusted ngrok URL. If that assumption ever changes,
 * move the endpoint behind the same shared-secret path segment used by the
 * Chartink webhook.
 */
@RestController
@RequestMapping("/admin")
public class BracketRepairController {

    private static final Logger log = LoggerFactory.getLogger(BracketRepairController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OrderRecordRepository repo;
    private final OrderService orders;
    private final TradingProperties trading;
    private final PositionService positions;

    public BracketRepairController(OrderRecordRepository repo, OrderService orders,
                                   TradingProperties trading, PositionService positions) {
        this.repo = repo;
        this.orders = orders;
        this.trading = trading;
        this.positions = positions;
    }

    /**
     * Remove duplicate SELL rows produced by the historical race between
     * parallel {@code /api/pnl/kpis?range=…} calls (each triggering
     * syncExternalSells / reconcileExternallyClosed with no cross-request
     * lock). Safe to run any time — a no-op when there are none.
     */
    @PostMapping("/dedupe-sells")
    public Map<String, Object> dedupeSells() {
        int removed = positions.dedupeSellRows();
        return Map.of("removedDuplicates", removed);
    }

    /**
     * Delete {@code EXTERNALLY_CLOSED} SELL rows produced by an earlier buggy
     * run of the reconciler (pre-fix: a failed kite.getPositions() would
     * cascade into false-positive closures of freshly-filled BUYs). Pass a
     * comma-separated {@code symbols=} to target specific tickers, or omit
     * to purge every reconciled row and let the (now safe) reconciler
     * rebuild.
     *
     * <p>Example: {@code curl -X POST '.../admin/delete-reconciled-sells?symbols=BHEL,LENSKART'}
     */
    @PostMapping("/delete-reconciled-sells")
    public Map<String, Object> deleteReconciledSells(
            @RequestParam(required = false, defaultValue = "") String symbols) {
        java.util.Set<String> syms = new java.util.HashSet<>();
        for (String s : symbols.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) syms.add(t);
        }
        int removed = positions.deleteReconciledSells(syms);
        return Map.of("removed", removed, "targetSymbols", syms);
    }

    /**
     * Re-place OCO brackets for today's unprotected Chartink BUYs.
     *
     * @param dryRun when true, returns the list of orders that <i>would</i> be
     *               repaired without actually calling Kite. Useful for a quick
     *               sanity check before the real invocation.
     */
    @PostMapping("/repair-brackets")
    public Map<String, Object> repairBrackets(@RequestParam(defaultValue = "false") boolean dryRun) {
        TradingProperties.RiskManagement rm = trading.riskManagement();
        if (rm == null || !rm.enabled()) {
            return Map.of("error", "risk-management is not enabled — nothing to do");
        }

        long todayStartMs = LocalDate.now(IST).atStartOfDay(IST).toInstant().toEpochMilli();

        List<OrderRecord> candidates = new ArrayList<>();
        for (OrderRecord r : repo.findAll()) {
            if (!"CHARTINK".equalsIgnoreCase(r.getIndicator())) continue;
            if (!"BUY".equalsIgnoreCase(r.getSide())) continue;
            if (r.getKiteOcoGttId() != null) continue;
            if (r.getPlacedAt() == null || r.getPlacedAt().toEpochMilli() < todayStartMs) continue;
            // Only rows Kite says are actually filled — no point putting a
            // bracket around a rejected/pending order.
            if (!"COMPLETE".equalsIgnoreCase(r.getStatus())
                    && !"OPEN".equalsIgnoreCase(r.getStatus())) continue;
            candidates.add(r);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dryRun", dryRun);
        summary.put("candidateCount", candidates.size());
        List<Map<String, Object>> results = new ArrayList<>();

        for (OrderRecord r : candidates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("symbol", r.getSymbol());
            row.put("qty", r.getFilledQty() != null ? r.getFilledQty() : 1);
            // Fall back to signal price if avg fill hasn't been backfilled yet.
            Double refPrice = r.getAvgFillPrice();
            if (refPrice == null || refPrice <= 0) {
                // The reason string embeds "@ <price>" from ChartinkOrderService.
                refPrice = extractSignalPrice(r.getReason());
            }
            row.put("refPrice", refPrice);

            if (refPrice == null || refPrice <= 0) {
                row.put("status", "SKIPPED_NO_REF_PRICE");
                results.add(row);
                continue;
            }

            if (dryRun) {
                row.put("status", "WOULD_REPAIR");
                results.add(row);
                continue;
            }

            int qty = r.getFilledQty() != null && r.getFilledQty() > 0 ? r.getFilledQty() : 1;
            BracketResult br = orders.placeBracket(r.getSymbol(), qty, refPrice,
                    rm.targetPctOrDefault(), rm.stopLossPctOrDefault());
            row.put("sl",  br.sl());
            row.put("tgt", br.tgt());
            if (br.ocoId() != null) {
                r.setKiteOcoGttId(br.ocoId());
                r.setStopLossPrice(br.sl());
                r.setTargetPrice(br.tgt());
                repo.save(r);
                row.put("status", "REPAIRED");
                row.put("kiteOcoGttId", br.ocoId());
                log.info("[REPAIR] {} — new OCO id={} SL={} TGT={} (ref {})",
                        r.getSymbol(), br.ocoId(), br.sl(), br.tgt(), refPrice);
            } else {
                row.put("status", "OCO_FAILED_AGAIN");
                log.error("[REPAIR] {} — OCO placement failed again; position remains unprotected",
                        r.getSymbol());
            }
            results.add(row);
        }
        summary.put("results", results);
        return summary;
    }

    /**
     * Best-effort parser for the "@ <price>" fragment that ChartinkOrderService
     * embeds in the {@link OrderRecord#getReason() reason} string, used only
     * as a fallback when {@link OrderRecord#getAvgFillPrice()} is null (rare
     * — normally the poll-after-fill flow populates it).
     */
    private static Double extractSignalPrice(String reason) {
        if (reason == null) return null;
        int at = reason.indexOf('@');
        if (at < 0) return null;
        StringBuilder num = new StringBuilder();
        for (int i = at + 1; i < reason.length(); i++) {
            char c = reason.charAt(i);
            if (Character.isDigit(c) || c == '.') { num.append(c); continue; }
            if (num.length() > 0) break;
        }
        try {
            return num.length() == 0 ? null : Double.parseDouble(num.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

