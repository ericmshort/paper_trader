package com.tradingapp.engine;

import java.util.HashMap;
import java.util.Map;

public class TrailingStopMonitor {

    private final Map<String, Double> peaks = new HashMap<>();
    private double trailingStopPct = 0.04;

    public void updatePeak(String symbol, double price) {
        peaks.put(symbol, Math.max(price, peaks.getOrDefault(symbol, price)));
    }

    public boolean check(String symbol, double currentPrice) {
        double peak = peaks.getOrDefault(symbol, currentPrice);
        if (currentPrice > peak) {
            peaks.put(symbol, currentPrice);
            return false;
        }
        return currentPrice <= peak * (1 - trailingStopPct);
    }

    public void reset(String symbol) {
        peaks.remove(symbol);
    }

    public void resetAll() {
        peaks.clear();
    }

    public double getTrailingStopPct() { return trailingStopPct; }
    public void setTrailingStopPct(double pct) { this.trailingStopPct = pct; }
}
