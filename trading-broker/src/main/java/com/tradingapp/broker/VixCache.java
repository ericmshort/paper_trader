package com.tradingapp.broker;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.HistoricalBarFetcher;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Fetches and caches daily VIX closing values from Yahoo Finance.
 * Used by the backtest to scale Black-Scholes sigma to reflect actual market IV levels.
 */
public class VixCache {

    private static final Logger LOG = Logger.getLogger(VixCache.class.getName());
    private static final Path CACHE_FILE = Path.of(System.getProperty("user.home"),
            ".tradingapp", "bar-cache", "vix", "vix.json");
    // ^VIX URL-encoded — URI.create() rejects bare ^ in path segments
    private static final String VIX_YAHOO = "%5EVIX";

    private final TreeMap<LocalDate, Double> vixByDate = new TreeMap<>();
    private double baselineVix = 18.0;

    /**
     * Loads VIX history covering [start, end], fetching any missing range from Yahoo Finance.
     * Silently falls back to the baseline (18) if the fetch fails.
     */
    public void load(LocalDate start, LocalDate end) {
        if (Files.exists(CACHE_FILE)) {
            try {
                JSONObject obj = new JSONObject(Files.readString(CACHE_FILE));
                for (String key : obj.keySet()) {
                    vixByDate.put(LocalDate.parse(key), obj.getDouble(key));
                }
            } catch (Exception e) {
                LOG.warning("VIX cache read failed: " + e.getMessage());
            }
        }

        boolean cacheCoversRange = !vixByDate.isEmpty()
                && !vixByDate.firstKey().isAfter(start)
                && !vixByDate.lastKey().isBefore(end);

        if (!cacheCoversRange) {
            // Start slightly before the requested window to get a floor entry for floorEntry() lookups
            LocalDate fetchFrom = vixByDate.isEmpty() ? start.minusDays(30)
                    : vixByDate.firstKey().isBefore(start)
                            ? vixByDate.lastKey().minusDays(2)
                            : start.minusDays(30);
            System.out.println("Fetching VIX data " + fetchFrom + " -> " + end + "...");
            try {
                List<HistoricalBar> bars = new HistoricalBarFetcher().fetchDailyBars(VIX_YAHOO, fetchFrom, end);
                for (HistoricalBar bar : bars) {
                    vixByDate.put(bar.getDate(), bar.getClose());
                }
                persist();
                System.out.println("VIX cache: " + vixByDate.size() + " trading days.");
            } catch (Exception e) {
                LOG.warning("VIX fetch failed — IV scaling disabled for this run: " + e.getMessage());
            }
        }

        if (!vixByDate.isEmpty()) {
            baselineVix = vixByDate.values().stream()
                    .mapToDouble(Double::doubleValue).average().orElse(18.0);
        }
        System.out.printf("VIX baseline: %.1f%n", baselineVix);
    }

    /** VIX close for the given date; falls back to the nearest earlier trading day. */
    public double getVix(LocalDate date) {
        Map.Entry<LocalDate, Double> entry = vixByDate.floorEntry(date);
        return entry != null ? entry.getValue() : baselineVix;
    }

    /** Long-term average VIX over the cached history. Used as the normalization denominator. */
    public double baselineVix() { return baselineVix; }

    private void persist() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            JSONObject obj = new JSONObject();
            for (Map.Entry<LocalDate, Double> e : vixByDate.entrySet()) {
                obj.put(e.getKey().toString(), e.getValue());
            }
            Files.writeString(CACHE_FILE, obj.toString());
        } catch (Exception e) {
            LOG.warning("VIX cache write failed: " + e.getMessage());
        }
    }
}
