package com.tradingapp.options;

import java.time.LocalDate;
import java.util.List;

public interface OptionsSubmitter {
    /** Submit a single-leg options order; returns the broker order ID or null on failure. */
    String submit(String symbol, String optionType, double strike, LocalDate expiry, int contracts, String side);

    /**
     * Submit a multi-leg options order atomically (all-or-nothing).
     * Returns the broker order ID on success, or null if multi-leg is not supported or the order was rejected.
     * The default returns null, which causes callers to fall back to sequential single-leg submission.
     */
    default String submitMultiLeg(List<MultiLegOrder> legs, int contracts) {
        return null;
    }
}
