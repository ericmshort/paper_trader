package com.tradingapp.account;

public class Position {

    public enum PositionType { STOCK, OPTION }

    private final String symbol;
    private int quantity;
    private double averageCost;
    private double currentPrice;
    private final PositionType type;
    private volatile boolean brokerVerified = false;

    public Position(String symbol, int quantity, double averageCost, PositionType type) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.averageCost = averageCost;
        this.currentPrice = averageCost;
        this.type = type;
    }

    public void addShares(int additionalQuantity, double pricePaid) {
        double totalCost = (averageCost * quantity) + (pricePaid * additionalQuantity);
        quantity += additionalQuantity;
        averageCost = quantity > 0 ? totalCost / quantity : 0;
    }

    public void reduceShares(int quantityToRemove) {
        quantity = Math.max(0, quantity - quantityToRemove);
    }

    public double getMarketValue() {
        return currentPrice * quantity;
    }

    public double getUnrealizedPnL() {
        return (currentPrice - averageCost) * quantity;
    }

    public String getSymbol() { return symbol; }
    public int getQuantity() { return quantity; }
    public double getAverageCost() { return averageCost; }
    public double getCurrentPrice() { return currentPrice; }
    public PositionType getType() { return type; }

    public void setCurrentPrice(double currentPrice) { this.currentPrice = currentPrice; }
    public boolean isBrokerVerified() { return brokerVerified; }
    public void setBrokerVerified(boolean brokerVerified) { this.brokerVerified = brokerVerified; }
}
