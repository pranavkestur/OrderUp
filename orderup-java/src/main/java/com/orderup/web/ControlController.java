package com.orderup.web;

import com.orderup.orders.OrderService;
import com.orderup.strategy.ScannerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Runtime toggles from the dashboard.
 * <ul>
 *   <li>Start / Stop (pause) — controls scanning.</li>
 *   <li>Enable / Disable orders — controls whether signals turn into real Kite orders
 *       (scans + Telegram alerts continue when disabled).</li>
 * </ul>
 */
@RestController
@RequestMapping("/control")
public class ControlController {

    private final ScannerService scanner;
    private final OrderService orders;

    public ControlController(ScannerService scanner, OrderService orders) {
        this.scanner = scanner;
        this.orders = orders;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "paused",          scanner.isPaused(),
                "scanning",        scanner.isScanning(),
                "ordersDisabled",  orders.isOrderingDisabled()
        );
    }

    @PostMapping("/pause")
    public Map<String, Object> pause() { scanner.pause(); return status(); }

    @PostMapping("/resume")
    public Map<String, Object> resume() { scanner.resume(); return status(); }

    @PostMapping("/orders/disable")
    public Map<String, Object> disableOrders() { orders.disableOrders(); return status(); }

    @PostMapping("/orders/enable")
    public Map<String, Object> enableOrders() { orders.enableOrders(); return status(); }
}

