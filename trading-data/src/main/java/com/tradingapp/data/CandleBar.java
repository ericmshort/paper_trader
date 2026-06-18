package com.tradingapp.data;

import java.time.Instant;

/** An OHLCV bar for a fixed intraday interval (e.g. 1-min or 5-min). */
public class CandleBar {

    public enum Interval { ONE_MIN, FIVE_MIN }

    private final String symbol;
    private final Instant periodStart; // start of the bar's time window
    private final Interval interval;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private double cumulativePriceVolume; // Σ(price × size) for VWAP

    public CandleBar(String symbol, Instant periodStart, Interval interval, double openPrice, double openVolume) {
        this.symbol = symbol;
        this.periodStart = periodStart;
        this.interval = interval;
        this.open  = openPrice;
        this.high  = openPrice;
        this.low   = openPrice;
        this.close = openPrice;
        this.volume = openVolume;
        this.cumulativePriceVolume = openPrice * openVolume;
    }

    /** Update the bar with a new trade tick. */
    public synchronized void update(double price, double size) {
        if (price > high) high = price;
        if (price < low)  low  = price;
        close = price;
        volume += size;
        cumulativePriceVolume += price * size;
    }

    /** VWAP for this bar's period. */
    public double getVwap() {
        return volume > 0 ? cumulativePriceVolume / volume : close;
    }

    public double getBodySize()   { return Math.abs(close - open); }
    public double getUpperShadow(){ return high - Math.max(open, close); }
    public double getLowerShadow(){ return Math.min(open, close) - low; }
    public double getRange()      { return high - low; }
    public boolean isBullish()    { return close >= open; }
    public boolean isBearish()    { return close < open; }

    public String  getSymbol()      { return symbol; }
    public Instant getPeriodStart() { return periodStart; }
    public Interval getInterval()   { return interval; }
    public double  getOpen()        { return open; }
    public double  getHigh()        { return high; }
    public double  getLow()         { return low; }
    public double  getClose()       { return close; }
    public double  getVolume()      { return volume; }
    public double  getCumulativePriceVolume() { return cumulativePriceVolume; }
}
