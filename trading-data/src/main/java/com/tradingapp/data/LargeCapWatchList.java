package com.tradingapp.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LargeCapWatchList {
    public static final List<String> SYMBOLS = Collections.unmodifiableList(Arrays.asList(
        "AAPL", "MSFT", "AMZN", "META", "NVDA",
        "BRK-B", "TSLA", "UNH", "XOM", "JNJ",
        "PG", "MA", "HD", "CVX", "LLY",
        "ABBV", "PEP", "WMT", "CSCO", "TMO",
        "ABT", "MCD", "NKE", "DHR", "ADBE",
        "CRM", "TXN", "NEE", "PM", "UPS",
        "QCOM", "LIN", "MDLZ", "CAT"
    ));

    private LargeCapWatchList() {}
}
