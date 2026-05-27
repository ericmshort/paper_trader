package com.tradingapp.engine;

import java.util.List;

/**
 * RSI-only momentum strategy with a 2% trailing stop.
 * Buys when RSI is oversold (< 30), sells when RSI is overbought (> 70).
 * Trailing stop tracks the peak price from entry and exits if price falls 2% below that peak.
 */
public class RSIMomentumStrategy {

    static final double TRAILING_STOP_PCT = 0.02;

    private final IndicatorEngine indicatorEngine;
    private double peak = Double.NaN;

    public RSIMomentumStrategy(IndicatorEngine indicatorEngine) {
        this.indicatorEngine = indicatorEngine;
    }

    public SignalResult.Direction signal(List<Double> prices) {
        return indicatorEngine.computeRSI(prices).getDirection();
    }

    /**
     * Updates the trailing peak and returns true if the 2% drawdown threshold is breached.
     * Only meaningful while a position is held — call {@link #onPositionOpened} at entry.
     */
    public boolean isTrailingStopHit(double currentPrice) {
        if (Double.isNaN(peak)) {
            peak = currentPrice;
            return false;
        }
        if (currentPrice > peak) {
            peak = currentPrice;
        }
        return currentPrice <= peak * (1.0 - TRAILING_STOP_PCT);
    }

    public void onPositionOpened(double entryPrice) {
        peak = entryPrice;
    }

    public void onPositionClosed() {
        peak = Double.NaN;
    }

    public double getTrailingStopPct() {
        return TRAILING_STOP_PCT;
    }
}
