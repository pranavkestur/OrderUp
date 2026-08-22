package com.orderup.app.web;

import com.orderup.auth.KiteAuthService;
import com.orderup.marketdata.InstrumentService;
import com.orderup.notify.TelegramNotifier;
import com.orderup.orders.OrderRecord;
import com.orderup.orders.OrderRecordRepository;
import com.orderup.app.strategy.ScannerService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class StatusController {

    private final KiteAuthService auth;
    private final InstrumentService instruments;
    private final OrderRecordRepository orders;
    private final ScannerService scanner;
    private final TelegramNotifier telegram;

    public StatusController(KiteAuthService auth, InstrumentService instruments,
                            OrderRecordRepository orders, ScannerService scanner,
                            TelegramNotifier telegram) {
        this.auth = auth;
        this.instruments = instruments;
        this.orders = orders;
        this.scanner = scanner;
        this.telegram = telegram;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "authenticated", auth.isAuthenticated(),
                "watchlistSize", instruments.watchlist().size(),
                "loginUrl", auth.loginUrl()
        );
    }

    @GetMapping("/watchlist")
    public Set<String> watchlist() {
        return instruments.watchlist();
    }

    /** Re-read watchlist files (external dir preferred over classpath). Called by the quarterly refresh cron. */
    @PostMapping("/watchlist/reload")
    public Map<String, Object> reloadWatchlist() {
        instruments.loadWatchlist();
        return Map.of("size", instruments.watchlist().size());
    }

    @GetMapping("/orders/today")
    public List<OrderRecord> todaysOrders() {
        return orders.findByPlacedAtAfterOrderByPlacedAtDesc(
                Instant.now().truncatedTo(ChronoUnit.DAYS));
    }

    /** Manual trigger — runs asynchronously so the UI doesn't hang for minutes. */
    @PostMapping("/scan/run")
    public Map<String, Object> runNow() {
        if (scanner.isScanning()) {
            return Map.of("status", "already_running");
        }
        // Fire-and-forget on a daemon thread; the UI will pick up results on its next refresh.
        Thread t = new Thread(() -> scanner.scanOnce(true), "manual-scan");
        t.setDaemon(true);
        t.start();
        return Map.of("status", "started", "paused", scanner.isPaused());
    }

    @GetMapping("/scan/status")
    public Map<String, Object> scanStatus() {
        return Map.of("scanning", scanner.isScanning(), "paused", scanner.isPaused());
    }

    /** Send a test Telegram message. Hit this once after setup to verify. */
    @PostMapping("/telegram/test")
    public Map<String, Object> telegramTest() {
        boolean configured = telegram.isConfigured();
        if (configured) {
            telegram.send("✅ OrderUp Telegram wiring works.");
        }
        return Map.of("configured", configured);
    }
}

