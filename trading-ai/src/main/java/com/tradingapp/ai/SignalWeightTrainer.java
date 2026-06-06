package com.tradingapp.ai;

import weka.attributeSelection.InfoGainAttributeEval;
import weka.attributeSelection.Ranker;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class SignalWeightTrainer {

    private static final Logger LOG = Logger.getLogger(SignalWeightTrainer.class.getName());
    static final int MIN_TRADES = 10;

    public SignalWeights train(List<LabeledTrade> trades) {
        if (trades.size() < MIN_TRADES) {
            return new SignalWeights();
        }

        try {
            ArrayList<Attribute> attrs = new ArrayList<>();
            String[] featureNames = {"RSI", "BollingerBands", "VolumeSurge", "VWAP", "ORB", "Candlestick"};
            for (String name : featureNames) {
                attrs.add(new Attribute(name));
            }
            ArrayList<String> classValues = new ArrayList<>();
            classValues.add("WIN");
            classValues.add("LOSE");
            attrs.add(new Attribute("outcome", classValues));

            Instances dataset = new Instances("trades", attrs, trades.size());
            dataset.setClassIndex(featureNames.length);

            for (LabeledTrade t : trades) {
                double[] vals = new double[featureNames.length + 1];
                for (int i = 0; i < featureNames.length; i++) {
                    vals[i] = t.getFeatures().get(i);
                }
                vals[featureNames.length] = t.isWin() ? 0 : 1;
                dataset.add(new DenseInstance(1.0, vals));
            }

            InfoGainAttributeEval evaluator = new InfoGainAttributeEval();
            evaluator.buildEvaluator(dataset);
            // evaluateAttribute(i) retrieves scores by index directly — Ranker ordering not needed

            double[] scores = new double[featureNames.length];
            double sum = 0;
            for (int i = 0; i < featureNames.length; i++) {
                scores[i] = evaluator.evaluateAttribute(i) + 0.01;
                sum += scores[i];
            }

            double[] weights = new double[featureNames.length];
            for (int i = 0; i < featureNames.length; i++) {
                weights[i] = scores[i] / sum * SignalWeights.NUM_FEATURES;
            }

            return new SignalWeights(weights);
        } catch (Throwable e) {
            LOG.warning("Weka training failed, returning default weights: " + e.getMessage());
            return new SignalWeights();
        }
    }
}
