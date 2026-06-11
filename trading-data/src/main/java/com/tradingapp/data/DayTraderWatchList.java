package com.tradingapp.data;

import java.util.List;

/**
 * Final 30-symbol watchlist — at the Alpaca WebSocket Free tier cap.
 * Every symbol except the 3 core ETF/signal names was individually backtested
 * and confirmed additive. Symbols are grouped by the batch in which they were
 * screened, annotated with their individual return delta vs the then-current baseline.
 */
public class DayTraderWatchList {

    public static final List<String> SYMBOLS = List.of(
        // Core — regime filter + market breadth signal (no individual delta available)
        "SPY", "QQQ",
        // Core mega-cap tech — deep options markets (no individual delta available)
        "AAPL", "MSFT", "NVDA", "TSLA", "META", "AMZN", "PLTR",
        // Batch 0 screening winners
        "LLY",   // +7.7pp
        "HD",    // +6.3pp
        "ORCL",  // +5.8pp
        // Batch 2 screening winners
        "RTX",   // +6.0pp, MaxDD↓
        "GS",    // +5.5pp
        "TSM",   // +2.0pp, MaxDD↓
        "TGT",   // +1.5pp
        // Batch 1 screening winners
        "MA",    // +1.6pp
        "CVX",   // +1.5pp
        "UNH",   // +1.3pp, MaxDD↓
        // Batch 3 screening winners
        "NOW",   // +2.5pp, MaxDD↓
        "GILD",  // +1.9pp
        "SBUX",  // +0.9pp
        "ADBE",  // +0.7pp
        // Batch 4 screening winners
        "AXP",   // +10.8pp
        "LOW",   // +9.8pp, MaxDD flat
        "REGN",  // +3.8pp
        "MRNA",  // +2.5pp
        // Marginal positives filling final 3 slots
        "COP",   // +0.9pp
        "XOM",   // +0.6pp
        "AVGO"   // +0.5pp
    );
}
