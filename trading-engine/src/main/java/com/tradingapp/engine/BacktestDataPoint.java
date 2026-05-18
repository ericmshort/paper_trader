package com.tradingapp.engine;

import java.time.LocalDate;

public class BacktestDataPoint {

    private final LocalDate date;
    private final double portfolioValue;

    public BacktestDataPoint(LocalDate date, double portfolioValue) {
        this.date = date;
        this.portfolioValue = portfolioValue;
    }

    public LocalDate getDate() { return date; }
    public double getPortfolioValue() { return portfolioValue; }
}
