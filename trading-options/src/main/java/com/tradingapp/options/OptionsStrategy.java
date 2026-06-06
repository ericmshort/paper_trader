package com.tradingapp.options;

public enum OptionsStrategy {
    HIGH_DELTA_SCALP("High-Delta Scalp"),
    MOMENTUM_NEAR_TERM("Near-Term Momentum"),
    LONG_CALL("Long Call"),
    LONG_PUT("Long Put"),
    ZERO_DTE("Zero-DTE Straddle");

    private final String displayName;

    OptionsStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
