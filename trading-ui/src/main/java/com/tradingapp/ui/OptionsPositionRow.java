package com.tradingapp.ui;

public class OptionsPositionRow {

    private final String symbol;
    private final String type;
    private final String strike;
    private final String expiry;
    private final int contracts;
    private final String cost;
    private final String currentValue;
    private final String unrealizedPnl;
    private final double pnlRaw;

    /**
     * @param costRaw          -premiumPaid * 100 * contracts (negative = paid, positive = collected)
     * @param currentValueRaw  currentPremium * 100 * contracts (positive = asset, negative = liability)
     */
    public OptionsPositionRow(String symbol, String type, String strike, String expiry,
                              int contracts, double costRaw, double currentValueRaw) {
        this.symbol = symbol;
        this.type = type;
        this.strike = strike;
        this.expiry = expiry;
        this.contracts = contracts;

        this.cost = costRaw >= 0
                ? String.format("+$%.2f", costRaw)
                : String.format("-$%.2f", Math.abs(costRaw));

        this.currentValue = currentValueRaw >= 0
                ? String.format("+$%.2f", currentValueRaw)
                : String.format("-$%.2f", Math.abs(currentValueRaw));

        this.pnlRaw = costRaw + currentValueRaw;
        this.unrealizedPnl = pnlRaw >= 0
                ? String.format("+$%.2f", pnlRaw)
                : String.format("-$%.2f", Math.abs(pnlRaw));
    }

    public String getSymbol() { return symbol; }
    public String getType() { return type; }
    public String getStrike() { return strike; }
    public String getExpiry() { return expiry; }
    public int getContracts() { return contracts; }
    public String getCost() { return cost; }
    public String getCurrentValue() { return currentValue; }
    public String getUnrealizedPnl() { return unrealizedPnl; }
    public double getPnlRaw() { return pnlRaw; }
}
