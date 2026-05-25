package com.tradingapp.data;

import java.util.*;

public class PriceHistory {

    private static final int MAX_BARS = 200;

    // Intraday tick stream — used for trailing stops and live price display
    private final Map<String, ArrayDeque<Double>> prices = new HashMap<>();
    private final Map<String, ArrayDeque<Double>> volumes = new HashMap<>();

    // Daily-resolution close prices — used for indicator calculations
    private final Map<String, ArrayDeque<Double>> dailyPrices = new HashMap<>();
    private final Map<String, ArrayDeque<Double>> dailyVolumes = new HashMap<>();

    public void record(String symbol, double price, double volume) {
        if (!Double.isFinite(price) || price <= 0) return;
        ArrayDeque<Double> p = prices.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        ArrayDeque<Double> v = volumes.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        p.addLast(price);
        v.addLast(volume);
        if (p.size() > MAX_BARS) p.pollFirst();
        if (v.size() > MAX_BARS) v.pollFirst();
    }

    /** Records one daily close. Called at most once per trading day per symbol from TradingLoop. */
    public void recordDaily(String symbol, double price, double volume) {
        if (!Double.isFinite(price) || price <= 0) return;
        ArrayDeque<Double> p = dailyPrices.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        ArrayDeque<Double> v = dailyVolumes.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        p.addLast(price);
        v.addLast(volume);
        if (p.size() > MAX_BARS) p.pollFirst();
        if (v.size() > MAX_BARS) v.pollFirst();
    }

    public List<Double> getPrices(String symbol) {
        return new ArrayList<>(prices.getOrDefault(symbol, new ArrayDeque<>()));
    }

    public List<Double> getVolumes(String symbol) {
        return new ArrayList<>(volumes.getOrDefault(symbol, new ArrayDeque<>()));
    }

    /** Daily-resolution closes seeded from historical bars or recorded via recordDaily(). */
    public List<Double> getDailyPrices(String symbol) {
        return new ArrayList<>(dailyPrices.getOrDefault(symbol, new ArrayDeque<>()));
    }

    public List<Double> getDailyVolumes(String symbol) {
        return new ArrayList<>(dailyVolumes.getOrDefault(symbol, new ArrayDeque<>()));
    }

    public int size(String symbol) {
        return prices.getOrDefault(symbol, new ArrayDeque<>()).size();
    }

    public void clear(String symbol) {
        prices.remove(symbol);
        volumes.remove(symbol);
        dailyPrices.remove(symbol);
        dailyVolumes.remove(symbol);
    }

    /** Seeds both intraday and daily stores from historical bars (oldest-first). Replaces existing data. */
    public void seed(String symbol, List<HistoricalBar> bars) {
        if (bars == null || bars.isEmpty()) return;
        ArrayDeque<Double> p = new ArrayDeque<>();
        ArrayDeque<Double> v = new ArrayDeque<>();
        int start = Math.max(0, bars.size() - MAX_BARS);
        for (int i = start; i < bars.size(); i++) {
            HistoricalBar bar = bars.get(i);
            double close = bar.getClose();
            if (Double.isFinite(close) && close > 0) {
                p.addLast(close);
                v.addLast((double) bar.getVolume());
            }
        }
        if (!p.isEmpty()) {
            prices.put(symbol, p);
            volumes.put(symbol, v);
            dailyPrices.put(symbol, new ArrayDeque<>(p));
            dailyVolumes.put(symbol, new ArrayDeque<>(v));
        }
    }
}
