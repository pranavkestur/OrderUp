package com.orderup.pnl;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Persistent daily-candle cache shared by the "post-entry price journey" drawer
 * and the nightly {@link ExitPerformanceService} snapshot job. One row per
 * {@code (symbol, date)} pair; daily candles are immutable once the trading
 * day closes, so writes are essentially insert-once.
 *
 * <p>The {@code symbol} column also stores synthetic benchmark keys prefixed
 * with {@code ^} (e.g. {@code ^NIFTY 50}, {@code ^NIFTY BANK}) so index
 * candles reuse the same table and cache-eviction semantics.
 *
 * <p>Primary key is the concatenated {@code SYMBOL|YYYY-MM-DD} string —
 * simpler than a composite {@code @IdClass} and keeps repository queries
 * trivial. The unique invariant is enforced by construction (callers only
 * ever build the id via {@link #keyFor(String, LocalDate)}).
 */
@Entity
@Table(name = "price_cache", indexes = {
        @Index(name = "idx_price_cache_symbol_date", columnList = "symbol,tradeDate")
})
public class PriceCacheEntry {

    @Id
    private String id;

    private String symbol;
    private LocalDate tradeDate;

    private double open;
    private double high;
    private double low;
    private double close;
    private long   volume;

    private Instant fetchedAt;

    public PriceCacheEntry() {}

    public PriceCacheEntry(String symbol, LocalDate tradeDate,
                           double open, double high, double low, double close,
                           long volume, Instant fetchedAt) {
        this.id = keyFor(symbol, tradeDate);
        this.symbol = symbol;
        this.tradeDate = tradeDate;
        this.open = open; this.high = high; this.low = low; this.close = close;
        this.volume = volume;
        this.fetchedAt = fetchedAt;
    }

    public static String keyFor(String symbol, LocalDate date) {
        return symbol + "|" + date;
    }

    public String getId()          { return id; }
    public String getSymbol()      { return symbol; }
    public LocalDate getTradeDate(){ return tradeDate; }
    public double getOpen()        { return open; }
    public double getHigh()        { return high; }
    public double getLow()         { return low; }
    public double getClose()       { return close; }
    public long getVolume()        { return volume; }
    public Instant getFetchedAt()  { return fetchedAt; }

    public void setId(String v)          { this.id = v; }
    public void setSymbol(String v)      { this.symbol = v; }
    public void setTradeDate(LocalDate v){ this.tradeDate = v; }
    public void setOpen(double v)        { this.open = v; }
    public void setHigh(double v)        { this.high = v; }
    public void setLow(double v)         { this.low = v; }
    public void setClose(double v)       { this.close = v; }
    public void setVolume(long v)        { this.volume = v; }
    public void setFetchedAt(Instant v)  { this.fetchedAt = v; }
}

