package com.tradingapp.data;

/**
 * Pre-market news sentiment for one symbol.
 * weight: 0.0–2.0 — how strongly to push the signal into the trading loop.
 * summary: one-sentence explanation logged to the research area.
 */
public record SentimentScore(
        String symbol,
        SentimentDirection direction,
        double weight,
        String summary
) {}
