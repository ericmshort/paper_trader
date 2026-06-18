package com.tradingapp.broker;

import com.tradingapp.data.MasterUniverse;
import com.tradingapp.engine.IntradayBar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Nightly ranking engine: given minute-bar history for the master universe,
 * selects the top-30 symbols for each trading day under four strategies.
 *
 * Rankings are computed from data available through EOD of the prior trading day
 * (no look-ahead). Each strategy always anchors SPY + QQQ in the top-30.
 *
 * Strategies:
 *   STATIC      — fixed Config-D 25-symbol options list (baseline, no rotation)
 *   MOMENTUM    — top 28 by 5-day return relative to SPY
 *   VOLATILITY  — top 28 by 20-day annualized realized volatility
 *   COMPOSITE   — top 28 by 50% normalized-momentum-rank + 50% normalized-vol-rank
 */
public class DailyRankingEngine {

    public enum Strategy { STATIC, MOMENTUM, VOLATILITY, COMPOSITE }

    // Config-D fixed options allowlist (25 symbols, never rotates)
    private static final Set<String> STATIC_ALLOWLIST = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR",
            "LLY","ORCL","RTX","GS","TSM","TGT",
            "MA","UNH","ADBE","LOW","COP","XOM",
            "NET","CRWD","PG","AMD","WMT","QCOM");

    // Watchlist for static (same 30 as Config D)
    private static final List<String> STATIC_WATCHLIST = List.of(
            "SPY","QQQ","AAPL","MSFT","NVDA","TSLA","META","AMZN","PLTR",
            "LLY","ORCL","RTX","GS","TSM","TGT","MA","UNH","ADBE","LOW",
            "COP","XOM","NET","CRWD","PG","AMD","WMT","QCOM","GILD","AXP","MRNA");

    // Options never opened on these regardless of which strategy is used
    private static final Set<String> OPTIONS_EXCLUDED = Set.of("QQQ", "TSLA");

    private static final int ACTIVE_SIZE    = 30;
    private static final int MOMENTUM_DAYS  =  5;
    private static final int VOL_DAYS       = 20;
    private static final int WARMUP_DAYS    = VOL_DAYS + 2;

    // Precomputed: symbol → sorted list of (date, closePrice)
    private final Map<String, TreeMap<LocalDate, Double>> dailyCloses;
    private final List<LocalDate> allTradingDays;

    public DailyRankingEngine(Map<String, List<IntradayBar>> barsBySymbol) {
        this.dailyCloses = buildDailyCloses(barsBySymbol);
        Set<LocalDate> days = new HashSet<>();
        barsBySymbol.values().forEach(bars -> bars.forEach(b -> days.add(b.time().toLocalDate())));
        this.allTradingDays = new ArrayList<>(days);
        Collections.sort(this.allTradingDays);
    }

    /**
     * Precomputes daily watchlists for all trading days under the given strategy.
     * Returns map: date → active symbol set (the 30 to trade that day).
     */
    public Map<LocalDate, Set<String>> buildDailyWatchlists(Strategy strategy) {
        Map<LocalDate, Set<String>> result = new LinkedHashMap<>();
        for (LocalDate date : allTradingDays) {
            result.put(date, selectForDay(date, strategy));
        }
        return result;
    }

    /**
     * Precomputes daily options allowlists for all days.
     * STATIC: returns the curated 25-symbol Config-D allowlist (excludes QQQ, TSLA, AXP, GILD, MRNA).
     * Dynamic strategies: watchlist minus OPTIONS_EXCLUDED (QQQ, TSLA).
     */
    public Map<LocalDate, Set<String>> buildDailyAllowlists(Strategy strategy) {
        Map<LocalDate, Set<String>> result = new LinkedHashMap<>();
        buildDailyWatchlists(strategy).forEach((date, watchlist) -> {
            if (strategy == Strategy.STATIC) {
                result.put(date, new HashSet<>(STATIC_ALLOWLIST));
            } else {
                Set<String> allowlist = new HashSet<>(watchlist);
                allowlist.removeAll(OPTIONS_EXCLUDED);
                result.put(date, allowlist);
            }
        });
        return result;
    }

    // ── Per-day selection ────────────────────────────────────────────────────

    private Set<String> selectForDay(LocalDate date, Strategy strategy) {
        if (strategy == Strategy.STATIC) {
            return new HashSet<>(STATIC_WATCHLIST);
        }

        // Find prior trading days to use as lookback window
        List<LocalDate> priorDays = priorTradingDays(date, WARMUP_DAYS + 1);
        if (priorDays.size() < WARMUP_DAYS) {
            // Not enough history — fall back to static list
            return new HashSet<>(STATIC_WATCHLIST);
        }

        List<String> candidates = new ArrayList<>(MasterUniverse.SYMBOLS);
        candidates.removeAll(MasterUniverse.ANCHORS);  // anchors added separately

        Map<String, Double> scores = new HashMap<>();
        for (String sym : candidates) {
            Double score = computeScore(sym, priorDays, strategy);
            if (score != null) scores.put(sym, score);
        }

        // Sort candidates by score descending, take top (ACTIVE_SIZE - anchors.size())
        int slots = ACTIVE_SIZE - MasterUniverse.ANCHORS.size();
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Set<String> selected = new HashSet<>(MasterUniverse.ANCHORS);
        for (int i = 0; i < Math.min(slots, ranked.size()); i++) {
            selected.add(ranked.get(i).getKey());
        }
        return selected;
    }

    private Double computeScore(String sym, List<LocalDate> priorDays, Strategy strategy) {
        return switch (strategy) {
            case MOMENTUM  -> momentumScore(sym, priorDays);
            case VOLATILITY -> volScore(sym, priorDays);
            case COMPOSITE -> compositeScore(sym, priorDays);
            default -> null;
        };
    }

    // ── Metric computations ──────────────────────────────────────────────────

    /** 5-day return of symbol minus 5-day return of SPY (higher = better relative momentum). */
    private Double momentumScore(String sym, List<LocalDate> priorDays) {
        if (priorDays.size() < MOMENTUM_DAYS + 1) return null;
        Double symNow  = closeOn(sym, priorDays.get(priorDays.size() - 1));
        Double symThen = closeOn(sym, priorDays.get(priorDays.size() - 1 - MOMENTUM_DAYS));
        Double spyNow  = closeOn("SPY", priorDays.get(priorDays.size() - 1));
        Double spyThen = closeOn("SPY", priorDays.get(priorDays.size() - 1 - MOMENTUM_DAYS));
        if (symNow == null || symThen == null || spyNow == null || spyThen == null
                || symThen == 0 || spyThen == 0) return null;
        double symRet = (symNow - symThen) / symThen;
        double spyRet = (spyNow - spyThen) / spyThen;
        return symRet - spyRet;
    }

    /** 20-day annualized realized volatility (std dev of daily log returns × √252). */
    private Double volScore(String sym, List<LocalDate> priorDays) {
        if (priorDays.size() < VOL_DAYS + 1) return null;
        List<Double> closes = new ArrayList<>();
        for (int i = priorDays.size() - 1 - VOL_DAYS; i < priorDays.size(); i++) {
            Double c = closeOn(sym, priorDays.get(i));
            if (c == null) return null;
            closes.add(c);
        }
        List<Double> logRets = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            if (closes.get(i - 1) <= 0) return null;
            logRets.add(Math.log(closes.get(i) / closes.get(i - 1)));
        }
        double mean = logRets.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = logRets.stream().mapToDouble(r -> (r - mean) * (r - mean)).average().orElse(0);
        return Math.sqrt(variance) * Math.sqrt(252);
    }

    /**
     * Composite: 50% percentile rank by momentum + 50% percentile rank by vol.
     * Computed by scoring all candidates, then ranking the result for the caller.
     * Since this is called per-symbol, we use raw scores and normalize at selection time.
     */
    private Double compositeScore(String sym, List<LocalDate> priorDays) {
        Double mom = momentumScore(sym, priorDays);
        Double vol = volScore(sym, priorDays);
        if (mom == null || vol == null) return null;
        // Raw composite — will be ranked relative to other symbols in selectForDay
        // We normalize by building full universe scores then re-ranking, but since
        // selectForDay already sorts descending, we need a single combined value.
        // Strategy: normalize each to [0,1] using tanh for scale-invariance.
        double normMom = 0.5 + 0.5 * Math.tanh(mom / 0.02);  // 2% move ≈ midpoint
        double normVol = Math.min(1.0, vol / 0.80);            // 80% annualized vol ≈ ceiling
        return 0.5 * normMom + 0.5 * normVol;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Double closeOn(String sym, LocalDate date) {
        TreeMap<LocalDate, Double> closes = dailyCloses.get(sym);
        if (closes == null) return null;
        // Use floor entry in case this exact date is missing (e.g. holiday)
        Map.Entry<LocalDate, Double> entry = closes.floorEntry(date);
        return entry != null ? entry.getValue() : null;
    }

    private List<LocalDate> priorTradingDays(LocalDate before, int count) {
        List<LocalDate> result = new ArrayList<>();
        for (int i = allTradingDays.size() - 1; i >= 0; i--) {
            if (allTradingDays.get(i).isBefore(before)) {
                result.add(0, allTradingDays.get(i));
                if (result.size() == count) break;
            }
        }
        return result;
    }

    private static Map<String, TreeMap<LocalDate, Double>> buildDailyCloses(
            Map<String, List<IntradayBar>> barsBySymbol) {
        Map<String, TreeMap<LocalDate, Double>> result = new HashMap<>();
        for (Map.Entry<String, List<IntradayBar>> e : barsBySymbol.entrySet()) {
            TreeMap<LocalDate, Double> closes = new TreeMap<>();
            for (IntradayBar bar : e.getValue()) {
                LocalDate d = bar.time().toLocalDate();
                // Keep last bar of each day (latest timestamp wins)
                closes.merge(d, bar.close(), (existing, newVal) -> newVal);
            }
            result.put(e.getKey(), closes);
        }
        return result;
    }
}
