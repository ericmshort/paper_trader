package com.tradingapp.data;

import java.util.List;
import java.util.Set;

/**
 * 100-symbol master universe for dynamic watchlist screening.
 * All symbols are large-cap, highly liquid, with active options markets and
 * tight bid/ask spreads — S&P 100 / NDX tier or comparable liquidity.
 *
 * SPY and QQQ are ANCHOR symbols: always included in the active watchlist
 * regardless of ranking, because SPY drives the regime filter and both
 * provide the deepest options liquidity.
 */
public final class MasterUniverse {

    /** Always kept in the active watchlist; never subject to rotation. */
    public static final Set<String> ANCHORS = Set.of("SPY", "QQQ");

    /** Full 100-symbol universe including anchors. */
    public static final List<String> SYMBOLS = List.of(
        // ── Anchors (2) ───────────────────────────────────────────────────────
        "SPY", "QQQ",

        // ── Mega-cap tech (8) ─────────────────────────────────────────────────
        "AAPL", "MSFT", "NVDA", "META", "AMZN", "GOOGL", "TSLA", "NFLX",

        // ── Semiconductors (10) ───────────────────────────────────────────────
        "AMD", "QCOM", "AVGO", "INTC", "MU", "AMAT", "KLAC", "LRCX", "MRVL", "ARM",

        // ── Software / Cloud / Cybersecurity (11) ─────────────────────────────
        "ORCL", "ADBE", "PLTR", "NET", "CRWD", "PANW", "DDOG", "ZS", "SNOW", "FTNT", "NOW",

        // ── Financials (12) ───────────────────────────────────────────────────
        "JPM", "BAC", "GS", "MS", "C", "WFC", "BLK", "COF", "V", "MA", "PYPL", "COIN",

        // ── Healthcare / Biotech (15) ─────────────────────────────────────────
        "LLY", "UNH", "JNJ", "PFE", "MRK", "AMGN", "ISRG", "REGN",
        "GILD", "MRNA", "VRTX", "DXCM", "BMY", "CVS", "ABT",

        // ── Consumer / Retail (13) ────────────────────────────────────────────
        "WMT", "TGT", "COST", "HD", "LOW", "MCD", "NKE", "KO", "PEP",
        "DIS", "SBUX", "UBER", "ABNB",

        // ── Energy (5) ────────────────────────────────────────────────────────
        "XOM", "CVX", "COP", "SLB", "EOG",

        // ── Industrials / Defense (9) ─────────────────────────────────────────
        "RTX", "LMT", "NOC", "GD", "BA", "CAT", "HON", "DE", "GE",

        // ── Telecom (3) ───────────────────────────────────────────────────────
        "T", "VZ", "TMUS",

        // ── Other large-cap (12) ──────────────────────────────────────────────
        "TSM", "PG", "AXP", "ACN", "IBM", "CB", "MET", "F", "GM", "SCHW", "SHOP", "BIIB"
    );
    // Total: 2+8+10+11+12+15+13+5+9+3+12 = 100

    private MasterUniverse() {}
}
