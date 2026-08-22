package com.orderup.orders;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A signal that the scanner produced but did NOT translate into a real Kite order.
 * Logged so the dashboard's "Potential orders" table can show what OrderUp would
 * have traded, and why it didn't. Reasons:
 *   DRY_RUN                — the operator toggled "Disable orders" from the UI.
 *   NO_HOLDINGS_FOR_SELL   — CNC guard: we don't own the stock so a SELL was rejected.
 *   KITE_REJECTED          — Kite refused (IP allowlist, margin, exchange rules, …).
 *   PAPER                  — running in paper mode (kept separate from real placements).
 */
@Entity
@Table(name = "potential_order")
public class PotentialOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant placedAt;
    private String  symbol;
    private String  side;         // BUY / SELL
    private String  indicator;    // strategy name
    private Double  signalPrice;  // last close at the moment the signal fired
    private Integer quantity;     // qty the strategy would have used
    private String  reason;       // enum-ish tag above
    @Column(length = 1000)
    private String  detail;       // free-text (Kite error message, strategy reason, …)

    public PotentialOrder() {}

    public PotentialOrder(Instant placedAt, String symbol, String side, String indicator,
                          Double signalPrice, Integer quantity, String reason, String detail) {
        this.placedAt = placedAt;
        this.symbol = symbol;
        this.side = side;
        this.indicator = indicator;
        this.signalPrice = signalPrice;
        this.quantity = quantity;
        this.reason = reason;
        this.detail = detail;
    }

    public Long    getId() { return id; }
    public Instant getPlacedAt() { return placedAt; }
    public String  getSymbol() { return symbol; }
    public String  getSide() { return side; }
    public String  getIndicator() { return indicator; }
    public Double  getSignalPrice() { return signalPrice; }
    public Integer getQuantity() { return quantity; }
    public String  getReason() { return reason; }
    public String  getDetail() { return detail; }
}

