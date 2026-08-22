package com.orderup.orders;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A symbol that satisfied the DAILY strategy on a given IST trading day and is
 * therefore a candidate to place an order — pending confirmation from HOURLY on
 * the same side. Rows are inserted the first time daily fires for the symbol on
 * a given day, and flipped to {@code triggered=true} the moment hourly agrees
 * and the order is placed.
 *
 * <p>This is intentionally decoupled from {@link PotentialOrder} — that table is
 * "signals we deliberately did not send to Kite" (dry mode, holdings guard, etc).
 * This table is "signals still waiting for the second timeframe to confirm".</p>
 */
@Entity
@Table(name = "watchlist_candidate",
        indexes = { @Index(name = "idx_wc_symbol_side", columnList = "symbol,side") })
public class WatchlistCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant addedAt;
    private String  symbol;
    private String  side;         // BUY / SELL — from daily
    private String  indicator;    // daily strategy name, e.g. DAILY_MULTI
    @Column(length = 1000)
    private String  reason;       // daily strategy reason string

    private boolean triggered;    // true once hourly confirmed and order placed
    private Instant triggeredAt;
    @Column(length = 1000)
    private String  triggerReason; // hourly reason at the time of firing

    public WatchlistCandidate() {}

    public WatchlistCandidate(Instant addedAt, String symbol, String side,
                              String indicator, String reason) {
        this.addedAt = addedAt;
        this.symbol = symbol;
        this.side = side;
        this.indicator = indicator;
        this.reason = reason;
        this.triggered = false;
    }

    public Long    getId()            { return id; }
    public Instant getAddedAt()       { return addedAt; }
    public String  getSymbol()        { return symbol; }
    public String  getSide()          { return side; }
    public String  getIndicator()     { return indicator; }
    public String  getReason()        { return reason; }
    public boolean isTriggered()      { return triggered; }
    public Instant getTriggeredAt()   { return triggeredAt; }
    public String  getTriggerReason() { return triggerReason; }

    public void markTriggered(Instant at, String hourlyReason) {
        this.triggered = true;
        this.triggeredAt = at;
        this.triggerReason = hourlyReason;
    }
}

