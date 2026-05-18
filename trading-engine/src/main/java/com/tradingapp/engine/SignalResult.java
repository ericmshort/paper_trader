package com.tradingapp.engine;

public class SignalResult {

    public enum Direction { BUY, SELL, NEUTRAL }

    private final String indicatorName;
    private final Direction direction;
    private final double value;

    public SignalResult(String indicatorName, Direction direction, double value) {
        this.indicatorName = indicatorName;
        this.direction = direction;
        this.value = value;
    }

    public static SignalResult buy(String name, double value) {
        return new SignalResult(name, Direction.BUY, value);
    }

    public static SignalResult sell(String name, double value) {
        return new SignalResult(name, Direction.SELL, value);
    }

    public static SignalResult neutral(String name, double value) {
        return new SignalResult(name, Direction.NEUTRAL, value);
    }

    public String getIndicatorName() { return indicatorName; }
    public Direction getDirection() { return direction; }
    public double getValue() { return value; }

    @Override
    public String toString() {
        return indicatorName + "=" + String.format("%.4f", value) + " [" + direction + "]";
    }
}
