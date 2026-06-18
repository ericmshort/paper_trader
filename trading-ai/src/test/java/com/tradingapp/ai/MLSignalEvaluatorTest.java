package com.tradingapp.ai;

import com.tradingapp.engine.SignalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MLSignalEvaluatorTest {

    @Test
    void weightedBuyScoreSumsWeightsOfBuySignals() {
        // RSI=2.0, Bollinger=1.0, VolumeSurge=1.0, VWAP=0.5, ORB=0.5, Candlestick=0.5
        SignalWeights weights = new SignalWeights(new double[]{2.0, 1.0, 1.0, 0.5, 0.5, 0.5, 1.0, 1.0, 1.0});
        MLSignalEvaluator eval = new MLSignalEvaluator(weights);

        List<SignalResult> signals = List.of(
            SignalResult.buy("RSI", 25.0),
            SignalResult.sell("ORB", 0.5),
            SignalResult.buy("BollingerBands", 95.0)
        );

        assertEquals(3.0, eval.weightedBuyScore(signals), 0.001); // RSI(2.0) + BollingerBands(1.0)
    }

    @Test
    void weightedSellScoreSumsWeightsOfSellSignals() {
        SignalWeights weights = new SignalWeights(new double[]{2.0, 1.0, 1.0, 0.5, 0.5, 0.5, 1.0, 1.0, 1.0});
        MLSignalEvaluator eval = new MLSignalEvaluator(weights);

        List<SignalResult> signals = List.of(
            SignalResult.sell("RSI", 80.0),
            SignalResult.sell("VWAP", 0.5),
            SignalResult.buy("BollingerBands", 95.0)
        );

        assertEquals(2.5, eval.weightedSellScore(signals), 0.001); // RSI(2.0) + VWAP(0.5)
    }

    @Test
    void unknownIndicatorNamesAreIgnored() {
        SignalWeights weights = new SignalWeights(new double[]{1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0});
        MLSignalEvaluator eval = new MLSignalEvaluator(weights);

        List<SignalResult> signals = List.of(
            SignalResult.buy("UnknownIndicator", 1.0),
            SignalResult.buy("RSI", 25.0)
        );

        assertEquals(1.0, eval.weightedBuyScore(signals), 0.001);
    }

    @Test
    void emptySignalsReturnZero() {
        MLSignalEvaluator eval = new MLSignalEvaluator(new SignalWeights());
        assertEquals(0.0, eval.weightedBuyScore(List.of()), 0.001);
        assertEquals(0.0, eval.weightedSellScore(List.of()), 0.001);
    }

    @Test
    void defaultWeightsYieldSameAsRawCount() {
        MLSignalEvaluator eval = new MLSignalEvaluator(new SignalWeights());

        List<SignalResult> signals = List.of(
            SignalResult.buy("RSI", 25.0),
            SignalResult.buy("VWAP", 0.5),
            SignalResult.neutral("BollingerBands", 100.0)
        );

        assertEquals(2.0, eval.weightedBuyScore(signals), 0.001);
    }
}
