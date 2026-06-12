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
    // Set during broker sync from Alpaca's current_price field. 0.0 = not yet set.
    private volatile double currentMarketPrice;

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
    public double getCurrentMarketPrice() { return currentMarketPrice; }
    public void setCurrentMarketPrice(double price) { this.currentMarketPrice = price; }

    public long daysToExpiry() {
        return ChronoUnit.DAYS.between(LocalDate.now(), expiry);
    }

    public long daysToExpiry(LocalDate referenceDate) {
        return ChronoUnit.DAYS.between(referenceDate, expiry);
    }

    public double getCurrentValue(double currentPremium) {
        return currentPremium * 100 * contracts;
    }
}
