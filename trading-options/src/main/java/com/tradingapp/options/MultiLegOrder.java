package com.tradingapp.options;

import java.time.LocalDate;

/**
 * Describes a single leg in a multi-leg options order sent to the broker.
 *
 * @param symbol          underlying ticker (e.g. "AAPL")
 * @param optionType      "CALL" or "PUT"
 * @param strike          strike price
 * @param expiry          expiration date
 * @param side            "buy" or "sell"
 * @param positionIntent  Alpaca position intent: "buy_to_open", "buy_to_close", "sell_to_open", "sell_to_close"
 * @param occSymbol       pre-resolved Alpaca OCC symbol; null for new opens (lookup will be performed)
 */
public record MultiLegOrder(
        String symbol,
        String optionType,
        double strike,
        LocalDate expiry,
        String side,
        String positionIntent,
        String occSymbol
) {
    /** Convenience constructor for new position opens (OCC symbol not yet known). */
    public MultiLegOrder(String symbol, String optionType, double strike, LocalDate expiry,
                         String side, String positionIntent) {
        this(symbol, optionType, strike, expiry, side, positionIntent, null);
    }
}
