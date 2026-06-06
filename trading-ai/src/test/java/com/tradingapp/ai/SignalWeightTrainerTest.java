package com.tradingapp.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalWeightTrainerTest {

    @Test
    void returnsDefaultWeightsWhenFewerThanMinTrades() {
        List<LabeledTrade> trades = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            trades.add(new LabeledTrade(new FeatureVector(50, 100, 1.5, 0.0, 0.0, 0.0), true));
        }
        SignalWeights weights = new SignalWeightTrainer().train(trades);
        for (int i = 0; i < SignalWeights.NUM_FEATURES; i++) {
            assertEquals(1.0, weights.getWeight(i), 0.001);
        }
    }

    @Test
    void weightsSumToSixOnSufficientData() {
        List<LabeledTrade> trades = buildBiasedTrades(20);
        SignalWeights weights = new SignalWeightTrainer().train(trades);

        double sum = 0;
        for (int i = 0; i < SignalWeights.NUM_FEATURES; i++) {
            sum += weights.getWeight(i);
            assertTrue(weights.getWeight(i) > 0, "Weight " + i + " should be positive");
        }
        assertEquals(6.0, sum, 0.01);
    }

    @Test
    void allWeightsPositive() {
        List<LabeledTrade> trades = buildBiasedTrades(15);
        SignalWeights weights = new SignalWeightTrainer().train(trades);
        for (int i = 0; i < SignalWeights.NUM_FEATURES; i++) {
            assertTrue(weights.getWeight(i) > 0, "Weight at index " + i + " must be positive");
        }
    }

    private List<LabeledTrade> buildBiasedTrades(int count) {
        List<LabeledTrade> trades = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean win = (i % 2 == 0);
            // RSI low (oversold) for wins, high (overbought) for losses — creates info gain
            double rsi = win ? 25.0 + i * 0.1 : 75.0 + i * 0.1;
            trades.add(new LabeledTrade(new FeatureVector(rsi, 100, 1.5, 0.0, 0.0, 0.0), win));
        }
        return trades;
    }
}
