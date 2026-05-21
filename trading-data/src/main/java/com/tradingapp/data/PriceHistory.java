package com.tradingapp.data;

import java.util.*;

public class PriceHistory {

    private static final int MAX_BARS = 200;

    private final Map<String, ArrayDeque<Double>> prices = new HashMap<>();
    private final Map<String, ArrayDeque<Double>> volumes = new HashMap<>();

    public void record(String symbol, double price, double volume) {
        if (!Double.isFinite(price) || price <= 0) return;
        ArrayDeque<Double> p = prices.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        ArrayDeque<Double> v = volumes.computeIfAbsent(symbol, k -> new ArrayDeque<>());
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

    public int size(String symbol) {
        return prices.getOrDefault(symbol, new ArrayDeque<>()).size();
    }

    public void clear(String symbol) {
        prices.remove(symbol);
        volumes.remove(symbol);
    }

    /** Seeds price/volume history from historical bars (oldest-first). Replaces any existing data. */
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
        }
    }
}
