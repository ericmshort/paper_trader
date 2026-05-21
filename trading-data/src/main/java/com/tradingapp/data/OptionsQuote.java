package com.tradingapp.data;

public class OptionsQuote {
    private final double bid;
    private final double ask;
    private final double lastPrice;
    private final long volume;
    private final long openInterest;

    public OptionsQuote(double bid, double ask, double lastPrice) {
        this(bid, ask, lastPrice, 0, 0);
    }

    public OptionsQuote(double bid, double ask, double lastPrice, long volume, long openInterest) {
        this.bid = bid;
        this.ask = ask;
        this.lastPrice = lastPrice;
        this.volume = volume;
        this.openInterest = openInterest;
    }

    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public double getLastPrice() { return lastPrice; }
    public long getVolume() { return volume; }
    public long getOpenInterest() { return openInterest; }

    public boolean isValid() {
        return ask > 0 && bid >= 0;
    }

    /** Returns true if the contract has enough liquidity to trade safely. */
    public boolean isLiquid() {
        if (!isValid()) return false;
        if (openInterest > 0 && openInterest < 100) return false;
        if (volume > 0 && volume < 10) return false;
        double mid = (bid + ask) / 2.0;
        if (mid > 0 && (ask - bid) / mid > 0.30) return false;
        return true;
    }

    public String liquidityInfo() {
        double mid = (bid + ask) / 2.0;
        double spreadPct = mid > 0 ? (ask - bid) / mid * 100.0 : 0.0;
        return String.format("OI=%d vol=%d spread=%.1f%%", openInterest, volume, spreadPct);
    }
}
