package com.orderup.marketdata;

import com.orderup.auth.KiteAuthService;
import com.orderup.config.TradingProperties;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the list of NSE equity instruments from Kite, keeps only segment==NSE & instrument_type==EQ,
 * and maps the user's configured watchlist (files + inline extras) to instrument tokens.
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final KiteConnect kite;
    private final TradingProperties trading;
    private final ObjectProvider<KiteAuthService> authProvider;

    /** tradingsymbol -> instrument token */
    private final Map<String, Long> symbolToToken = new ConcurrentHashMap<>();
    private final Set<String> watchlist = new LinkedHashSet<>();

    public InstrumentService(KiteConnect kite, TradingProperties trading,
                             ObjectProvider<KiteAuthService> authProvider) {
        this.kite = kite;
        this.trading = trading;
        this.authProvider = authProvider;
    }

    /**
     * After the app is fully wired, if we have a restored/valid Kite session,
     * refresh the NSE EQ instrument map so scans work on cold-restart days.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshIfAuthenticated() {
        // Nothing-to-do short-circuit for apps that don't need the instrument
        // dump (e.g. orderup-chartink-app, which trusts Chartink's ticker and
        // relies on Kite server-side symbol resolution for placeOrder/getLTP).
        // Naturally opt-in for orderup-app because its allNseEq=true.
        if (!trading.allNseEq() && watchlist.isEmpty()) {
            log.info("Skipping instrument refresh — allNseEq=false and watchlist empty (nothing to resolve).");
            return;
        }
        KiteAuthService auth = authProvider.getIfAvailable();
        if (auth != null && auth.isAuthenticated()) {
            refreshInstruments();
        } else {
            log.info("Skipping instrument refresh at startup — Kite session not yet authenticated.");
        }
    }

    @PostConstruct
    public synchronized void loadWatchlist() {
        watchlist.clear();
        // Load watchlist from external dir first (hot-reloadable), fall back to classpath.
        if (trading.watchlistFiles() != null) {
            for (String file : trading.watchlistFiles()) {
                boolean loaded = tryLoadExternal(file);
                if (!loaded) tryLoadClasspath(file);
            }
        }
        if (trading.extraSymbols() != null) {
            trading.extraSymbols().forEach(s -> watchlist.add(s.toUpperCase(Locale.ROOT)));
        }
        log.info("Watchlist size: {} symbols", watchlist.size());
    }

    private boolean tryLoadExternal(String file) {
        String base = new java.io.File(file).getName();      // e.g. "watchlist/nifty50.txt" -> "nifty50.txt"
        Path[] candidates = new Path[] {
                Paths.get("watchlists", base),
                Paths.get(file)
        };
        for (Path p : candidates) {
            if (!Files.isReadable(p)) continue;
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                readInto(br);
                log.info("Loaded watchlist from external file: {}", p.toAbsolutePath());
                return true;
            } catch (Exception e) {
                log.warn("Failed reading {}: {}", p, e.getMessage());
            }
        }
        return false;
    }

    private void tryLoadClasspath(String file) {
        try (InputStream in = new ClassPathResource(file).getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            readInto(br);
            log.info("Loaded watchlist from classpath: {}", file);
        } catch (Exception e) {
            log.warn("Could not load watchlist file {}: {}", file, e.getMessage());
        }
    }

    private void readInto(BufferedReader br) throws java.io.IOException {
        String line;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            watchlist.add(s.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Called after Kite auth is confirmed valid — populates the symbol->token map.
     */
    public synchronized void refreshInstruments() {
        try {
            List<Instrument> all = kite.getInstruments(trading.exchange());
            int count = 0;
            for (Instrument i : all) {
                if (!"EQ".equalsIgnoreCase(i.instrument_type)) continue;
                if (!trading.exchange().equalsIgnoreCase(i.segment) &&
                    !trading.exchange().equalsIgnoreCase(i.exchange)) continue;
                symbolToToken.put(i.tradingsymbol.toUpperCase(Locale.ROOT), i.instrument_token);
                count++;
            }
            log.info("Loaded {} NSE EQ instruments from Kite", count);

            // When trading.all-nse-eq=true the watchlist becomes the entire loaded
            // NSE EQ universe (union with any file/extra symbols). This is done
            // AFTER refresh so the token map is populated first.
            if (trading.allNseEq()) {
                int before = watchlist.size();
                watchlist.addAll(symbolToToken.keySet());
                log.info("all-nse-eq=true → watchlist expanded from {} to {} symbols",
                        before, watchlist.size());
            }
        } catch (Throwable e) {
            log.error("Failed to fetch instruments from Kite: {}", e.getMessage());
        }
    }

    public Set<String> watchlist() {
        return Collections.unmodifiableSet(watchlist);
    }

    public Long tokenFor(String symbol) {
        return symbolToToken.get(symbol.toUpperCase(Locale.ROOT));
    }
}

