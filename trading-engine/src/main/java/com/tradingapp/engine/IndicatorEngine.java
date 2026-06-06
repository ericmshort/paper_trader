package com.tradingapp.engine;

import com.tradingapp.data.CandleBar;
import com.tradingapp.data.CandleHistory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

public class IndicatorEngine {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // ── RSI (period=9 for faster intraday response) ────────────────────────────

    public SignalResult computeRSI(List<Double> prices) {
        int period = 9;
        if (prices.size() < period + 1) {
            return SignalResult.neutral("RSI", 0);
        }
        double avgGain = 0, avgLoss = 0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;
        if (avgLoss == 0) return SignalResult.sell("RSI", 100);
        double rsi = 100 - (100 / (1 + avgGain / avgLoss));
        if (rsi < 35) return SignalResult.buy("RSI", rsi);
        if (rsi > 65) return SignalResult.sell("RSI", rsi);
        return SignalResult.neutral("RSI", rsi);
    }

    // ── Bollinger Bands (20-period) ────────────────────────────────────────────

    public SignalResult computeBollingerBands(List<Double> prices, double currentPrice) {
        int period = 20;
        if (prices.size() < period) return SignalResult.neutral("BollingerBands", 0);
        List<Double> window = prices.subList(prices.size() - period, prices.size());
        double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = window.stream().mapToDouble(p -> (p - mean) * (p - mean)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        double upper = mean + 2 * stdDev;
        double lower = mean - 2 * stdDev;
        if (currentPrice < lower) return SignalResult.buy("BollingerBands", currentPrice);
        if (currentPrice > upper) return SignalResult.sell("BollingerBands", currentPrice);
        return SignalResult.neutral("BollingerBands", currentPrice);
    }

    // ── Volume Surge ───────────────────────────────────────────────────────────

    public SignalResult computeVolumeSurge(List<Double> volumes, double currentVolume,
                                           double priceChangePct) {
        if (volumes.size() < 20) return SignalResult.neutral("VolumeSurge", 0);
        double avgVolume = volumes.subList(0, 20).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0);
        double ratio = avgVolume > 0 ? currentVolume / avgVolume : 0;
        if (currentVolume < avgVolume * 1.5) return SignalResult.neutral("VolumeSurge", ratio);
        if (priceChangePct > 0) return SignalResult.buy("VolumeSurge", ratio);
        if (priceChangePct < 0) return SignalResult.sell("VolumeSurge", ratio);
        return SignalResult.neutral("VolumeSurge", ratio);
    }

    // ── VWAP ───────────────────────────────────────────────────────────────────
    // Computed from all 1-min bars since market open.
    // Price above VWAP = bullish institutional bias; below = bearish.

    public SignalResult computeVwap(List<CandleBar> oneMinBars, double currentPrice) {
        if (oneMinBars.isEmpty()) return SignalResult.neutral("VWAP", 0);
        double totalPV = 0, totalVol = 0;
        for (CandleBar b : oneMinBars) {
            totalPV  += b.getCumulativePriceVolume();
            totalVol += b.getVolume();
        }
        if (totalVol == 0) return SignalResult.neutral("VWAP", 0);
        double vwap = totalPV / totalVol;
        double pct = (currentPrice - vwap) / vwap;
        // Require >0.1% deviation to avoid noise at the open
        if (pct >  0.001) return SignalResult.buy("VWAP", vwap);
        if (pct < -0.001) return SignalResult.sell("VWAP", vwap);
        return SignalResult.neutral("VWAP", vwap);
    }

    // ── Opening Range Breakout ─────────────────────────────────────────────────
    // ORB uses the first 5-min candle (9:30-9:35 ET) as the range.
    // Breakout above the high = BUY; breakdown below the low = SELL.
    // Returns NEUTRAL during the first 5 minutes (range still forming).

    public SignalResult computeOrb(List<CandleBar> fiveMinBars, double currentPrice) {
        if (fiveMinBars.isEmpty()) return SignalResult.neutral("ORB", 0);

        // ORB range is the first completed 5-min bar of the trading day
        CandleBar orb = fiveMinBars.get(0);
        ZonedDateTime orbStart = orb.getPeriodStart().atZone(ET);

        // Still in the opening range period — wait for confirmation
        if (fiveMinBars.size() < 2) return SignalResult.neutral("ORB", 0);

        // Check if the first bar is actually from market open (9:30 ET)
        // If the first bar isn't from 9:30, we may be mid-session after restart — use it anyway
        double orbHigh = orb.getHigh();
        double orbLow  = orb.getLow();

        if (currentPrice > orbHigh) return SignalResult.buy("ORB", orbHigh);
        if (currentPrice < orbLow)  return SignalResult.sell("ORB", orbLow);
        return SignalResult.neutral("ORB", (orbHigh + orbLow) / 2.0);
    }

    // ── Candlestick Patterns ───────────────────────────────────────────────────
    // Uses 5-min bars for more reliable signal (less noise than 1-min).
    // Returns the strongest pattern found, or NEUTRAL.

    public SignalResult computeCandlestickPatterns(List<CandleBar> fiveMinBars) {
        if (fiveMinBars.size() < 2) return SignalResult.neutral("Candlestick", 0);

        CandleBar curr = fiveMinBars.get(fiveMinBars.size() - 1);
        CandleBar prev = fiveMinBars.get(fiveMinBars.size() - 2);

        // Bullish Engulfing — strongest single-session reversal
        if (isBullishEngulfing(prev, curr))   return SignalResult.buy("Candlestick", 1);
        // Bearish Engulfing
        if (isBearishEngulfing(prev, curr))   return SignalResult.sell("Candlestick", -1);
        // Hammer (bullish reversal at bottom)
        if (isHammer(curr))                   return SignalResult.buy("Candlestick", 0.8);
        // Shooting Star (bearish reversal at top)
        if (isShootingStarOrHangingMan(curr)) return SignalResult.sell("Candlestick", -0.8);

        // Bull Flag / Bear Flag (needs 6+ bars)
        if (fiveMinBars.size() >= 6) {
            int flag = detectFlag(fiveMinBars);
            if (flag > 0) return SignalResult.buy("Candlestick", 0.9);
            if (flag < 0) return SignalResult.sell("Candlestick", -0.9);
        }

        // Morning Star (3-candle bullish reversal)
        if (fiveMinBars.size() >= 3) {
            CandleBar prev2 = fiveMinBars.get(fiveMinBars.size() - 3);
            if (isMorningStar(prev2, prev, curr))  return SignalResult.buy("Candlestick", 0.85);
            if (isEveningStar(prev2, prev, curr))  return SignalResult.sell("Candlestick", -0.85);
        }

        // Doji — indecision; lean with the prior candle's direction
        if (isDoji(curr)) {
            if (prev.isBullish()) return SignalResult.buy("Candlestick", 0.4);
            if (prev.isBearish()) return SignalResult.sell("Candlestick", -0.4);
        }

        // Inside Bar — compression; break in prior bar's direction
        if (isInsideBar(prev, curr)) {
            if (prev.isBullish()) return SignalResult.buy("Candlestick", 0.5);
            if (prev.isBearish()) return SignalResult.sell("Candlestick", -0.5);
        }

        return SignalResult.neutral("Candlestick", 0);
    }

    // ── Pattern helpers ────────────────────────────────────────────────────────

    private boolean isBullishEngulfing(CandleBar prev, CandleBar curr) {
        return prev.isBearish()
                && curr.isBullish()
                && curr.getOpen() <= prev.getClose()
                && curr.getClose() >= prev.getOpen()
                && curr.getBodySize() > prev.getBodySize() * 0.8;
    }

    private boolean isBearishEngulfing(CandleBar prev, CandleBar curr) {
        return prev.isBullish()
                && curr.isBearish()
                && curr.getOpen() >= prev.getClose()
                && curr.getClose() <= prev.getOpen()
                && curr.getBodySize() > prev.getBodySize() * 0.8;
    }

    private boolean isHammer(CandleBar bar) {
        if (bar.getRange() == 0) return false;
        double bodyRatio  = bar.getBodySize() / bar.getRange();
        double lowerRatio = bar.getLowerShadow() / bar.getRange();
        double upperRatio = bar.getUpperShadow() / bar.getRange();
        // Small body (≤30% of range) near the top, long lower shadow (≥50%), tiny upper shadow
        return bodyRatio <= 0.30 && lowerRatio >= 0.50 && upperRatio <= 0.15;
    }

    private boolean isShootingStarOrHangingMan(CandleBar bar) {
        if (bar.getRange() == 0) return false;
        double bodyRatio  = bar.getBodySize() / bar.getRange();
        double upperRatio = bar.getUpperShadow() / bar.getRange();
        double lowerRatio = bar.getLowerShadow() / bar.getRange();
        // Small body (≤30%) near the bottom, long upper shadow (≥50%), tiny lower shadow
        return bodyRatio <= 0.30 && upperRatio >= 0.50 && lowerRatio <= 0.15;
    }

    private boolean isDoji(CandleBar bar) {
        if (bar.getRange() == 0) return false;
        return bar.getBodySize() / bar.getRange() < 0.08;
    }

    private boolean isInsideBar(CandleBar prev, CandleBar curr) {
        return curr.getHigh() < prev.getHigh() && curr.getLow() > prev.getLow();
    }

    private boolean isMorningStar(CandleBar first, CandleBar middle, CandleBar last) {
        boolean firstBear = first.isBearish() && first.getBodySize() / Math.max(first.getRange(), 0.01) > 0.3;
        boolean middleSmall = middle.getBodySize() / Math.max(middle.getRange(), 0.01) < 0.3;
        boolean lastBull = last.isBullish() && last.getClose() > (first.getOpen() + first.getClose()) / 2.0;
        return firstBear && middleSmall && lastBull;
    }

    private boolean isEveningStar(CandleBar first, CandleBar middle, CandleBar last) {
        boolean firstBull = first.isBullish() && first.getBodySize() / Math.max(first.getRange(), 0.01) > 0.3;
        boolean middleSmall = middle.getBodySize() / Math.max(middle.getRange(), 0.01) < 0.3;
        boolean lastBear = last.isBearish() && last.getClose() < (first.getOpen() + first.getClose()) / 2.0;
        return firstBull && middleSmall && lastBear;
    }

    /**
     * Detects a Bull Flag or Bear Flag over the last N bars.
     * Bull Flag: sharp rise (flagpole) followed by tight bearish consolidation,
     * with the most recent bar closing above the consolidation high.
     * Returns +1 for bull flag, -1 for bear flag, 0 for none.
     */
    private int detectFlag(List<CandleBar> bars) {
        int n = bars.size();
        // Flagpole: bars[n-6..n-4] trending strongly
        // Consolidation: bars[n-4..n-2] tight range
        // Breakout: bars[n-1] closes above/below consolidation

        // Compute moves for flagpole (3 bars)
        double poleMove = bars.get(n - 3).getClose() - bars.get(n - 6).getOpen();
        double poleRange = Math.abs(bars.get(n - 6).getOpen()) > 0
                ? Math.abs(poleMove / bars.get(n - 6).getOpen()) : 0;

        if (poleRange < 0.005) return 0; // flagpole needs at least 0.5% move

        // Consolidation range (3 bars after pole)
        double consHigh = bars.subList(n - 4, n - 1).stream().mapToDouble(CandleBar::getHigh).max().orElse(0);
        double consLow  = bars.subList(n - 4, n - 1).stream().mapToDouble(CandleBar::getLow).min().orElse(Double.MAX_VALUE);
        double consRange = consHigh - consLow;
        double consRangePct = consHigh > 0 ? consRange / consHigh : 0;

        // Tight consolidation: range ≤ 40% of flagpole move
        if (consRangePct > 0.02 || consRange > Math.abs(poleMove) * 0.4) return 0;

        CandleBar breakoutBar = bars.get(n - 1);

        if (poleMove > 0 && breakoutBar.getClose() > consHigh) return 1;  // bull flag breakout
        if (poleMove < 0 && breakoutBar.getClose() < consLow)  return -1; // bear flag breakdown
        return 0;
    }

    // ── Combined evaluation ────────────────────────────────────────────────────

    public List<SignalResult> evaluateAll(List<Double> prices, List<Double> volumes,
                                          double currentPrice) {
        return evaluateAll(prices, volumes, currentPrice, null);
    }

    public List<SignalResult> evaluateAll(List<Double> prices, List<Double> volumes,
                                          double currentPrice, CandleHistory candleHistory) {
        List<SignalResult> results = new ArrayList<>();
        results.add(computeRSI(prices));
        results.add(computeBollingerBands(prices, currentPrice));

        if (volumes.size() >= 20) {
            double priceChange = prices.size() >= 2
                    ? prices.get(prices.size() - 1) - prices.get(prices.size() - 2) : 0;
            double currentVol = volumes.get(volumes.size() - 1);
            int histEnd   = Math.max(0, volumes.size() - 1);
            int histStart = Math.max(0, histEnd - 20);
            results.add(computeVolumeSurge(volumes.subList(histStart, histEnd), currentVol, priceChange));
        }

        if (candleHistory != null) {
            // Derive symbol from the prices context — candle data passed separately
            // VWAP and ORB are called per-symbol in TradingLoop; we receive pre-fetched lists here
        }

        return results;
    }

    /**
     * Full day-trading evaluation including candle-based indicators.
     * Called by TradingLoop when CandleHistory is available.
     */
    public List<SignalResult> evaluateAllWithCandles(List<Double> prices, List<Double> volumes,
                                                      double currentPrice,
                                                      List<CandleBar> oneMinBars,
                                                      List<CandleBar> fiveMinBars) {
        List<SignalResult> results = evaluateAll(prices, volumes, currentPrice, null);
        results.add(computeVwap(oneMinBars, currentPrice));
        results.add(computeOrb(fiveMinBars, currentPrice));
        results.add(computeCandlestickPatterns(fiveMinBars));
        return results;
    }

    public int countBuySignals(List<SignalResult> signals) {
        return (int) signals.stream().filter(s -> s.getDirection() == SignalResult.Direction.BUY).count();
    }

    public int countSellSignals(List<SignalResult> signals) {
        return (int) signals.stream().filter(s -> s.getDirection() == SignalResult.Direction.SELL).count();
    }
}
