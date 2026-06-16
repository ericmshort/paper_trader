package com.tradingapp.data;

import java.util.List;

/**
 * 30-symbol watchlist — at the Alpaca WebSocket Free tier cap.
 * All 30 are options-eligible (no QQQ/TSLA exclusions needed).
 * Selected by 2-year net options P&L ranking across the 100-symbol MasterUniverse.
 */
public class DayTraderWatchList {

    public static final List<String> SYMBOLS = List.of(
        "SPY",   // regime anchor
        "NOC",   // $47,786 net P&L, 51.2% WR
        "NVDA",  // $16,423
        "MSFT",  // $29,619
        "COST",  // $66,816
        "VRTX",  // $43,325
        "AMGN",  // $24,205
        "CRWD",  // $55,134
        "GS",    // $104,298
        "AVGO",  // $23,068
        "LRCX",  // $60,660
        "DE",    // $42,482
        "ORCL",  // $23,454
        "LLY",   // $83,927
        "BLK",   // $49,138
        "NOW",   // $29,529
        "MA",    // $13,829
        "REGN",  // $76,015
        "META",  // $16,654
        "AMAT",  // $22,986
        "KLAC",  // $173,866
        "CAT",   // $49,372
        "NFLX",  // $54,868
        "UNH",   // $24,273
        "LMT",   // $14,458
        "JPM",   // scan P&L $15,934 — +56pp return vs 25-symbol baseline
        "MU",    // scan P&L $13,462
        "HD",    // scan P&L $11,789
        "MCD",   // scan P&L $8,908
        "V"      // scan P&L $8,492
    );
}
