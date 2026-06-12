package com.tradingapp.options;

import java.time.LocalDate;
import java.util.List;

public interface OptionsSubmitter {
    /** Submit a single-leg options order; returns the broker order ID or null on failure. */
    String submit(String symbol, String optionType, double strike, LocalDate expiry, int contracts, String side);

    /**
     * Submit a single-leg options order with an explicit position_intent.
     * Required for closing orders so the broker doesn't treat them as naked writes.
     * Defaults to the side-only form for paper-trading implementations.
     */
    default String submit(String symbol, String optionType, double strike, LocalDate expiry,
                          int contracts, String side, String positionIntent) {
        return submit(symbol, optionType, strike, expiry, contracts, side);
    }

    /**
     * Submit a close order using the exact OCC symbol already held in the broker account,
     * bypassing contract re-lookup. This avoids position_intent mismatch when the actual
     * filled contract differs from the originally targeted strike/expiry.
     * Defaults to a no-op (returns null) for paper-trading implementations.
     */
    default String submitDirect(String occSymbol, int contracts, String side, String positionIntent) {
        return null;
    }

    /**
     * Submit a multi-leg options order atomically (all-or-nothing).
     * Returns the broker order ID on success, or null if multi-leg is not supported or the order was rejected.
     * The default returns null, which causes callers to fall back to sequential single-leg submission.
     */
    default String submitMultiLeg(List<MultiLegOrder> legs, int contracts) {
        return null;
    }

    /**
     * Submit a market order to buy stock shares.
     * Returns the broker order ID on success, or null if unsupported or rejected.
     * The default returns null (paper-trading path uses direct account manipulation).
     */
    default String buyStock(String symbol, int shares) {
        return null;
    }

    /**
     * Submit a market order to sell stock shares.
     * Returns the broker order ID on success, or null if unsupported or rejected.
     */
    default String sellStock(String symbol, int shares) {
        return null;
    }

    /**
     * Close every open options position held at the broker by fetching the live position list
     * and issuing a DELETE for each OCC symbol found.
     * Returns -1 if not supported (paper trading), 0 if no open options remain,
     * or a positive count of positions that were found and submitted for close (retry on next tick
     * until this returns 0 to confirm all are gone).
     */
    default int closeAllOptionsPositions() {
        return -1;
    }
}
