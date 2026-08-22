package com.orderup.orders;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant placedAt;
    private String symbol;
    private String side;          // BUY / SELL
    private String indicator;     // strategy name (HOURLY_MULTI / DAILY_MULTI / ...)
    private String orderType;     // MARKET / GTT / PAPER
    private String kiteOrderId;   // regular order id
    private Long   kiteGttId;     // GTT id (if applicable)
    private String reason;

    /** Persisted Kite fill data — synced from the orderbook so it survives across trading days. */
    private Integer filledQty;      // executed quantity (nullable = not yet synced)
    private Double  avgFillPrice;   // executed avg price (nullable)
    private String  status;         // COMPLETE / REJECTED / CANCELLED / OPEN / TRIGGER_PENDING / PAPER / GTT

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

    public void setFilledQty(Integer v) { this.filledQty = v; }
    public void setAvgFillPrice(Double v) { this.avgFillPrice = v; }
    public void setStatus(String v) { this.status = v; }
}

