package com.tradingapp.engine;

import java.util.List;

public interface SignalWeightEvaluator {
    double weightedBuyScore(List<SignalResult> signals);
    double weightedSellScore(List<SignalResult> signals);
}
