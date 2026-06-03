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
    // Set during broker sync to the exact OCC symbol held in the account.
    // Used for close orders to skip re-lookup and avoid position_intent mismatch.
    private volatile String brokerOccSymbol;

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
    public String getBrokerOccSymbol() { return brokerOccSymbol; }
    public void setBrokerOccSymbol(String brokerOccSymbol) { this.brokerOccSymbol = brokerOccSymbol; }

    public long daysToExpiry() {
        return ChronoUnit.DAYS.between(LocalDate.now(), expiry);
    }

    public double getCurrentValue(double currentPremium) {
        return currentPremium * 100 * contracts;
    }
}
