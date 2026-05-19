package com.tradingapp.data;

import java.util.Collections;
import java.util.Map;

public class OptionsChain {
    private final Map<Double, OptionsQuote> calls;
    private final Map<Double, OptionsQuote> puts;

    public OptionsChain(Map<Double, OptionsQuote> calls, Map<Double, OptionsQuote> puts) {
        this.calls = Collections.unmodifiableMap(calls);
        this.puts = Collections.unmodifiableMap(puts);
    }

    public static OptionsChain empty() {
        return new OptionsChain(Map.of(), Map.of());
    }

    public OptionsQuote getCall(double strike) { return calls.get(strike); }
    public OptionsQuote getPut(double strike) { return puts.get(strike); }
}
