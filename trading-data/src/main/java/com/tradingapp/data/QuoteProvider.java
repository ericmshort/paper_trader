package com.tradingapp.data;

import java.time.LocalDate;
import java.util.List;

public interface QuoteProvider {
    QuoteModel getQuote(String symbol);
    List<QuoteModel> getQuotes(List<String> symbols);
    OptionsChain getOptionsChain(String symbol, LocalDate expiry);
    List<HistoricalBar> getHistoricalBars(String symbol, LocalDate start, LocalDate end);
    String getName();
}
