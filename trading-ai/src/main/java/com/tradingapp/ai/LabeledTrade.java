package com.tradingapp.ai;

public class LabeledTrade {
    private final FeatureVector features;
    private final boolean win;

    public LabeledTrade(FeatureVector features, boolean win) {
        this.features = features;
        this.win = win;
    }

    public FeatureVector getFeatures() { return features; }
    public boolean isWin() { return win; }
}
