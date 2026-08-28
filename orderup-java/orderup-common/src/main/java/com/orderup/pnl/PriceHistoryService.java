package com.orderup.pnl;

import com.orderup.marketdata.InstrumentService;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches daily OHLC candles from Kite for a symbol over a fixed window,
 * used to power the "post-entry price journey" drawer on the P&L dashboard.
 * The user clicks a symbol in Open Positions / Closed Trades and gets two
 * charts (daily High / daily Low) from the entry date onwards, so they can
 * see whether their SL / TGT levels were too tight or too generous relative
 * to the actual intraday range the market subsequently offered.
 *
 * <p>Small in-memory cache keyed by {@code (symbol, fromDate, days)} — the
 * dashboard is likely to hit the same rows repeatedly (user opens & closes
 * the drawer, toggles between High and Low tabs). TTL is short enough to
 * pick up a new candle after market close on the same day.
 *
 * <p>Bypasses {@code HistoricalDataService} because that service is disabled
 * (via {@code trading.market-data.enabled=false}) in the Chartink app.
 */
@Service
public class PriceHistoryService {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    /**
     * Safety margin over the requested trading-day count when translating
     * to a calendar-day window for Kite. Accounts for weekends + holidays
     * (~2 weekend days per 5 trading days, plus 8–10 NSE holidays / yr).
     * 30 trading days → up to ~50 calendar days.
     */
    private static final int CALENDAR_MARGIN_DAYS_PER_10 = 5;

    private final KiteConnect kite;
    private final InstrumentService instruments;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public PriceHistoryService(KiteConnect kite, InstrumentService instruments) {
        this.kite = kite;
        this.instruments = instruments;
    }

    /**
     * Return up to {@code tradingDays} daily candles starting on {@code fromDate}
     * for {@code symbol}. If the position is younger than the requested window
     * the returned list will be shorter — no padding, no synthetic points.
     *
     * @return empty list on any failure (unknown symbol / Kite error / no
     *         candles). Callers get a "no data" state on the frontend.
     */
    public List<Candle> fetch(String symbol, LocalDate fromDate, int tradingDays) {
        if (symbol == null || symbol.isBlank() || fromDate == null || tradingDays <= 0) return List.of();
        String key = symbol.toUpperCase(Locale.ROOT) + "|" + fromDate + "|" + tradingDays;
        Entry cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.timestampMs) < CACHE_TTL.toMillis()) {
            return cached.candles;
        }
        Long token = instruments.tokenFor(symbol);
        if (token == null) {
            log.debug("PriceHistoryService: no instrument token for {}", symbol);
            return List.of();
        }
        // Convert trading-day count into a calendar-day span, adding a
        // holiday-safe margin. We then trim the response to the requested
        // trading-day count by array index — this handles NSE holidays
        // naturally (they simply don't appear in Kite's response).
        int lookaheadCalDays = tradingDays + (tradingDays / 10 + 1) * CALENDAR_MARGIN_DAYS_PER_10 + 4;
        Instant from = fromDate.atStartOfDay(IST).toInstant();
        Instant to   = fromDate.plusDays(lookaheadCalDays).atStartOfDay(IST).toInstant();
        // Never ask Kite for candles past "now" — it errors on future dates.
        Instant nowIst = Instant.now();
        if (to.isAfter(nowIst)) to = nowIst;
        if (!to.isAfter(from)) return List.of();

        try {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            fmt.setTimeZone(TimeZone.getTimeZone(IST));
            HistoricalData hd = kite.getHistoricalData(
                    Date.from(from), Date.from(to),
                    String.valueOf(token), "day",
                    false, false);
            List<Candle> out = new ArrayList<>();
            if (hd != null && hd.dataArrayList != null) {
                int n = Math.min(hd.dataArrayList.size(), tradingDays);
                for (int i = 0; i < n; i++) {
                    HistoricalData d = hd.dataArrayList.get(i);
                    // timeStamp is like "2026-08-24T00:00:00+0530" — keep the date part only.
                    String date = d.timeStamp == null ? "" : d.timeStamp.substring(0, 10);
                    out.add(new Candle(date, d.open, d.high, d.low, d.close, (long) d.volume));
                }
            }
            cache.put(key, new Entry(now, out));
            return out;
        } catch (Throwable t) {
            log.warn("PriceHistoryService: fetch failed for {} from={} : {}",
                    symbol, fromDate, t.getMessage());
            return List.of();
        }
    }

    /** Serialised over the wire → keep field names short and JSON-friendly. */
    public record Candle(String date, double open, double high, double low,
                         double close, long volume) {}

    private record Entry(long timestampMs, List<Candle> candles) {}
}

