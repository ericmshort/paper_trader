package com.tradingapp.ui;

public class OptionsPositionRow {

    private final String symbol;
    private final String type;
    private final String strike;
    private final String expiry;
    private final int contracts;
    private final String entryPremium;
    private final String currentPremium;
    private final String unrealizedPnl;

    public OptionsPositionRow(String symbol, String type, double strikeRaw, String expiryRaw,
                              int contracts, double entryPremiumRaw, double currentPremiumRaw) {
        this.symbol = symbol;
        this.type = type;
        this.strike = String.format("$%.2f", strikeRaw);
        this.expiry = expiryRaw;
        this.contracts = contracts;
        this.entryPremium = String.format("$%.4f", entryPremiumRaw);
        this.currentPremium = String.format("$%.4f", currentPremiumRaw);
        double pnl = (currentPremiumRaw - entryPremiumRaw) * 100 * contracts;
        this.unrealizedPnl = pnl >= 0
                ? String.format("+$%.2f", pnl)
                : String.format("-$%.2f", Math.abs(pnl));
    }

    public String getSymbol() { return symbol; }
    public String getType() { return type; }
    public String getStrike() { return strike; }
    public String getExpiry() { return expiry; }
    public int getContracts() { return contracts; }
    public String getEntryPremium() { return entryPremium; }
    public String getCurrentPremium() { return currentPremium; }
    public String getUnrealizedPnl() { return unrealizedPnl; }
}
