package com.orderup.marketdata;

import com.orderup.config.TradingProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Simple token-bucket rate limiter, shared across all fetchers, sized off
 * {@code trading.market-data.rate-limit-per-sec}. Kite's historical endpoint
 * has a documented ~3 req/s ceiling; typical accounts sustain 5–10 req/s.
 *
 * <p>Implementation: a single {@link AtomicLong} tracking the next-allowed
 * nanosecond. Each {@link #acquire()} atomically advances it by
 * {@code intervalNanos} and sleeps if the reservation is in the future.
 * Contention is O(1) CAS retries and fair enough at our scale.
 */
@ConditionalOnProperty(prefix = "trading.market-data", name = "enabled", matchIfMissing = true)
@Component
public class KiteRateLimiter {

    private final long intervalNanos;
    private final AtomicLong nextAllowedNanos = new AtomicLong(System.nanoTime());

    public KiteRateLimiter(TradingProperties trading) {
        double perSec = trading.marketData() != null ? trading.marketData().rateLimitPerSec() : 5.0;
        if (perSec <= 0) perSec = 5.0;
        this.intervalNanos = (long) (1_000_000_000.0 / perSec);
    }

    public void acquire() throws InterruptedException {
        while (true) {
            long expected = nextAllowedNanos.get();
            long now = System.nanoTime();
            long reservation = Math.max(now, expected);
            if (nextAllowedNanos.compareAndSet(expected, reservation + intervalNanos)) {
                long wait = reservation - now;
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
                return;
            }
        }
    }
}

