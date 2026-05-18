package com.tradingapp.data;

public class QuoteModel {
    private final String symbol;
    private final double price;
    private final double bid;
    private final double ask;
    private final long volume;
    private final long timestamp;
    private final boolean stale;

    private static final long STALE_THRESHOLD_MS = 5 * 60 * 1000L;

    public QuoteModel(String symbol, double price, double bid, double ask,
                      long volume, long timestamp, boolean stale) {
        this.symbol = symbol;
        this.price = price;
        this.bid = bid;
        this.ask = ask;
        this.volume = volume;
        this.timestamp = timestamp;
        this.stale = stale;
    }

    public static QuoteModel fromLive(String symbol, double price, long volume, long fetchedAt) {
        double spread = price * 0.0005;
        double bid = price - spread;
        double ask = price + spread;
        boolean stale = (System.currentTimeMillis() - fetchedAt) > STALE_THRESHOLD_MS;
        return new QuoteModel(symbol, price, bid, ask, volume, fetchedAt, stale);
    }

    public static QuoteModel withTimestamp(String symbol, double price, double bid,
                                           double ask, long volume, long timestamp) {
        boolean stale = (System.currentTimeMillis() - timestamp) > STALE_THRESHOLD_MS;
        return new QuoteModel(symbol, price, bid, ask, volume, timestamp, stale);
    }

    public String getSymbol() { return symbol; }
    public double getPrice() { return price; }
    public double getBid() { return bid; }
    public double getAsk() { return ask; }
    public long getVolume() { return volume; }
    public long getTimestamp() { return timestamp; }
    public boolean isStale() { return stale; }

    @Override
    public String toString() {
        return String.format("QuoteModel{symbol='%s', price=%.2f, bid=%.2f, ask=%.2f, volume=%d, stale=%b}",
                symbol, price, bid, ask, volume, stale);
    }
}
