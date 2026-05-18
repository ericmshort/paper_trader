package com.tradingapp.options;

public class GreeksResult {

    public final double delta;
    public final double gamma;
    public final double theta;
    public final double vega;

    public GreeksResult(double delta, double gamma, double theta, double vega) {
        this.delta = delta;
        this.gamma = gamma;
        this.theta = theta;
        this.vega = vega;
    }

    @Override
    public String toString() {
        return String.format("delta=%.4f gamma=%.4f theta=%.4f vega=%.4f", delta, gamma, theta, vega);
    }
}
