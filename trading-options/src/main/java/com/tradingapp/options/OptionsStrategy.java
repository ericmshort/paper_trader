package com.tradingapp.options;

public enum OptionsStrategy {
    LONG_CALL("Long Call"),
    LONG_PUT("Long Put"),
    BULL_CALL_SPREAD("Bull Call Spread"),
    BEAR_PUT_SPREAD("Bear Put Spread"),
    BULL_PUT_SPREAD("Bull Put Spread"),
    BEAR_CALL_SPREAD("Bear Call Spread"),
    HIGH_DELTA_SCALP("High-Delta Scalp"),
    ZERO_DTE("Zero-DTE Straddle"),
    MOMENTUM_NEAR_TERM("Near-Term Momentum"),
    STRADDLE("Straddle"),
    STRANGLE("Strangle"),
    BUTTERFLY("Butterfly"),
    COVERED_CALL("Covered Call");

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
