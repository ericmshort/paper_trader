package com.tradingapp.engine;

import java.time.ZonedDateTime;

public record IntradayBar(String symbol, ZonedDateTime time, double open, double high, double low, double close, long volume) {}
