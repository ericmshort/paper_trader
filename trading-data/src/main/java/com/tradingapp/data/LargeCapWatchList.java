package com.tradingapp.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LargeCapWatchList {
    public static final List<String> SYMBOLS = Collections.unmodifiableList(Arrays.asList(
        "AAPL", "MSFT", "GOOGL", "AMZN", "META",
        "NVDA", "BRK-B", "TSLA", "UNH", "XOM",
        "JPM", "JNJ", "V", "PG", "MA",
        "HD", "CVX", "MRK", "LLY", "ABBV",
        "PEP", "KO", "AVGO", "COST", "WMT",
        "BAC", "DIS", "CSCO", "TMO", "ACN",
        "ABT", "MCD", "NKE", "DHR", "ADBE",
        "CRM", "TXN", "NEE", "PM", "RTX",
        "QCOM", "HON", "LOW", "UPS", "IBM",
        "LIN", "AMGN", "SBUX", "MDLZ", "CAT"
    ));

    private LargeCapWatchList() {}
}
