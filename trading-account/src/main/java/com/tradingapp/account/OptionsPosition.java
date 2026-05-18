package com.tradingapp.account;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class OptionsPosition {

    private final String symbol;
    private final String type;
    private final double strike;
    private final LocalDate expiry;
    private final int contracts;
    private final double premiumPaid;

    public OptionsPosition(String symbol, String type, double strike, LocalDate expiry, int contracts, double premiumPaid) {
        this.symbol = symbol;
        this.type = type;
        this.strike = strike;
        this.expiry = expiry;
        this.contracts = contracts;
        this.premiumPaid = premiumPaid;
    }

    public String getSymbol() { return symbol; }
    public String getType() { return type; }
    public double getStrike() { return strike; }
    public LocalDate getExpiry() { return expiry; }
    public int getContracts() { return contracts; }
    public double getPremiumPaid() { return premiumPaid; }

    public long daysToExpiry() {
        return ChronoUnit.DAYS.between(LocalDate.now(), expiry);
    }

    public double getCurrentValue(double currentPremium) {
        return currentPremium * 100 * contracts;
    }
}
