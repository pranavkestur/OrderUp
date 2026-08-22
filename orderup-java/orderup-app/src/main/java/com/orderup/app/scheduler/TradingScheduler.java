package com.orderup.app.scheduler;

import com.orderup.config.TradingProperties;
import com.orderup.app.strategy.ScannerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Fires the scanner on a cron expression (default: every 5 minutes 09:00–15:59 IST, Mon–Fri).
 * Inside the tick we additionally gate on market open/close time and NSE holidays.
 */
@Component
public class TradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingScheduler.class);

    private final ScannerService scanner;
    private final TradingProperties trading;

    public TradingScheduler(ScannerService scanner, TradingProperties trading) {
        this.scanner = scanner;
        this.trading = trading;
    }

    @Scheduled(cron = "${trading.scan-cron}", zone = "${trading.scheduler-zone}")
    public void scheduledScan() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(trading.schedulerZone()));
        LocalTime t = now.toLocalTime();
        LocalDate d = now.toLocalDate();

        if (trading.holidays() != null && trading.holidays().contains(d)) {
            log.info("Market holiday {} — skipping scan.", d);
            return;
        }
        if (t.isBefore(trading.marketOpen()) || t.isAfter(trading.marketClose())) {
            log.debug("Outside market hours ({}), skipping scan.", t);
            return;
        }
        try {
            scanner.scanOnce();
        } catch (Exception e) {
            log.error("Scan tick failed", e);
        }
    }

    /**
     * WACE-only scan on a faster cadence (default: every 5 min during market hours).
     * Uses the rolling candle cache so per-tick Kite traffic stays tiny.
     */
    @Scheduled(cron = "${trading.wace-scan-cron}", zone = "${trading.scheduler-zone}")
    public void scheduledWaceScan() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(trading.schedulerZone()));
        LocalTime t = now.toLocalTime();
        LocalDate d = now.toLocalDate();

        if (trading.holidays() != null && trading.holidays().contains(d)) {
            log.debug("Market holiday {} — skipping WACE scan.", d);
            return;
        }
        if (t.isBefore(trading.marketOpen()) || t.isAfter(trading.marketClose())) {
            log.debug("Outside market hours ({}), skipping WACE scan.", t);
            return;
        }
        try {
            scanner.scanWaceOnce();
        } catch (Exception e) {
            log.error("WACE scan tick failed", e);
        }
    }
}

