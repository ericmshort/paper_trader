package com.tradingapp.engine;

public class FeeCalculator {

    public double calculateFee(int shares) {
        return shares * 0.01;
    }

    public int maxShares(double balance, double pricePerShare) {
        if (pricePerShare <= 0) return 0;
        return (int) ((balance * 0.05) / pricePerShare);
    }
}
