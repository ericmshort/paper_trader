package com.tradingapp.options;

import java.time.LocalDate;

@FunctionalInterface
public interface OptionsSubmitter {
    /**
     * Submit an options order to the broker.
     * @return the Alpaca order ID, or null if submission failed
     */
    String submit(String symbol, String optionType, double strike, LocalDate expiry, int contracts, String side);
}
