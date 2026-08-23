package com.orderup.chartink.web;

import com.orderup.auth.KiteAuthService;
import com.orderup.orders.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chartink-app twin of {@code orderup-app}'s ControlController, minus the
 * scanner endpoints (there is no scanner here). Exposes the
 * {@code /control/orders/{enable,disable}} toggle so real fires can be
 * turned on for a trading session, plus {@code /control/status} so a quick
 * {@code curl} can confirm the app is authenticated and order-enabled
 * before market open.
 *
 * <p>No watchlist gate — Chartink is the source of truth for tickers; we
 * skip the local NSE EQ instrument dump entirely.
 */
@RestController
@RequestMapping("/control")
public class OrderControlController {

    private final OrderService orders;
    private final KiteAuthService auth;

    public OrderControlController(OrderService orders, KiteAuthService auth) {
        this.orders = orders;
        this.auth = auth;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", "orderup-chartink");
        m.put("kiteAuthenticated", auth.isAuthenticated());
        m.put("ordersDisabled", orders.isOrderingDisabled());
        m.put("ready", auth.isAuthenticated() && !orders.isOrderingDisabled());
        return m;
    }

    @PostMapping("/orders/enable")
    public Map<String, Object> enableOrders() {
        orders.enableOrders();
        return status();
    }

    @PostMapping("/orders/disable")
    public Map<String, Object> disableOrders() {
        orders.disableOrders();
        return status();
    }
}

