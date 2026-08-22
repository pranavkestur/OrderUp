package com.orderup.marketdata;

import com.orderup.config.TradingProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Fixed thread pool shared by the candle-cache warmer and the parallel scanners.
 * Size comes from {@code trading.market-data.worker-threads}. Threads are daemon
 * so they don't block JVM shutdown. Actual throughput is capped by
 * {@link KiteRateLimiter}, not by this pool size.
 */
@ConditionalOnProperty(prefix = "trading.market-data", name = "enabled", matchIfMissing = true)
@Component
public class MarketDataExecutor {

    private static final Logger log = LoggerFactory.getLogger(MarketDataExecutor.class);

    private final ExecutorService pool;

    public MarketDataExecutor(TradingProperties trading) {
        int workers = trading.marketData() != null ? trading.marketData().workerThreads() : 6;
        if (workers <= 0) workers = 6;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "md-worker-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        log.info("MarketDataExecutor started with {} workers", workers);
    }

    public ExecutorService pool() { return pool; }

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}

