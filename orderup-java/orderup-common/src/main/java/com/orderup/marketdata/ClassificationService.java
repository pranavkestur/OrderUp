package com.orderup.marketdata;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical, source-of-truth classifier for NSE-listed equities.
 *
 * <h3>Sector</h3>
 * Loaded from NSE Indices' Nifty 500 constituent CSV shipped as
 * {@code classpath:sector-nse500.csv}. The {@code Industry} column is NSE's
 * official taxonomy (same one used to build sectoral indices like NIFTY BANK,
 * NIFTY IT). Refresh once a quarter alongside the semi-annual index review by
 * re-downloading:
 * {@code https://niftyindices.com/IndexConstituent/ind_nifty500list.csv}.
 * Symbols outside the Nifty 500 fall through to {@code null} — the caller
 * decides whether to fall back to a payload-provided sector (e.g. the
 * Chartink {@code columns["sector"]}).
 *
 * <h3>Market cap</h3>
 * Loaded from AMFI's semi-annual categorisation. AMFI publishes this as an
 * XLSX behind a JS-rendered SPA — there is no reliable direct-download URL,
 * so this service looks for a user-provided CSV at:
 * <pre>
 *   data/marketcap-amfi.csv                (external — hot-reloadable, preferred)
 *   classpath:marketcap-amfi.csv           (bundled placeholder)
 * </pre>
 * The CSV must have {@code SYMBOL} and {@code CATEGORY} columns (case-
 * insensitive). {@code CATEGORY} is normalised to one of {@code LARGE_CAP},
 * {@code MID_CAP}, {@code SMALL_CAP}. Unknown symbols return {@code null}.
 *
 * <p>See {@code resources/marketcap-amfi.csv} for the download / refresh
 * instructions bundled in the JAR.
 *
 * <p>Both maps are refreshable in-place via {@link #reload()} without a
 * restart — surfaces via {@code POST /admin/reload-classifications}.
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    /** Where to look for the external hot-reloadable AMFI file. */
    private static final Path[] EXTERNAL_MARKETCAP_CANDIDATES = new Path[]{
            Paths.get("data", "marketcap-amfi.csv"),
            Paths.get("marketcap-amfi.csv")
    };

    /** UPPERCASE tradingsymbol -> canonical NSE Industry string. */
    private volatile Map<String, String> symbolToSector = Map.of();
    /** UPPERCASE tradingsymbol -> LARGE_CAP / MID_CAP / SMALL_CAP. */
    private volatile Map<String, String> symbolToMarketCap = Map.of();

    @PostConstruct
    public void init() { reload(); }

    /** Re-read both classification files from disk / classpath. */
    public synchronized void reload() {
        symbolToSector    = loadSectorMap();
        symbolToMarketCap = loadMarketCapMap();
        log.info("ClassificationService loaded: sectors={} marketCaps={}",
                symbolToSector.size(), symbolToMarketCap.size());
    }

    /**
     * @return canonical NSE Industry sector for {@code symbol}, or null if
     *         the symbol is outside the Nifty 500 universe (in which case the
     *         caller should fall back to whatever payload sector is available).
     */
    public String sectorFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return symbolToSector.get(symbol.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * @return one of {@code LARGE_CAP}, {@code MID_CAP}, {@code SMALL_CAP},
     *         or {@code null} when the symbol isn't in the AMFI list yet
     *         (fresh IPO or user hasn't refreshed the file). Callers should
     *         treat null as "unknown / uncategorised".
     */
    public String marketCapFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return symbolToMarketCap.get(symbol.trim().toUpperCase(Locale.ROOT));
    }

    /** Diagnostic snapshot — used by admin endpoints. */
    public Map<String, Integer> stats() {
        return Map.of("sectors", symbolToSector.size(),
                      "marketCaps", symbolToMarketCap.size());
    }

    /**
     * Immutable snapshot of the full {@code symbol → NSE Industry} map.
     * Used by services that need to invert the mapping (e.g. HeatmapService
     * building a {@code sector-index → constituent symbols} lookup). The
     * returned map is safe to iterate concurrently.
     */
    public Map<String, String> allSymbolSectors() {
        return symbolToSector; // already Map.copyOf(...) — immutable
    }

    // -----------------------------------------------------------------
    // Loaders
    // -----------------------------------------------------------------

    private Map<String, String> loadSectorMap() {
        // Nifty 500 CSV columns: Company Name, Industry, Symbol, Series, ISIN Code
        Map<String, String> out = new HashMap<>();
        try (InputStream in = new ClassPathResource("sector-nse500.csv").getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            int industryIdx = -1, symbolIdx = -1;
            if (header != null) {
                String[] cols = splitCsv(header);
                for (int i = 0; i < cols.length; i++) {
                    String c = cols[i].trim();
                    if (c.equalsIgnoreCase("Industry")) industryIdx = i;
                    else if (c.equalsIgnoreCase("Symbol")) symbolIdx = i;
                }
            }
            if (industryIdx < 0 || symbolIdx < 0) {
                log.warn("sector-nse500.csv missing Industry/Symbol columns; sectors disabled");
                return Map.of();
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsv(line);
                if (cols.length <= Math.max(industryIdx, symbolIdx)) continue;
                String sym = cols[symbolIdx].trim().toUpperCase(Locale.ROOT);
                String sec = cols[industryIdx].trim();
                if (!sym.isEmpty() && !sec.isEmpty()) out.put(sym, sec);
            }
        } catch (Exception e) {
            log.warn("Could not read sector-nse500.csv: {}", e.getMessage());
        }
        return Map.copyOf(out);
    }

    private Map<String, String> loadMarketCapMap() {
        // Prefer the external hot-reloadable file — that's how the user gets
        // the fresh AMFI data in without a rebuild.
        Map<String, String> ext = tryLoadMarketCapExternal();
        if (!ext.isEmpty()) return ext;
        return tryLoadMarketCapClasspath();
    }

    private Map<String, String> tryLoadMarketCapExternal() {
        for (Path p : EXTERNAL_MARKETCAP_CANDIDATES) {
            if (!Files.isReadable(p)) continue;
            try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                Map<String, String> map = readMarketCap(br);
                if (!map.isEmpty()) {
                    log.info("Loaded market-cap classification from external file: {} ({} rows)",
                            p.toAbsolutePath(), map.size());
                    return map;
                }
            } catch (Exception e) {
                log.warn("Failed reading market-cap file {}: {}", p, e.getMessage());
            }
        }
        return Map.of();
    }

    private Map<String, String> tryLoadMarketCapClasspath() {
        try (InputStream in = new ClassPathResource("marketcap-amfi.csv").getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Map<String, String> map = readMarketCap(br);
            if (map.isEmpty()) {
                log.info("classpath:marketcap-amfi.csv is the empty placeholder — market-cap " +
                        "classification disabled until data/marketcap-amfi.csv is provided.");
            }
            return map;
        } catch (Exception e) {
            log.warn("Could not read classpath:marketcap-amfi.csv: {}", e.getMessage());
        }
        return Map.of();
    }

    /**
     * Parse either the AMFI-native XLSX-derived CSV (with columns like
     * {@code Sl. No, Company Name, Symbol, Average Market Cap, Categorization})
     * or the minimal {@code SYMBOL,CATEGORY} shape. Column matching is
     * case-insensitive and header-driven so the user doesn't have to
     * hand-massage the file after Excel export.
     */
    private static Map<String, String> readMarketCap(BufferedReader br) throws java.io.IOException {
        Map<String, String> out = new HashMap<>();
        String header = null;
        String line;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            header = s;
            break;
        }
        if (header == null) return out;
        String[] cols = splitCsv(header);
        int symbolIdx = -1, catIdx = -1;
        for (int i = 0; i < cols.length; i++) {
            String c = cols[i].trim().toLowerCase(Locale.ROOT);
            if (c.equals("symbol") || c.contains("symbol")) symbolIdx = i;
            else if (c.equals("category") || c.contains("categor")) catIdx = i;
        }
        if (symbolIdx < 0 || catIdx < 0) return out;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] vals = splitCsv(s);
            if (vals.length <= Math.max(symbolIdx, catIdx)) continue;
            String sym = vals[symbolIdx].trim().toUpperCase(Locale.ROOT);
            String cat = normaliseCategory(vals[catIdx].trim());
            if (!sym.isEmpty() && cat != null) out.put(sym, cat);
        }
        return Map.copyOf(out);
    }

    /**
     * AMFI writes categories as "Large Cap" / "Mid Cap" / "Small Cap" (with
     * variants like "Largecap", "LARGE_CAP", etc). Normalise to a single
     * canonical form so downstream code can just string-compare.
     */
    private static String normaliseCategory(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        if (s.startsWith("largecap") || s.equals("large")) return "LARGE_CAP";
        if (s.startsWith("midcap")   || s.equals("mid"))   return "MID_CAP";
        if (s.startsWith("smallcap") || s.equals("small")) return "SMALL_CAP";
        return null;
    }

    /**
     * Minimal CSV splitter that respects double-quoted fields — the AMFI file
     * quotes company names containing commas. Not a full RFC 4180 parser but
     * handles every case we've seen in the NSE/AMFI files.
     */
    private static String[] splitCsv(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') { inQuote = !inQuote; continue; }
            if (c == ',' && !inQuote) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}

