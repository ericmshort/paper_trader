package com.tradingapp.data;

import java.util.List;

/**
 * The 30 most liquid US equities for day trading: highest average daily volume
 * and tightest bid-ask spreads. Used by the AlpacaWebSocket_Free provider.
 */
public class DayTraderWatchList {

    public static final List<String> SYMBOLS = List.of(
        // Broad market ETFs — regime filter and low-volatility options targets
        "SPY", "QQQ",
        // Mega-cap tech with consistent positive P&L in backtests
        "AAPL", "MSFT", "NVDA", "AMD",
        // High-momentum individual names
        "TSLA",
        // Large-cap internet
        "META", "AMZN", "GOOGL",
        // Mid-cap tech outperformers
        "INTC", "PLTR",
        // High-volume low-beta with tight spreads
        "F", "NOK"
    );
}
