package com.tradingapp.data;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
public class YahooFinanceClientTest {

    private final YahooFinanceClient client = new YahooFinanceClient();

    @Test
    void testGetQuoteReturnsValidPrice() {
        QuoteModel quote = client.getQuote("AAPL");
        assertNotNull(quote, "Quote for AAPL should not be null");
        assertEquals("AAPL", quote.getSymbol());
        assertTrue(quote.getPrice() > 0, "Price should be positive, got: " + quote.getPrice());
        assertFalse(quote.isStale(), "Quote should not be stale immediately after fetch");
    }

    @Test
    void testGetQuotesReturnsFiveSymbols() {
        List<String> symbols = List.of("AAPL", "MSFT", "GOOGL", "AMZN", "META");
        List<QuoteModel> quotes = client.getQuotes(symbols);
        assertEquals(5, quotes.size(), "Expected 5 quotes, got: " + quotes.size());
        for (QuoteModel q : quotes) {
            assertTrue(q.getPrice() > 0, "Price should be positive for " + q.getSymbol());
        }
    }

    @Test
    void testStaleFlagSetOnOldTimestamp() {
        long tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000L);
        QuoteModel staleQuote = QuoteModel.withTimestamp("TEST", 100.0, 99.9, 100.1, 1000L, tenMinutesAgo);
        assertTrue(staleQuote.isStale(), "Quote with 10-minute-old timestamp should be stale");
    }
}
