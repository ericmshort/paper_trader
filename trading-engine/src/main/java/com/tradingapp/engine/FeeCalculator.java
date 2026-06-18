package com.tradingapp.engine;

public class FeeCalculator {

    public double calculateFee(int shares) {
        return shares * 0.01;
    }

    public int maxShares(double balance, double pricePerShare) {
        if (pricePerShare <= 0) return 0;
        return (int) ((balance * 0.05) / pricePerShare);
    }

    public int riskBasedShares(double portfolioValue, double entryPrice,
                                double stopLossPct, double maxLossPerTradePct) {
        if (entryPrice <= 0 || stopLossPct <= 0) return 0;
        double dollarRisk = portfolioValue * maxLossPerTradePct;
        double lossPerShare = entryPrice * stopLossPct;
        int shares = (int) (dollarRisk / lossPerShare);
        int maxByAllocation = (int) ((portfolioValue * 0.10) / entryPrice);
        return Math.min(shares, maxByAllocation);
    }
}
