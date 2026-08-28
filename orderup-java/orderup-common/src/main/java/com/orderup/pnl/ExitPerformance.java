package com.orderup.pnl;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Post-exit tracking row: one per closed trade (SELL {@code OrderRecord} row).
 * Populated by {@link ExitPerformanceService} the trading day after each SELL
 * and periodically for up to 30 trading days after, so the dashboard can
 * answer "did I exit too early / too late?".
 *
 * <p>The primary key intentionally mirrors {@code OrderRecord.id} of the
 * SELL — one-to-one, no extra join key needed. Not modelled as a JPA
 * relationship because the two entities live in different bounded contexts
 * (order-writing vs. analytics) and OrderRecord is heavily written to by
 * the trading path; we don't want lazy loading or cascade surprises.
 */
@Entity
@Table(name = "exit_performance", indexes = {
        @Index(name = "idx_exit_perf_symbol", columnList = "symbol"),
        @Index(name = "idx_exit_perf_exit_at", columnList = "exitAt")
})
public class ExitPerformance {

    /** Matches {@code OrderRecord.id} of the SELL row. */
    @Id
    private Long id;

    private String  symbol;
    private Instant exitAt;
    private double  exitPrice;
    /** Copied from OrderRecord.exitType for query convenience (avoid join). */
    private String  exitType;

    // Price N *trading days* after the exit (holidays skipped).
    private Double  price1d;
    private Double  roi1d;
    private Double  price7d;
    private Double  roi7d;
    private Double  price30d;
    private Double  roi30d;

    /** Last time the scheduler touched this row — throttles re-fetches. */
    private Instant updatedAt;

    public ExitPerformance() {}

    public ExitPerformance(Long id, String symbol, Instant exitAt, double exitPrice, String exitType) {
        this.id = id;
        this.symbol = symbol;
        this.exitAt = exitAt;
        this.exitPrice = exitPrice;
        this.exitType = exitType;
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public Instant getExitAt() { return exitAt; }
    public double getExitPrice() { return exitPrice; }
    public String getExitType() { return exitType; }
    public Double getPrice1d() { return price1d; }
    public Double getRoi1d() { return roi1d; }
    public Double getPrice7d() { return price7d; }
    public Double getRoi7d() { return roi7d; }
    public Double getPrice30d() { return price30d; }
    public Double getRoi30d() { return roi30d; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setSymbol(String v) { this.symbol = v; }
    public void setExitAt(Instant v) { this.exitAt = v; }
    public void setExitPrice(double v) { this.exitPrice = v; }
    public void setExitType(String v) { this.exitType = v; }
    public void setPrice1d(Double v) { this.price1d = v; }
    public void setRoi1d(Double v) { this.roi1d = v; }
    public void setPrice7d(Double v) { this.price7d = v; }
    public void setRoi7d(Double v) { this.roi7d = v; }
    public void setPrice30d(Double v) { this.price30d = v; }
    public void setRoi30d(Double v) { this.roi30d = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}

