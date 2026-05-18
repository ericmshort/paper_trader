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
}
