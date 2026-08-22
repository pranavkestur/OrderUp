package com.orderup.marketdata;

import com.orderup.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Rolling in-memory candle cache keyed by {@code (instrumentToken, interval)}.
 *
 * <h2>Why</h2>
 * WACE is scanned every 5 minutes on ~500 symbols. Re-downloading 260 daily and
 * 360 hourly bars per symbol per pass swamps the Kite historical API. Since the
 * only bar that actually changes between passes is the current (still-forming)
 * bar — plus, occasionally, one newly-closed bar — we cache the window per
 * (token, interval) and only fetch the delta.
 *
 * <h2>Merge rules</h2>
 * A delta fetch always requests candles from {@code tail.time} onward, so the
 * response's first entry (if any) shares the tail timestamp and acts as a
 * checksum. Merge is strict and timestamp-driven:
 * <ul>
 *   <li>{@code newBar.time == tail.time} — <b>overwrite</b> tail (live/partial
 *       bar update, or the bar being finalized).</li>
 *   <li>{@code newBar.time  > tail.time} — <b>append</b> (a fresh bar started).</li>
 *   <li>{@code newBar.time  < tail.time} — discard (should never happen).</li>
 * </ul>
 * If the response's first entry does not match {@code tail.time} the cache has
 * drifted (missed a bar, timezone anomaly, whatever), and we self-heal with a
 * full refetch.
 *
 * <h2>Daily rebuild</h2>
 * Exactly once per IST trading day (on the first {@code get} of the day) we do
 * a full refetch. This absorbs overnight corporate-action adjustments (splits,
 * bonuses, dividends) which retroactively rewrite historical closes.
 *
 * <h2>Correctness</h2>
 * Every cached bar is a verbatim Kite response. The cache never fabricates or
 * interpolates values. Therefore, at any time {@code T}, the deque is byte-
 * identical to what a fresh full fetch at {@code T} would return — and the
 * pure-function indicator math yields identical results.
 */
@ConditionalOnProperty(prefix = "trading.market-data", name = "enabled", matchIfMissing = true)
@Service
public class CandleCacheService {

    private static final Logger log = LoggerFactory.getLogger(CandleCacheService.class);

    private final HistoricalDataService history;
    private final ZoneId zone;

    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public CandleCacheService(HistoricalDataService history, TradingProperties trading) {
        this.history = history;
        this.zone = ZoneId.of(trading.schedulerZone());
    }

    private record Key(long token, String interval) {}

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<Candle> deque = new ArrayDeque<>();
        LocalDate lastFullRefreshDay = LocalDate.MIN;
    }

    /**
     * Return the current cached window for {@code (token, interval)}, refreshed
     * with a delta fetch (or full fetch if this is the first hit of the IST day
     * / cache miss / drift). Returned list is a snapshot safe to iterate without
     * holding any lock.
     */
    public List<Candle> get(long token, String interval, int historyDays) {
        Entry e = cache.computeIfAbsent(new Key(token, interval), k -> new Entry());
        e.lock.lock();
        try {
            LocalDate today = LocalDate.now(zone);
            boolean needFull = e.deque.isEmpty() || !e.lastFullRefreshDay.equals(today);

            if (needFull) {
                fullRefresh(e, token, interval, historyDays, today);
            } else {
                deltaRefresh(e, token, interval, historyDays, today);
            }

            trimHead(e, historyDays);
            return new ArrayList<>(e.deque);
        } finally {
            e.lock.unlock();
        }
    }

    private void fullRefresh(Entry e, long token, String interval, int historyDays, LocalDate today) {
        List<Candle> fresh = history.fetch(token, interval, historyDays);
        e.deque.clear();
        e.deque.addAll(fresh);
        e.lastFullRefreshDay = today;
        log.debug("Cache full-refresh token={} interval={} -> {} bars", token, interval, fresh.size());
    }

    private void deltaRefresh(Entry e, long token, String interval, int historyDays, LocalDate today) {
        Candle tail = e.deque.peekLast();
        ZonedDateTime from = tail.time().atZone(zone);
        ZonedDateTime to   = ZonedDateTime.now(zone);
        List<Candle> delta = history.fetchRange(token, interval, from, to);

        if (delta.isEmpty()) {
            // No fresh data (dead time / illiquid symbol / just before next bar).
            return;
        }

        // Checksum: first delta bar should match cache tail. If not, self-heal.
        if (!delta.get(0).time().equals(tail.time())) {
            log.warn("Cache drift token={} interval={} tail={} deltaFirst={} — full refetch.",
                    token, interval, tail.time(), delta.get(0).time());
            fullRefresh(e, token, interval, historyDays, today);
            return;
        }

        // Overwrite tail with the (possibly-updated) live bar, then append any newer bars.
        e.deque.pollLast();
        for (Candle c : delta) {
            Candle prevTail = e.deque.peekLast();
            if (prevTail == null || c.time().isAfter(prevTail.time()) || c.time().equals(prevTail.time())) {
                if (prevTail != null && c.time().equals(prevTail.time())) {
                    e.deque.pollLast();
                }
                e.deque.addLast(c);
            }
            // Older bars silently ignored.
        }
    }

    private void trimHead(Entry e, int historyDays) {
        Instant cutoff = ZonedDateTime.now(zone).minusDays(historyDays).toInstant();
        while (!e.deque.isEmpty() && e.deque.peekFirst().time().isBefore(cutoff)) {
            e.deque.pollFirst();
        }
    }

    /** Force full refetch for all keys on next {@link #get}. */
    public void invalidateAll() {
        cache.clear();
        log.info("Candle cache fully invalidated.");
    }

    // -------------------------------------------------------------------------
    //  Snapshot / restore hooks used by CandleCacheSnapshot for disk persistence.
    // -------------------------------------------------------------------------

    /** Immutable view of one cached (token, interval) entry. */
    public record SnapshotRow(
            long      token,
            String    interval,
            LocalDate lastFullRefreshDay,
            List<Candle> candles
    ) {}

    /** Point-in-time copy of every cache entry, safe to write to disk. */
    public List<SnapshotRow> exportAll() {
        List<SnapshotRow> out = new ArrayList<>(cache.size());
        for (Map.Entry<Key, Entry> me : cache.entrySet()) {
            Entry e = me.getValue();
            e.lock.lock();
            try {
                out.add(new SnapshotRow(
                        me.getKey().token(),
                        me.getKey().interval(),
                        e.lastFullRefreshDay,
                        new ArrayList<>(e.deque)));
            } finally {
                e.lock.unlock();
            }
        }
        return out;
    }

    /**
     * Bulk-restore cache entries from a snapshot. Existing entries are replaced.
     * The {@code lastFullRefreshDay} is preserved as-is so that the first
     * {@link #get} of a new IST trading day still triggers the corporate-action
     * safe full refetch.
     */
    public void restore(List<SnapshotRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        int restored = 0;
        for (SnapshotRow row : rows) {
            Key k = new Key(row.token(), row.interval());
            Entry e = cache.computeIfAbsent(k, __ -> new Entry());
            e.lock.lock();
            try {
                e.deque.clear();
                e.deque.addAll(row.candles());
                e.lastFullRefreshDay = row.lastFullRefreshDay() != null
                        ? row.lastFullRefreshDay() : LocalDate.MIN;
                restored++;
            } finally {
                e.lock.unlock();
            }
        }
        log.info("Candle cache restored: {} entries from snapshot", restored);
    }
}

