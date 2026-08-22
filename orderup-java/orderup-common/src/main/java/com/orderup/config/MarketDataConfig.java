package com.orderup.config;

/**
 * Tunables for market-data fetch parallelism, rate limiting, retries, and
 * candle-cache disk snapshot. Bound from {@code trading.market-data.*}.
 */
public record MarketDataConfig(
        int    workerThreads,        // pool size for warmer + scanner per-symbol tasks
        double rateLimitPerSec,      // shared throttle across the pool (Kite historical ceiling)
        int    maxRetries,           // additional attempts after a failed fetch (0 = no retry)
        long   retryBackoffMs,       // linear backoff between retries

        String cacheSnapshotPath,    // filesystem path for atomic snapshot file (or blank = disabled)
        String snapshotCron          // cron for periodic snapshots (in trading.scheduler-zone)
) {}

