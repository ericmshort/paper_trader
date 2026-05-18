package com.tradingapp.ui;

public class StockPositionRow {

    private final String symbol;
    private final int quantity;
    private final String avgCost;
    private final String currentPrice;
    private final String marketValue;
    private final String unrealizedPnl;

    public StockPositionRow(String symbol, int quantity, double avgCostRaw, double currentPriceRaw) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.avgCost = String.format("$%.2f", avgCostRaw);
        this.currentPrice = String.format("$%.2f", currentPriceRaw);
        double mv = currentPriceRaw * quantity;
        double pnl = (currentPriceRaw - avgCostRaw) * quantity;
        this.marketValue = String.format("$%,.2f", mv);
        this.unrealizedPnl = pnl >= 0
                ? String.format("+$%.2f", pnl)
                : String.format("-$%.2f", Math.abs(pnl));
    }

    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public String getAvgCost() { return avgCost; }
    public String getCurrentPrice() { return currentPrice; }
    public String getMarketValue() { return marketValue; }
    public String getUnrealizedPnl() { return unrealizedPnl; }
}
