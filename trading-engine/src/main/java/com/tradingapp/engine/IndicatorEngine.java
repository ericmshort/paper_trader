package com.tradingapp.engine;

import java.util.List;

public class IndicatorEngine {

    public SignalResult computeRSI(List<Double> prices) {
        int period = 14;
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
        if (avgLoss == 0) {
            return SignalResult.sell("RSI", 100);
        }
        double rs = avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));
        if (rsi < 30) return SignalResult.buy("RSI", rsi);
        if (rsi > 70) return SignalResult.sell("RSI", rsi);
        return SignalResult.neutral("RSI", rsi);
    }

    public SignalResult computeBollingerBands(List<Double> prices, double currentPrice) {
        int period = 20;
        if (prices.size() < period) {
            return SignalResult.neutral("BollingerBands", 0);
        }
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

    public SignalResult computeMACD(List<Double> prices) {
        if (prices.size() < 27) {
            return SignalResult.neutral("MACD", 0);
        }
        double k12 = 2.0 / (12 + 1);
        double k26 = 2.0 / (26 + 1);
        double k9 = 2.0 / (9 + 1);
        // Collect per-bar MACD values starting from the first bar where EMA26 is seeded
        double ema12 = sma(prices, 0, 12);
        double ema26 = sma(prices, 0, 26);
        java.util.List<Double> macdValues = new java.util.ArrayList<>();
        for (int i = 12; i < prices.size(); i++) {
            ema12 = prices.get(i) * k12 + ema12 * (1 - k12);
            if (i >= 26) {
                ema26 = prices.get(i) * k26 + ema26 * (1 - k26);
                macdValues.add(ema12 - ema26);
            }
        }
        double macdLine = macdValues.isEmpty() ? 0 : macdValues.get(macdValues.size() - 1);
        // Signal line: EMA9 of MACD values; use SMA seed over all available bars if fewer than 9
        int sigLen = macdValues.size();
        double signalLine = macdValues.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (sigLen >= 9) {
            signalLine = smaList(macdValues, 0, 9);
            for (int i = 9; i < macdValues.size(); i++) {
                signalLine = macdValues.get(i) * k9 + signalLine * (1 - k9);
            }
        }
        if (macdLine > signalLine) return SignalResult.buy("MACD", macdLine);
        if (macdLine < signalLine) return SignalResult.sell("MACD", macdLine);
        return SignalResult.neutral("MACD", macdLine);
    }

    public SignalResult computeMACrossover(List<Double> prices) {
        if (prices.size() < 200) {
            return SignalResult.neutral("MACrossover", 0);
        }
        double longMA = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double shortMA = prices.subList(prices.size() - 50, prices.size())
                               .stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double diff = shortMA - longMA;
        if (shortMA > longMA) return SignalResult.buy("MACrossover", diff);
        if (shortMA < longMA) return SignalResult.sell("MACrossover", diff);
        return SignalResult.neutral("MACrossover", diff);
    }

    public SignalResult computeVolumeSurge(List<Double> volumes, double currentVolume, double priceChangePct) {
        if (volumes.size() < 20) {
            return SignalResult.neutral("VolumeSurge", 0);
        }
        double avgVolume = volumes.subList(0, 20).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double ratio = currentVolume / avgVolume;
        if (currentVolume < avgVolume * 2) {
            return SignalResult.neutral("VolumeSurge", ratio);
        }
        if (priceChangePct > 0) return SignalResult.buy("VolumeSurge", ratio);
        if (priceChangePct < 0) return SignalResult.sell("VolumeSurge", ratio);
        return SignalResult.neutral("VolumeSurge", ratio);
    }

    private double sma(List<Double> prices, int start, int count) {
        double sum = 0;
        for (int i = start; i < start + count; i++) sum += prices.get(i);
        return sum / count;
    }

    private double smaList(List<Double> values, int start, int count) {
        double sum = 0;
        for (int i = start; i < start + count; i++) sum += values.get(i);
        return sum / count;
    }


    public List<SignalResult> evaluateAll(List<Double> prices, List<Double> volumes, double currentPrice) {
        java.util.List<SignalResult> results = new java.util.ArrayList<>();
        results.add(computeRSI(prices));
        results.add(computeMACD(prices));
        results.add(computeBollingerBands(prices, currentPrice));
        if (prices.size() >= 200) {
            results.add(computeMACrossover(prices));
        }
        if (volumes.size() >= 20) {
            double priceChange = prices.size() >= 2
                ? prices.get(prices.size() - 1) - prices.get(prices.size() - 2) : 0;
            double currentVol = volumes.get(volumes.size() - 1);
            // Baseline is the 20 bars preceding current tick (exclude current from its own average)
            int histEnd = Math.max(0, volumes.size() - 1);
            int histStart = Math.max(0, histEnd - 20);
            results.add(computeVolumeSurge(volumes.subList(histStart, histEnd), currentVol, priceChange));
        }
        return results;
    }

    public int countBuySignals(List<SignalResult> signals) {
        return (int) signals.stream().filter(s -> s.getDirection() == SignalResult.Direction.BUY).count();
    }

    public int countSellSignals(List<SignalResult> signals) {
        return (int) signals.stream().filter(s -> s.getDirection() == SignalResult.Direction.SELL).count();
    }
}
