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
        "NOC",   // $11,079 net P&L, 56.3% WR
        "NVDA",  // $9,752
        "MSFT",  // $8,584
        "COST",  // $7,701
        "VRTX",  // $6,524
        "AMGN",  // $6,318
        "CRWD",  // $6,139
        "RTX",   // $5,905
        "GS",    // $3,660
        "AVGO",  // $3,353
        "LRCX",  // $2,681
        "XOM",   // $2,675
        "WMT",   // $2,397
        "DE",    // $2,204
        "ORCL",  // $2,065
        "PG",    // $1,921
        "LLY",   // $1,918
        "BLK",   // $1,654
        "NOW",   // $1,623
        "MA",    // $1,521
        "REGN",  // $1,448
        "META",  // $1,425
        "MS",    // $1,173
        "AMAT"   // $984  (COIN excluded — crypto correlation risk)
    );
}
