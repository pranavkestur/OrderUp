package com.orderup.chartink.web;

import com.orderup.orders.OrderRecordChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-Sent Events broadcaster for the chartink P&L dashboard.
 *
 * <p>The frontend opens an {@code EventSource("/api/pnl/events")}. Every
 * time an {@link com.orderup.orders.OrderRecord} is inserted or updated,
 * the JPA listener fires a {@link OrderRecordChangedEvent}, this bean
 * receives it and pushes {@code event:refresh} to every connected browser.
 * The frontend then runs its refreshAll() once, which redraws the equity
 * curve, sector bars, mcap doughnut and KPI strip.</p>
 *
 * <p>This replaces the previous 10-second polling loop entirely — on an
 * idle day the dashboard makes zero background requests.</p>
 *
 * <p>Filters events by {@code indicator = "CHARTINK"} so writes from the
 * other app (HOURLY_MULTI / DAILY_MULTI in orderup-app) don't cause
 * pointless refreshes here.</p>
 */
@RestController
@RequestMapping("/api/pnl")
public class DashboardEventsController {

    private static final Logger log = LoggerFactory.getLogger(DashboardEventsController.class);
    private static final String STRATEGY = "CHARTINK";
    private static final long SSE_TIMEOUT_MS = 0L; // 0 = never time-out server-side

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();

    /**
     * SSE subscription endpoint. Each connected browser gets its own
     * emitter. Dead emitters (client closed the tab, network dropped, etc.)
     * are pruned lazily on the next broadcast attempt.
     */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(()    -> subscribers.remove(emitter));
        emitter.onError(t       -> subscribers.remove(emitter));
        try {
            // Send a hello so the browser knows the stream is live and can
            // start listening for real events. Also serves as the initial
            // keep-alive on flaky mobile connections.
            emitter.send(SseEmitter.event().name("hello").data("connected"));
        } catch (IOException ignored) { /* client left before we could hello */ }
        log.debug("SSE subscriber attached, total={}", subscribers.size());
        return emitter;
    }

    /**
     * Bridge JPA entity events → SSE broadcast. Runs on the calling thread
     * (the same one that persisted the OrderRecord), which is fine because
     * SseEmitter.send is non-blocking on the servlet container's I/O side.
     */
    @EventListener
    public void onOrderChanged(OrderRecordChangedEvent ev) {
        if (ev == null || !STRATEGY.equalsIgnoreCase(ev.indicator())) return;
        broadcast("refresh", "order:" + ev.id());
    }

    /**
     * Periodic keep-alive comment. Some reverse proxies (nginx, cloudflared,
     * ngrok) close idle connections after ~30-60 s of silence, which the
     * browser reports as a stream error. A 20-second heartbeat keeps the
     * pipe warm; it's a raw ":" comment so it's ignored by EventSource
     * but resets the proxy's idle timer.
     */
    @Scheduled(fixedDelay = 20_000L)
    public void heartbeat() {
        broadcast("ping", String.valueOf(System.currentTimeMillis()));
    }

    private void broadcast(String eventName, String data) {
        for (SseEmitter em : subscribers) {
            try {
                em.send(SseEmitter.event().name(eventName).data(data));
            } catch (Throwable t) {
                // Client is gone — the completion callback will remove it,
                // but drop it eagerly here too so the next broadcast is clean.
                subscribers.remove(em);
                try { em.complete(); } catch (Throwable ignored) {}
            }
        }
    }
}

