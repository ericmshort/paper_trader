package com.tradingapp.data;

public class OptionsQuote {
    private final double bid;
    private final double ask;
    private final double lastPrice;

    public OptionsQuote(double bid, double ask, double lastPrice) {
        this.bid = bid;
        this.ask = ask;
        this.lastPrice = lastPrice;
    }

    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public double getLastPrice() { return lastPrice; }

    public boolean isValid() {
        return ask > 0 && bid >= 0;
    }
}
