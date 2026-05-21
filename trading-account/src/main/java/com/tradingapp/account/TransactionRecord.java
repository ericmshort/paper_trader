package com.tradingapp.account;

public class TransactionRecord {

    public enum TransactionAction { BUY, SELL, CALL_BUY, CALL_SELL, PUT_BUY, PUT_SELL }

    private long id;
    private long timestamp;
    private String symbol;
    private TransactionAction action;
    private int quantity;
    private double pricePerUnit;
    private double feeCharged;
    private double balanceAfter;
    private String reason;
    private String signals;
    private String features;
    private String externalId;

    public TransactionRecord() {}

    public TransactionRecord(String symbol, TransactionAction action, int quantity,
                             double pricePerUnit, double feeCharged, double balanceAfter,
                             String reason, String signals) {
        this.timestamp = System.currentTimeMillis();
        this.symbol = symbol;
        this.action = action;
        this.quantity = quantity;
        this.pricePerUnit = pricePerUnit;
        this.feeCharged = feeCharged;
        this.balanceAfter = balanceAfter;
        this.reason = reason;
        this.signals = signals;
    }

    public long getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public String getSymbol() { return symbol; }
    public TransactionAction getAction() { return action; }
    public int getQuantity() { return quantity; }
    public double getPricePerUnit() { return pricePerUnit; }
    public double getFeeCharged() { return feeCharged; }
    public double getBalanceAfter() { return balanceAfter; }
    public String getReason() { return reason; }
    public String getSignals() { return signals; }
    public String getFeatures() { return features; }

    public void setId(long id) { this.id = id; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setAction(TransactionAction action) { this.action = action; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void setPricePerUnit(double pricePerUnit) { this.pricePerUnit = pricePerUnit; }
    public void setFeeCharged(double feeCharged) { this.feeCharged = feeCharged; }
    public void setBalanceAfter(double balanceAfter) { this.balanceAfter = balanceAfter; }
    public void setReason(String reason) { this.reason = reason; }
    public void setSignals(String signals) { this.signals = signals; }
    public void setFeatures(String features) { this.features = features; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
}
