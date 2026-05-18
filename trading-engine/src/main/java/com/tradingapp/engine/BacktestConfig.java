package com.tradingapp.engine;

import java.time.LocalDate;
import java.util.List;

public class BacktestConfig {

    private final List<String> symbols;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final double startingBalance;

    public BacktestConfig(List<String> symbols, LocalDate startDate, LocalDate endDate, double startingBalance) {
        this.symbols = symbols;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startingBalance = startingBalance;
    }

    public List<String> getSymbols() { return symbols; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public double getStartingBalance() { return startingBalance; }
}
