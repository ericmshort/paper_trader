package com.tradingapp.data;

public class DataUnavailableException extends RuntimeException {
    public DataUnavailableException(String symbol, Throwable cause) {
        super("Failed to fetch quote for symbol: " + symbol, cause);
    }

    public DataUnavailableException(String message) {
        super(message);
    }
}
