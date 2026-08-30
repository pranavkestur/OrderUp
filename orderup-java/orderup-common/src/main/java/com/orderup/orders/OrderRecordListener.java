package com.orderup.orders;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;

/**
 * JPA entity listener that emits a {@link OrderRecordChangedEvent} on the
 * Spring application context every time an {@link OrderRecord} is inserted
 * or updated. Wired into the entity via {@code @EntityListeners} — see
 * {@link OrderRecord}.
 *
 * <p>Consumers (currently just the chartink dashboard's SSE broadcaster)
 * subscribe with {@code @EventListener} and push a "refresh" hint to
 * connected browsers. This means the dashboard graphs redraw exactly once
 * per persisted order — no polling loop, no 5-second lag.</p>
 *
 * <p>JPA entity listeners are instantiated by the persistence provider,
 * not Spring, so we can't {@code @Autowired} the publisher directly.
 * Instead the {@link Registrar} inner-class captures the live
 * {@link ApplicationEventPublisher} into a {@code static} slot at
 * container-startup time; the listener callbacks read from that slot.</p>
 */
public class OrderRecordListener {

    private static volatile ApplicationEventPublisher publisher;

    @PostPersist
    public void onPersist(OrderRecord rec) { fire(rec); }

    @PostUpdate
    public void onUpdate(OrderRecord rec) { fire(rec); }

    private static void fire(OrderRecord rec) {
        ApplicationEventPublisher p = publisher;
        if (p == null || rec == null) return;
        try {
            p.publishEvent(new OrderRecordChangedEvent(rec.getIndicator(), rec.getId()));
        } catch (Throwable t) {
            // Never let event publication break the DB transaction.
        }
    }

    /**
     * Spring-managed sidecar that captures the live event publisher into
     * the static slot above at boot time. Placed inside this file so the
     * whole plumbing is discoverable from one place.
     */
    @Component
    public static class Registrar {
        public Registrar(ApplicationEventPublisher p) { publisher = p; }
    }
}

