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
 * @param positionIntent  Alpaca position intent: "bto", "btc", "sto", "stc"
 */
public record MultiLegOrder(
        String symbol,
        String optionType,
        double strike,
        LocalDate expiry,
        String side,
        String positionIntent
) {}
