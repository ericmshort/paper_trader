package com.tradingapp.engine;

import java.util.HashMap;
import java.util.Map;

public class TrailingStopMonitor {

    private final Map<String, Double> peaks = new HashMap<>();

    public void updatePeak(String symbol, double price) {
        peaks.put(symbol, Math.max(price, peaks.getOrDefault(symbol, price)));
    }

    public boolean check(String symbol, double currentPrice) {
        double peak = peaks.getOrDefault(symbol, currentPrice);
        if (currentPrice > peak) {
            peaks.put(symbol, currentPrice);
            return false;
        }
        return currentPrice <= peak * 0.95;
    }

    public void reset(String symbol) {
        peaks.remove(symbol);
    }
}
