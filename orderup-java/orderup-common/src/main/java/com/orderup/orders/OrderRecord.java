package com.orderup.orders;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@EntityListeners(OrderRecordListener.class)
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant placedAt;
    private String symbol;
    private String side;          // BUY / SELL
    private String indicator;     // strategy name (HOURLY_MULTI / DAILY_MULTI / CHARTINK / ...)
    private String orderType;     // MARKET / GTT / PAPER
    private String kiteOrderId;   // regular order id
    private Long   kiteGttId;     // GTT id (if applicable - closed-market fallback)
    private String reason;

    /** Persisted Kite fill data — synced from the orderbook so it survives across trading days. */
    private Integer filledQty;      // executed quantity (nullable = not yet synced)
    private Double  avgFillPrice;   // executed avg price (nullable)
    private String  status;         // COMPLETE / REJECTED / CANCELLED / OPEN / TRIGGER_PENDING / PAPER / GTT

    // ------- Chartink / bracket-order metadata (added for the P&L dashboard) -------

    /** Chartink alert display name that opened this position (BUY only). */
    private String alertName;

    /** Sector (from Chartink "sector" payload column), lowercased trimmed. Nullable. */
    private String sector;

    /** Industry (from Chartink "industry" payload column). Nullable. */
    private String industry;

    /**
     * SEBI/AMFI market-cap category. One of {@code LARGE_CAP},
     * {@code MID_CAP}, {@code SMALL_CAP}, or {@code null} for unknown /
     * uncategorised (e.g. fresh IPO or user hasn't refreshed the AMFI file).
     * Populated at order-write time from {@code ClassificationService}
     * and backfilled on startup for pre-existing rows.
     */
    private String marketCap;

    /**
     * Full Chartink columns[] map for this symbol, serialized as JSON. Kept so the
     * UI's row-drawer can render any custom columns the user later adds without a
     * schema migration. Nullable.
     */
    @Column(columnDefinition = "TEXT")
    private String columnsJson;

    /** Kite OCO (two-leg) GTT id that carries this position's SL + TGT. Nullable. */
    private Long kiteOcoGttId;

    /** Configured SL price at time of BUY. Nullable. */
    private Double stopLossPrice;

    /** Configured TGT price at time of BUY. Nullable. */
    private Double targetPrice;

    /**
     * How a SELL exited. One of {@code SL}, {@code TGT}, {@code SL_APPROX},
     * {@code MANUAL}, {@code EXPIRED}, {@code UNKNOWN}. Null on BUY rows.
     * Populated by {@code PositionService.syncExternalSells} (OCO fill →
     * compare against source BUY's SL/TGT) and
     * {@code reconcileExternallyClosed} (uses the same tag it already
     * derives from the triggered GTT leg or the SL fallback). Backfilled
     * for pre-existing rows via {@code POST /admin/backfill-exit-types}.
     */
    private String exitType;

    public OrderRecord() {}

    public OrderRecord(Instant placedAt, String symbol, String side, String indicator,
                       String orderType, String kiteOrderId, Long kiteGttId, String reason) {
        this.placedAt = placedAt;
        this.symbol = symbol;
        this.side = side;
        this.indicator = indicator;
        this.orderType = orderType;
        this.kiteOrderId = kiteOrderId;
        this.kiteGttId = kiteGttId;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public Instant getPlacedAt() { return placedAt; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getIndicator() { return indicator; }
    public String getOrderType() { return orderType; }
    public String getKiteOrderId() { return kiteOrderId; }
    public Long getKiteGttId() { return kiteGttId; }
    public String getReason() { return reason; }
    public Integer getFilledQty() { return filledQty; }
    public Double getAvgFillPrice() { return avgFillPrice; }
    public String getStatus() { return status; }
    public String getAlertName() { return alertName; }
    public String getSector() { return sector; }
    public String getIndustry() { return industry; }
    public String getMarketCap() { return marketCap; }
    public String getColumnsJson() { return columnsJson; }
    public Long getKiteOcoGttId() { return kiteOcoGttId; }
    public Double getStopLossPrice() { return stopLossPrice; }
    public Double getTargetPrice() { return targetPrice; }
    public String getExitType() { return exitType; }

    public void setFilledQty(Integer v) { this.filledQty = v; }
    public void setAvgFillPrice(Double v) { this.avgFillPrice = v; }
    public void setStatus(String v) { this.status = v; }
    public void setAlertName(String v) { this.alertName = v; }
    public void setSector(String v) { this.sector = v; }
    public void setIndustry(String v) { this.industry = v; }
    public void setMarketCap(String v) { this.marketCap = v; }
    public void setColumnsJson(String v) { this.columnsJson = v; }
    public void setKiteOcoGttId(Long v) { this.kiteOcoGttId = v; }
    public void setStopLossPrice(Double v) { this.stopLossPrice = v; }
    public void setTargetPrice(Double v) { this.targetPrice = v; }
    public void setExitType(String v) { this.exitType = v; }
}

