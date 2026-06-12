package com.tradingapp.ai;

public class FeatureVector {
    private final double rsi;
    private final double bollinger;
    private final double volRatio;
    private final double vwap;
    private final double orb;
    private final double candlestick;
    private final double macd;
    private final double stochastic;
    private final double relativeStrength;

    public FeatureVector(double rsi, double bollinger, double volRatio, double vwap, double orb,
                         double candlestick, double macd, double stochastic, double relativeStrength) {
        this.rsi = rsi;
        this.bollinger = bollinger;
        this.volRatio = volRatio;
        this.vwap = vwap;
        this.orb = orb;
        this.candlestick = candlestick;
        this.macd = macd;
        this.stochastic = stochastic;
        this.relativeStrength = relativeStrength;
    }

    public double getRsi()             { return rsi; }
    public double getBollinger()       { return bollinger; }
    public double getVolRatio()        { return volRatio; }
    public double getVwap()            { return vwap; }
    public double getOrb()             { return orb; }
    public double getCandlestick()     { return candlestick; }
    public double getMacd()            { return macd; }
    public double getStochastic()      { return stochastic; }
    public double getRelativeStrength(){ return relativeStrength; }

    public double get(int index) {
        return switch (index) {
            case 0 -> rsi;
            case 1 -> bollinger;
            case 2 -> volRatio;
            case 3 -> vwap;
            case 4 -> orb;
            case 5 -> candlestick;
            case 6 -> macd;
            case 7 -> stochastic;
            case 8 -> relativeStrength;
            default -> throw new IllegalArgumentException("Feature index out of range: " + index);
        };
    }

    public static FeatureVector fromCsv(String csv) {
        String[] parts = csv.split(",");
        // Accept both 6-feature (legacy) and 9-feature (current) vectors.
        // Legacy trades missing MACD/STOCHASTIC/RS default those features to 0.
        if (parts.length != 6 && parts.length != 9) {
            throw new IllegalArgumentException(
                    "Expected 6 or 9 feature values, got " + parts.length + ": " + csv);
        }
        double macd       = parts.length > 6 ? Double.parseDouble(parts[6].trim()) : 0.0;
        double stoch      = parts.length > 7 ? Double.parseDouble(parts[7].trim()) : 0.0;
        double rs         = parts.length > 8 ? Double.parseDouble(parts[8].trim()) : 0.0;
        return new FeatureVector(
            Double.parseDouble(parts[0].trim()),
            Double.parseDouble(parts[1].trim()),
            Double.parseDouble(parts[2].trim()),
            Double.parseDouble(parts[3].trim()),
            Double.parseDouble(parts[4].trim()),
            Double.parseDouble(parts[5].trim()),
            macd, stoch, rs
        );
    }
}
