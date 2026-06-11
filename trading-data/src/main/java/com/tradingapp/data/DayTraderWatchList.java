package com.tradingapp.data;

import java.util.List;

/**
 * 30-symbol watchlist — at the Alpaca WebSocket Free tier cap.
 * Screened under the corrected D2 backtest (options-only, uptrendSupplier wired,
 * LONG_PUT min=3). Each symbol's delta is vs the SPY-alone baseline.
 */
public class DayTraderWatchList {

    public static final List<String> SYMBOLS = List.of(
        // Core — regime filter + market breadth signal
        "SPY", "QQQ",
        // Core mega-cap tech — deep options markets
        "AAPL", "MSFT", "NVDA", "TSLA", "META", "AMZN", "PLTR",
        // D2 screening survivors (originally confirmed, re-verified under corrected framework)
        "LLY",   // +7.7pp (original), retained
        "ORCL",  // +5.8pp (original), retained
        "RTX",   // +6.0pp (original), retained
        "GS",    // +5.5pp (original), retained
        "TSM",   // +2.0pp (original), retained
        "TGT",   // +1.5pp (original), retained
        "MA",    // +1.6pp (original), retained
        "UNH",   // +1.3pp (original), retained
        "GILD",  // +1.9pp (original), retained
        "AXP",   // +10.8pp (original), retained
        "MRNA",  // +2.5pp (original), retained
        "COP",   // +0.9pp (original), retained
        "XOM",   // +0.6pp (original), retained
        // Redeemed losers — false negatives under the flawed backtest
        "ADBE",  // +2.89pp delta (D2 screener)
        "LOW",   // +0.66pp delta (D2 screener)
        // New additions — screened under corrected D2 framework
        "NET",   // +10.30pp delta — highest of all candidates
        "CRWD",  // +4.06pp delta, 67% WR
        "PG",    // +1.57pp delta, 67% WR, 1.54% MaxDD (best risk-adjusted new entry)
        "AMD",   // +1.38pp delta, 56% WR, 1.61% MaxDD
        "WMT",   // +1.01pp delta
        "QCOM"   // +0.32pp delta, 60% WR, 2.39% MaxDD (preferred over CRM for lower MaxDD)
    );
}
