package com.tradingapp.ai;

public class FeatureVector {
    private final double rsi;
    private final double bollinger;
    private final double volRatio;
    private final double vwap;
    private final double orb;
    private final double candlestick;

    public FeatureVector(double rsi, double bollinger, double volRatio, double vwap, double orb, double candlestick) {
        this.rsi = rsi;
        this.bollinger = bollinger;
        this.volRatio = volRatio;
        this.vwap = vwap;
        this.orb = orb;
        this.candlestick = candlestick;
    }

    public double getRsi()         { return rsi; }
    public double getBollinger()   { return bollinger; }
    public double getVolRatio()    { return volRatio; }
    public double getVwap()        { return vwap; }
    public double getOrb()         { return orb; }
    public double getCandlestick() { return candlestick; }

    public double get(int index) {
        return switch (index) {
            case 0 -> rsi;
            case 1 -> bollinger;
            case 2 -> volRatio;
            case 3 -> vwap;
            case 4 -> orb;
            case 5 -> candlestick;
            default -> throw new IllegalArgumentException("Feature index out of range: " + index);
        };
    }

    public static FeatureVector fromCsv(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Expected 6 feature values, got " + parts.length + ": " + csv);
        }
        return new FeatureVector(
            Double.parseDouble(parts[0].trim()),
            Double.parseDouble(parts[1].trim()),
            Double.parseDouble(parts[2].trim()),
            Double.parseDouble(parts[3].trim()),
            Double.parseDouble(parts[4].trim()),
            Double.parseDouble(parts[5].trim())
        );
    }
}
