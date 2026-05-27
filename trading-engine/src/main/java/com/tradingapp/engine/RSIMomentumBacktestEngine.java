package com.tradingapp.engine;

import com.tradingapp.data.HistoricalBar;

import java.time.LocalDate;
import java.util.*;

public class RSIMomentumBacktestEngine {

    private final IndicatorEngine indicatorEngine;
    private final FeeCalculator feeCalc;

    public RSIMomentumBacktestEngine(IndicatorEngine indicatorEngine, FeeCalculator feeCalc) {
        this.indicatorEngine = indicatorEngine;
        this.feeCalc = feeCalc;
    }

    public BacktestResult run(BacktestConfig config, Map<String, List<HistoricalBar>> barsBySymbol) {
        if (barsBySymbol.isEmpty()) {
            return new BacktestResult(Collections.emptyList(), 0.0, 0.0, 0, 0, 0);
        }

        Map<String, Map<LocalDate, HistoricalBar>> index = new HashMap<>();
        Set<LocalDate> allDates = new TreeSet<>();
        for (Map.Entry<String, List<HistoricalBar>> entry : barsBySymbol.entrySet()) {
            Map<LocalDate, HistoricalBar> dateMap = new HashMap<>();
            for (HistoricalBar bar : entry.getValue()) {
                dateMap.put(bar.getDate(), bar);
                allDates.add(bar.getDate());
            }
            index.put(entry.getKey(), dateMap);
        }

        List<LocalDate> tradingDays = new ArrayList<>(allDates);
        double cash = config.getStartingBalance();
        // [shares, entryPrice, peakPrice]
        Map<String, double[]> openPositions = new HashMap<>();
        Map<String, List<Double>> priceHistory = new HashMap<>();
        Map<String, Double> lastClose = new HashMap<>();

        int wins = 0, losses = 0, totalTrades = 0;
        List<BacktestDataPoint> equityCurve = new ArrayList<>();

        for (LocalDate date : tradingDays) {
            for (String symbol : config.getSymbols()) {
                Map<LocalDate, HistoricalBar> symbolIndex = index.get(symbol);
                if (symbolIndex == null) continue;
                HistoricalBar bar = symbolIndex.get(date);
                if (bar == null) continue;

                double close = bar.getClose();
                lastClose.put(symbol, close);
                priceHistory.computeIfAbsent(symbol, k -> new ArrayList<>()).add(close);

                List<Double> prices = priceHistory.get(symbol);
                if (prices.size() < 15) continue;

                SignalResult.Direction direction = indicatorEngine.computeRSI(prices).getDirection();

                if (openPositions.containsKey(symbol)) {
                    double[] pos = openPositions.get(symbol);
                    double entryPrice = pos[1];
                    if (close > pos[2]) pos[2] = close;
                    boolean stopHit = close <= pos[2] * (1.0 - RSIMomentumStrategy.TRAILING_STOP_PCT);
                    if (direction == SignalResult.Direction.SELL || stopHit) {
                        int shares = (int) pos[0];
                        cash += shares * close - feeCalc.calculateFee(shares);
                        totalTrades++;
                        if (close > entryPrice) wins++; else losses++;
                        openPositions.remove(symbol);
                    }
                } else if (direction == SignalResult.Direction.BUY) {
                    int shares = feeCalc.maxShares(cash, close);
                    if (shares > 0) {
                        cash -= shares * close + feeCalc.calculateFee(shares);
                        openPositions.put(symbol, new double[]{shares, close, close});
                    }
                }
            }

            double holdingsValue = 0.0;
            for (Map.Entry<String, double[]> e : openPositions.entrySet()) {
                holdingsValue += e.getValue()[0] * lastClose.getOrDefault(e.getKey(), e.getValue()[1]);
            }
            equityCurve.add(new BacktestDataPoint(date, cash + holdingsValue));
        }

        for (Map.Entry<String, double[]> e : openPositions.entrySet()) {
            int shares = (int) e.getValue()[0];
            cash += shares * lastClose.getOrDefault(e.getKey(), e.getValue()[1]) - feeCalc.calculateFee(shares);
        }

        double finalValue = equityCurve.isEmpty()
                ? config.getStartingBalance()
                : equityCurve.get(equityCurve.size() - 1).getPortfolioValue();
        double totalReturnPct = (finalValue - config.getStartingBalance()) / config.getStartingBalance() * 100.0;

        double maxDrawdownPct = 0.0;
        double peak = config.getStartingBalance();
        for (BacktestDataPoint pt : equityCurve) {
            if (pt.getPortfolioValue() > peak) peak = pt.getPortfolioValue();
            if (peak > 0) {
                double drawdown = (peak - pt.getPortfolioValue()) / peak * 100.0;
                if (drawdown > maxDrawdownPct) maxDrawdownPct = drawdown;
            }
        }

        return new BacktestResult(equityCurve, totalReturnPct, maxDrawdownPct, wins, losses, totalTrades);
    }
}
