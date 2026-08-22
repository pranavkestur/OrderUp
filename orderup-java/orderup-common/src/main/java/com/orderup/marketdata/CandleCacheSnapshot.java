package com.orderup.marketdata;

import com.orderup.config.TradingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * File-based, atomic snapshot of the {@link CandleCacheService} rolling window.
 *
 * <p><b>Layout</b> (single binary file, big-endian via {@link DataOutputStream}):
 * <pre>
 *   magic         int32   = 0xC1CAC1E5
 *   schemaVersion int32   = 1
 *   entryCount    int32
 *   for each entry:
 *     token             long
 *     interval          UTF (short-prefixed string)
 *     lastRefreshEpochDay long
 *     candleCount       int32
 *     for each candle:  long time (epoch millis), 4 × double (o,h,l,c), long volume
 * </pre>
 *
 * <p><b>Write path</b>: writes to {@code path.tmp} then {@link StandardCopyOption#ATOMIC_MOVE}
 * onto the target. Reader always sees either the previous snapshot or the new one, never a torn write.
 *
 * <p><b>Read path</b>: on {@link PostConstruct} (before {@code CandleCacheWarmer}
 * runs on {@code ApplicationReadyEvent}), loads the file and pushes rows into the cache.
 * If magic/version mismatch or any IO error occurs, snapshot is ignored and the
 * warmer performs a full Kite refetch as if no snapshot existed. Never a hard failure.
 *
 * <p><b>Correctness</b>: the persisted {@code lastFullRefreshDay} is loaded verbatim.
 * The cache's own "if today != lastFullRefreshDay, do full refetch" invariant then
 * guarantees a corporate-action-safe rebuild on the first {@code get} of every new
 * IST trading day — even if the process was down overnight.
 */
@ConditionalOnProperty(prefix = "trading.market-data", name = "enabled", matchIfMissing = true)
@Component
public class CandleCacheSnapshot {

    private static final Logger log = LoggerFactory.getLogger(CandleCacheSnapshot.class);
    private static final int MAGIC = 0xC1CAC1E5;
    private static final int SCHEMA_VERSION = 1;

    private final CandleCacheService cache;
    private final Path path;
    private final boolean enabled;

    public CandleCacheSnapshot(CandleCacheService cache, TradingProperties trading) {
        this.cache = cache;
        String p = trading.marketData() != null ? trading.marketData().cacheSnapshotPath() : null;
        this.enabled = p != null && !p.isBlank();
        this.path = enabled ? Paths.get(p) : null;
    }

    @PostConstruct
    public void loadOnStartup() {
        if (!enabled) { log.info("Candle cache snapshot disabled (no path configured)."); return; }
        if (!Files.isReadable(path)) {
            log.info("No candle-cache snapshot at {} — cold start.", path.toAbsolutePath());
            return;
        }
        try {
            List<CandleCacheService.SnapshotRow> rows = readSnapshot(path);
            cache.restore(rows);
        } catch (Exception e) {
            log.warn("Failed to load candle-cache snapshot {}: {} — proceeding with cold cache.",
                    path, e.getMessage());
        }
    }

    /** Periodic snapshot, in trading.scheduler-zone (defaults to every 30 min). */
    @Scheduled(cron = "${trading.market-data.snapshot-cron:0 */30 * * * *}",
               zone = "${trading.scheduler-zone}")
    public void scheduledSave() { save("scheduled"); }

    @PreDestroy
    public void saveOnShutdown() { save("shutdown"); }

    public void save(String reason) {
        if (!enabled) return;
        List<CandleCacheService.SnapshotRow> rows = cache.exportAll();
        if (rows.isEmpty()) { log.debug("Skipping snapshot ({}) — cache empty", reason); return; }
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            writeSnapshot(tmp, rows);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Candle-cache snapshot saved ({}): {} entries -> {}", reason, rows.size(), path);
        } catch (Exception e) {
            log.warn("Failed to save candle-cache snapshot ({}): {}", reason, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ IO ---

    private static void writeSnapshot(Path file, List<CandleCacheService.SnapshotRow> rows) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA_VERSION);
            out.writeInt(rows.size());
            for (var r : rows) {
                out.writeLong(r.token());
                out.writeUTF(r.interval());
                out.writeLong(r.lastFullRefreshDay() == null
                        ? LocalDate.MIN.toEpochDay() : r.lastFullRefreshDay().toEpochDay());
                out.writeInt(r.candles().size());
                for (Candle c : r.candles()) {
                    out.writeLong(c.time().toEpochMilli());
                    out.writeDouble(c.open());
                    out.writeDouble(c.high());
                    out.writeDouble(c.low());
                    out.writeDouble(c.close());
                    out.writeLong(c.volume());
                }
            }
        }
    }

    private static List<CandleCacheService.SnapshotRow> readSnapshot(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC) throw new IOException("Bad magic: 0x" + Integer.toHexString(magic));
            int schema = in.readInt();
            if (schema != SCHEMA_VERSION) throw new IOException("Schema mismatch: got " + schema);
            int nEntries = in.readInt();
            List<CandleCacheService.SnapshotRow> rows = new ArrayList<>(nEntries);
            long totalCandles = 0;
            for (int e = 0; e < nEntries; e++) {
                long token = in.readLong();
                String interval = in.readUTF();
                long epochDay = in.readLong();
                LocalDate day = epochDay == LocalDate.MIN.toEpochDay() ? LocalDate.MIN : LocalDate.ofEpochDay(epochDay);
                int nCandles = in.readInt();
                totalCandles += nCandles;
                List<Candle> candles = new ArrayList<>(nCandles);
                for (int i = 0; i < nCandles; i++) {
                    long t = in.readLong();
                    double o = in.readDouble();
                    double h = in.readDouble();
                    double l = in.readDouble();
                    double c = in.readDouble();
                    long v = in.readLong();
                    candles.add(new Candle(Instant.ofEpochMilli(t), o, h, l, c, v));
                }
                rows.add(new CandleCacheService.SnapshotRow(token, interval, day, candles));
            }
            log.info("Read candle-cache snapshot: {} entries, {} total candles from {}",
                    nEntries, totalCandles, file);
            return rows;
        }
    }
}

