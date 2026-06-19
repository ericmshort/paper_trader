package com.tradingapp.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LargeCapWatchList {
    public static final List<String> SYMBOLS = Collections.unmodifiableList(Arrays.asList(
        "AAPL", "MSFT", "AMZN", "META", "NVDA",
        "XOM", "JNJ", "PG", "CVX", "LLY",
        "ABBV", "PEP", "WMT", "CSCO", "TMO",
        "ADBE", "TXN", "NEE", "PM", "QCOM",
        "LIN", "MDLZ", "CAT"
    ));

    private LargeCapWatchList() {}
}
