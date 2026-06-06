package com.tradingapp.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

public class SignalWeights {

    private static final Logger LOG = Logger.getLogger(SignalWeights.class.getName());
    public static final int NUM_FEATURES = 6;
    private static final String[] NAMES = {"RSI", "BollingerBands", "VolumeSurge", "VWAP", "ORB", "Candlestick"};

    private final double[] weights;

    public SignalWeights() {
        this.weights = new double[]{1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
    }

    public SignalWeights(double[] weights) {
        if (weights.length != NUM_FEATURES) throw new IllegalArgumentException("Expected 6 weights");
        this.weights = weights.clone();
    }

    public double getWeight(int index) { return weights[index]; }

    public void setWeight(int index, double value) { weights[index] = value; }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < NUM_FEATURES; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(NAMES[i]).append("\":").append(weights[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    public static SignalWeights fromJson(String json) {
        double[] w = new double[NUM_FEATURES];
        for (int i = 0; i < NUM_FEATURES; i++) {
            try {
                String key = "\"" + NAMES[i] + "\":";
                int start = json.indexOf(key);
                if (start < 0) { w[i] = 1.0; continue; }
                start += key.length();
                int end = json.indexOf(",", start);
                if (end < 0) end = json.indexOf("}", start);
                w[i] = Double.parseDouble(json.substring(start, end).trim());
            } catch (Exception e) {
                LOG.warning("Failed to parse weight for " + NAMES[i] + ", using 1.0: " + e.getMessage());
                w[i] = 1.0;
            }
        }
        return new SignalWeights(w);
    }

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, toJson());
    }

    public static SignalWeights load(Path path) throws IOException {
        return fromJson(Files.readString(path));
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NUM_FEATURES; i++) {
            if (i > 0) sb.append(" ");
            sb.append(NAMES[i]).append("=").append(String.format("%.2f", weights[i]));
        }
        return sb.toString();
    }
}
