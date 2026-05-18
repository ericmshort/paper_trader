package com.tradingapp.ai;

import com.tradingapp.account.TransactionLog;
import com.tradingapp.engine.SignalResult;
import com.tradingapp.engine.SignalWeightEvaluator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class MLSignalEvaluator implements SignalWeightEvaluator {

    private static final Logger LOG = Logger.getLogger(MLSignalEvaluator.class.getName());

    private static final Map<String, Integer> INDICATOR_INDEX = Map.of(
        "RSI", 0,
        "MACD", 1,
        "BollingerBands", 2,
        "MACrossover", 3,
        "VolumeSurge", 4
    );

    private final SignalWeights weights;
    private final Path weightsPath;

    public MLSignalEvaluator(SignalWeights weights) {
        this.weights = weights;
        this.weightsPath = Path.of(System.getProperty("user.home"), ".tradingapp", "signal-weights.json");
    }

    public MLSignalEvaluator(SignalWeights weights, Path weightsPath) {
        this.weights = weights;
        this.weightsPath = weightsPath;
    }

    @Override
    public double weightedBuyScore(List<SignalResult> signals) {
        double score = 0;
        for (SignalResult s : signals) {
            if (s.getDirection() == SignalResult.Direction.BUY) {
                Integer idx = INDICATOR_INDEX.get(s.getIndicatorName());
                if (idx != null) score += weights.getWeight(idx);
            }
        }
        return score;
    }

    @Override
    public double weightedSellScore(List<SignalResult> signals) {
        double score = 0;
        for (SignalResult s : signals) {
            if (s.getDirection() == SignalResult.Direction.SELL) {
                Integer idx = INDICATOR_INDEX.get(s.getIndicatorName());
                if (idx != null) score += weights.getWeight(idx);
            }
        }
        return score;
    }

    public void retrain(TransactionLog log) {
        List<LabeledTrade> trades = new TradeFeatureExtractor().extract(log);
        SignalWeights updated = new SignalWeightTrainer().train(trades);
        for (int i = 0; i < SignalWeights.NUM_FEATURES; i++) {
            weights.setWeight(i, updated.getWeight(i));
        }
        try {
            weights.save(weightsPath);
        } catch (IOException e) {
            LOG.warning("Failed to persist signal weights to " + weightsPath + ": " + e.getMessage());
        }
    }

    public String getWeightsSummary() {
        return weights.getSummary();
    }
}
