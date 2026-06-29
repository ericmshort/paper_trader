package com.tradingapp.ui;

public class PremiumSellerRow {

    private final String symbol;
    private final String strategy;
    private final String shortStrike;
    private final String expiry;
    private final String dte;
    private final String premiumCollected;
    private final String maxProfit;
    private final String currentPnl;
    private final String pctCaptured;
    private final double pnlRaw;
    private final double maxProfitRaw;
    private final String lowStrike;
    private final String highStrike;
    private final String currentPrice;

    public PremiumSellerRow(String symbol, String strategy, String shortStrike, String expiry,
                            long dteValue, double maxProfitRaw, double pnlRaw,
                            String lowStrike, String highStrike, String currentPrice) {
        this.symbol       = symbol;
        this.strategy     = strategy;
        this.shortStrike  = shortStrike;
        this.expiry       = expiry;
        this.dte          = dteValue + "d";
        this.pnlRaw       = pnlRaw;
        this.maxProfitRaw = maxProfitRaw;
        this.lowStrike    = lowStrike;
        this.highStrike   = highStrike;
        this.currentPrice = currentPrice;

        this.premiumCollected = maxProfitRaw >= 0
                ? String.format("+$%.0f", maxProfitRaw)
                : String.format("-$%.0f", Math.abs(maxProfitRaw));
        this.maxProfit = String.format("$%.0f", maxProfitRaw);

        this.currentPnl = pnlRaw >= 0
                ? String.format("+$%.0f", pnlRaw)
                : String.format("-$%.0f", Math.abs(pnlRaw));

        double pct = maxProfitRaw > 0 ? pnlRaw / maxProfitRaw * 100.0 : 0.0;
        this.pctCaptured = String.format("%.0f%%", Math.max(-999, Math.min(999, pct)));
    }

    public String getSymbol()            { return symbol; }
    public String getStrategy()          { return strategy; }
    public String getShortStrike()       { return shortStrike; }
    public String getExpiry()            { return expiry; }
    public String getDte()               { return dte; }
    public String getPremiumCollected()  { return premiumCollected; }
    public String getMaxProfit()         { return maxProfit; }
    public String getCurrentPnl()        { return currentPnl; }
    public String getPctCaptured()       { return pctCaptured; }
    public double getPnlRaw()            { return pnlRaw; }
    public double getMaxProfitRaw()      { return maxProfitRaw; }
    public String getLowStrike()         { return lowStrike; }
    public String getHighStrike()        { return highStrike; }
    public String getCurrentPrice()      { return currentPrice; }
}
