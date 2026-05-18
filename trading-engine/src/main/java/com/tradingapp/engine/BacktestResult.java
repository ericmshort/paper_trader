package com.tradingapp.engine;

import java.util.List;

public class BacktestResult {

    private final List<BacktestDataPoint> equityCurve;
    private final double totalReturnPct;
    private final double maxDrawdownPct;
    private final int wins;
    private final int losses;
    private final int totalTrades;

    public BacktestResult(List<BacktestDataPoint> equityCurve, double totalReturnPct,
                          double maxDrawdownPct, int wins, int losses, int totalTrades) {
        this.equityCurve = equityCurve;
        this.totalReturnPct = totalReturnPct;
        this.maxDrawdownPct = maxDrawdownPct;
        this.wins = wins;
        this.losses = losses;
        this.totalTrades = totalTrades;
    }

    public List<BacktestDataPoint> getEquityCurve() { return equityCurve; }
    public double getTotalReturnPct() { return totalReturnPct; }
    public double getMaxDrawdownPct() { return maxDrawdownPct; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getTotalTrades() { return totalTrades; }

    public double winRate() {
        return totalTrades > 0 ? wins * 100.0 / totalTrades : 0.0;
    }
}
