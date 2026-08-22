package com.orderup.marketdata;

import com.orderup.config.TradingProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.HistoricalData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Wraps Kite's historical_data API and converts the response to our Candle model.
 * Requires an active Kite Connect subscription with historical data enabled.
 */
@ConditionalOnProperty(prefix = "trading.market-data", name = "enabled", matchIfMissing = true)
@Service
public class HistoricalDataService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataService.class);

    private final KiteConnect kite;
    private final TradingProperties trading;
    private final KiteRateLimiter rateLimiter;

    public HistoricalDataService(KiteConnect kite, TradingProperties trading,
                                 KiteRateLimiter rateLimiter) {
        this.kite = kite;
        this.trading = trading;
        this.rateLimiter = rateLimiter;
    }

    public List<Candle> fetch(long instrumentToken) {
        return fetch(instrumentToken, "5minute", 5);
    }

    public List<Candle> fetch(long instrumentToken, String interval, int historyDays) {
        try {
            ZoneId ist = ZoneId.of(trading.schedulerZone());
            ZonedDateTime to = ZonedDateTime.now(ist);
            ZonedDateTime from = to.minusDays(historyDays);
            return fetchRange(instrumentToken, interval, from, to);
        } catch (Throwable e) {
            log.warn("Historical fetch failed token={} interval={} : {} — {}",
                    instrumentToken, interval, e.getClass().getSimpleName(), e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Fetch candles for an explicit {@code [from, to]} range. Used by the rolling
     * {@code CandleCacheService} to pull only the delta since the last cached bar,
     * including the current still-forming bar (Kite returns it with live OHLCV).
     */
    public List<Candle> fetchRange(long instrumentToken, String interval,
                                   ZonedDateTime from, ZonedDateTime to) {
        int maxRetries = trading.marketData() != null ? trading.marketData().maxRetries() : 0;
        long backoffMs = trading.marketData() != null ? trading.marketData().retryBackoffMs() : 1000L;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                rateLimiter.acquire();
                return doFetchRange(instrumentToken, interval, from, to);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ke) {
                boolean retryable = looksRateLimited(ke) || looksNetwork(ke);
                if (attempt < maxRetries && retryable) {
                    log.debug("Retryable historical fetch error token={} interval={} attempt={} code={} msg={}",
                            instrumentToken, interval, attempt, ke.code, ke.message);
                    sleep(backoffMs * (attempt + 1));
                    continue;
                }
                log.warn("Historical range fetch failed token={} interval={} : code={} msg={}",
                        instrumentToken, interval, ke.code, ke.message);
                return List.of();
            } catch (Throwable e) {
                if (attempt < maxRetries) {
                    log.debug("Retryable throwable token={} interval={} attempt={} : {}",
                            instrumentToken, interval, attempt, e.getMessage());
                    sleep(backoffMs * (attempt + 1));
                    continue;
                }
                log.warn("Historical range fetch failed token={} interval={} : {} — {}",
                        instrumentToken, interval, e.getClass().getSimpleName(), e.getMessage());
                return List.of();
            }
        }
        return List.of();
    }

    private List<Candle> doFetchRange(long instrumentToken, String interval,
                                      ZonedDateTime from, ZonedDateTime to) throws Throwable {
        ZoneId ist = ZoneId.of(trading.schedulerZone());
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        fmt.setTimeZone(TimeZone.getTimeZone(ist));
        Date fromDate = Date.from(from.toInstant());
        Date toDate   = Date.from(to.toInstant());

        HistoricalData hd = kite.getHistoricalData(
                fromDate, toDate,
                String.valueOf(instrumentToken),
                interval,
                false, false);

        List<Candle> out = new ArrayList<>();
        if (hd == null || hd.dataArrayList == null) return out;

        for (HistoricalData d : hd.dataArrayList) {
            Instant t;
            try {
                t = fmt.parse(d.timeStamp.replace('T', ' ').substring(0, 19)).toInstant();
            } catch (Exception ex) {
                t = Instant.now();
            }
            out.add(new Candle(t, d.open, d.high, d.low, d.close, (long) d.volume));
        }
        return out;
    }

    private static boolean looksRateLimited(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ke) {
        int c = ke.code;
        if (c == 429) return true;
        String m = ke.message == null ? "" : ke.message.toLowerCase();
        return m.contains("too many") || m.contains("rate");
    }

    private static boolean looksNetwork(com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException ke) {
        String m = ke.message == null ? "" : ke.message.toLowerCase();
        return m.contains("network") || m.contains("timeout") || m.contains("connection");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}

