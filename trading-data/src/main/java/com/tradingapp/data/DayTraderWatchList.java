package com.tradingapp.data;

import java.util.List;

/**
 * The 30 most liquid US equities for day trading: highest average daily volume
 * and tightest bid-ask spreads. Used by the AlpacaWebSocket_Free provider.
 */
public class DayTraderWatchList {

    public static final List<String> SYMBOLS = List.of(
        // High-volume ETFs and leveraged ETFs
        "SPY", "QQQ", "IWM", "TQQQ", "SQQQ",
        // Mega-cap tech (highest individual stock volume)
        "AAPL", "MSFT", "NVDA", "TSLA", "AMD",
        // More large-cap tech
        "META", "AMZN", "GOOGL", "INTC",
        // High-volume speculative / growth
        "PLTR", "SOFI", "MARA", "RIOT", "NIO", "RIVN",
        // Retail favorites / meme stocks (consistently high volume)
        "GME", "AMC",
        // High-volume financials and blue chips
        "BAC", "C", "JPM", "F", "GE", "T",
        // High-volume micro/penny-adjacent with tight spreads
        "SNDL", "NOK"
    );
}
