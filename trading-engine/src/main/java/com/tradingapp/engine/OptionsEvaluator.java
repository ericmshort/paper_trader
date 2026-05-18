package com.tradingapp.engine;

@FunctionalInterface
public interface OptionsEvaluator {
    void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr);
}
