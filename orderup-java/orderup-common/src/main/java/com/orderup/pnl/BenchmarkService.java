package com.orderup.pnl;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.models.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves benchmark index names (NIFTY 50, sector indices) to Kite
 * instrument tokens so the "price journey" drawer can overlay a "did the
 * whole market sell off?" line on top of a single stock's candles.
 *
 * <p>Index tokens are not present in {@link com.orderup.marketdata.InstrumentService}
 * (which is EQ-only). We lazily fetch the full {@code NSE} instrument dump
 * on first use and cache the {@code (name -> token)} map for the process
 * lifetime — index tokens never change.
 *
 * <p>Sector strings coming from the Chartink payload are free-form
 * (e.g. "banking", "IT - Software"). {@link #benchNameForSector(String)}
 * normalises them to the closest NSE index name; if there's no confident
 * mapping we simply return {@code null} and the frontend disables the
 * "vs sector" toggle for that trade.
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    /** Canonical NSE index name → sector-string keywords that route to it. */
    private static final Map<String, String[]> SECTOR_KEYWORDS = new LinkedHashMap<>();
    static {
        SECTOR_KEYWORDS.put("NIFTY BANK",        new String[]{"bank","banking"});
        SECTOR_KEYWORDS.put("NIFTY IT",          new String[]{"it","information tech","software","technology"});
        SECTOR_KEYWORDS.put("NIFTY AUTO",        new String[]{"auto","automobile","automotive"});
        SECTOR_KEYWORDS.put("NIFTY PHARMA",      new String[]{"pharma","pharmaceutical","healthcare","health"});
        SECTOR_KEYWORDS.put("NIFTY FMCG",        new String[]{"fmcg","consumer goods","consumer non-durables"});
        SECTOR_KEYWORDS.put("NIFTY ENERGY",      new String[]{"energy","power","oil","gas","petroleum"});
        SECTOR_KEYWORDS.put("NIFTY METAL",       new String[]{"metal","metals","mining","steel"});
        SECTOR_KEYWORDS.put("NIFTY REALTY",      new String[]{"realty","real estate","construction"});
        SECTOR_KEYWORDS.put("NIFTY MEDIA",       new String[]{"media","entertainment","broadcast"});
        SECTOR_KEYWORDS.put("NIFTY PSU BANK",    new String[]{"psu bank","public sector bank"});
        SECTOR_KEYWORDS.put("NIFTY PVT BANK",    new String[]{"private bank","pvt bank"});
        SECTOR_KEYWORDS.put("NIFTY FIN SERVICE", new String[]{"finance","financial","fin service","nbfc"});
        SECTOR_KEYWORDS.put("NIFTY CONSUMPTION", new String[]{"consumption","consumer durables","retail"});
    }

    private final KiteConnect kite;
    private volatile Map<String, Long> nameToToken; // null until first load

    public BenchmarkService(KiteConnect kite) {
        this.kite = kite;
    }

    /** @return instrument token for {@code name} (e.g. "NIFTY 50"), or null. */
    public Long tokenFor(String name) {
        if (name == null || name.isBlank()) return null;
        ensureLoaded();
        Map<String, Long> map = nameToToken;
        if (map == null) return null;
        return map.get(name.toUpperCase(Locale.ROOT).trim());
    }

    /**
     * Map a free-form sector string (from OrderRecord.sector) to a canonical
     * NSE sector index name. Case-insensitive substring match against the
     * keyword table. Returns {@code null} when no confident match is found.
     */
    public String benchNameForSector(String sector) {
        if (sector == null || sector.isBlank()) return null;
        String s = sector.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String[]> e : SECTOR_KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (s.contains(kw)) return e.getKey();
            }
        }
        return null;
    }

    /** Ordered list of canonical benchmark names — populates the UI dropdown. */
    public List<String> knownBenchmarks() {
        List<String> out = new java.util.ArrayList<>();
        out.add("NIFTY 50");
        out.addAll(SECTOR_KEYWORDS.keySet());
        return out;
    }

    private synchronized void ensureLoaded() {
        if (nameToToken != null) return;
        try {
            List<Instrument> all = kite.getInstruments("NSE");
            Map<String, Long> map = new HashMap<>();
            for (Instrument i : all) {
                // Kite indices carry segment "INDICES" (uppercase) and
                // instrument_type "INDICES" or "EQ" depending on feed version.
                if (i.segment == null || !i.segment.toUpperCase(Locale.ROOT).contains("INDICES")) continue;
                if (i.tradingsymbol == null) continue;
                map.put(i.tradingsymbol.toUpperCase(Locale.ROOT).trim(), i.instrument_token);
            }
            log.info("BenchmarkService: loaded {} NSE index tokens", map.size());
            nameToToken = map;
        } catch (Throwable t) {
            log.warn("BenchmarkService: failed to load index tokens: {}", t.getMessage());
            nameToToken = Map.of(); // negative-cache so we don't hammer Kite
        }
    }
}

