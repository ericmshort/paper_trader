package com.tradingapp.data;

import java.time.LocalDate;
import java.util.List;

public class YahooFinanceQuoteProvider implements QuoteProvider {

    private final YahooFinanceClient client;
    private final HistoricalBarFetcher barFetcher;

    public YahooFinanceQuoteProvider() {
        this.client = new YahooFinanceClient();
        this.barFetcher = new HistoricalBarFetcher();
    }

    @Override public QuoteModel getQuote(String symbol) { return client.getQuote(symbol); }
    @Override public List<QuoteModel> getQuotes(List<String> symbols) { return client.getQuotes(symbols); }
    @Override public OptionsChain getOptionsChain(String symbol, LocalDate expiry) { return client.getOptionsChain(symbol, expiry); }
    @Override public List<HistoricalBar> getHistoricalBars(String symbol, LocalDate start, LocalDate end) { return barFetcher.fetchDailyBars(symbol, start, end); }
    @Override public String getName() { return "Yahoo Finance"; }

    /** Yahoo only provides current options chains — no historical options data. */
    @Override public LocalDate getEarliestBacktestDate() { return LocalDate.now().minusYears(1); }
}
