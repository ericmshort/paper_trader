package com.tradingapp.ai;

public class FeatureVector {
    private final double rsi;
    private final double macd;
    private final double bollinger;
    private final double maCrossover;
    private final double volRatio;

    public FeatureVector(double rsi, double macd, double bollinger, double maCrossover, double volRatio) {
        this.rsi = rsi;
        this.macd = macd;
        this.bollinger = bollinger;
        this.maCrossover = maCrossover;
        this.volRatio = volRatio;
    }

    public double getRsi() { return rsi; }
    public double getMacd() { return macd; }
    public double getBollinger() { return bollinger; }
    public double getMaCrossover() { return maCrossover; }
    public double getVolRatio() { return volRatio; }

    public double get(int index) {
        return switch (index) {
            case 0 -> rsi;
            case 1 -> macd;
            case 2 -> bollinger;
            case 3 -> maCrossover;
            case 4 -> volRatio;
            default -> throw new IllegalArgumentException("Feature index out of range: " + index);
        };
    }

    public static FeatureVector fromCsv(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Expected 5 feature values, got " + parts.length + ": " + csv);
        }
        return new FeatureVector(
            Double.parseDouble(parts[0].trim()),
            Double.parseDouble(parts[1].trim()),
            Double.parseDouble(parts[2].trim()),
            Double.parseDouble(parts[3].trim()),
            Double.parseDouble(parts[4].trim())
        );
    }
}
