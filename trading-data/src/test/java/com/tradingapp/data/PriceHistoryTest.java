package com.tradingapp.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PriceHistoryTest {

    @Test
    void testRecordPopulatesIntradayOnly() {
        PriceHistory ph = new PriceHistory();
        ph.record("AAPL", 150.0, 1_000_000);
        ph.record("AAPL", 151.0, 1_000_000);

        assertEquals(2, ph.getPrices("AAPL").size());
        assertEquals(0, ph.getDailyPrices("AAPL").size(), "record() must not touch daily store");
    }

    @Test
    void testRecordDailyPopulatesDailyOnly() {
        PriceHistory ph = new PriceHistory();
        ph.recordDaily("AAPL", 150.0, 1_000_000);
        ph.recordDaily("AAPL", 151.0, 1_000_000);

        assertEquals(2, ph.getDailyPrices("AAPL").size());
        assertEquals(0, ph.getPrices("AAPL").size(), "recordDaily() must not touch intraday store");
    }

    @Test
    void testSeedPopulatesBothStores() {
        PriceHistory ph = new PriceHistory();
        List<HistoricalBar> bars = List.of(
                new HistoricalBar("AAPL", LocalDate.of(2026, 1, 2), 148, 152, 147, 150, 1_000_000L),
                new HistoricalBar("AAPL", LocalDate.of(2026, 1, 3), 150, 155, 149, 153, 1_200_000L),
                new HistoricalBar("AAPL", LocalDate.of(2026, 1, 6), 153, 156, 151, 154, 900_000L)
        );
        ph.seed("AAPL", bars);

        assertEquals(3, ph.getPrices("AAPL").size(), "Intraday store should be seeded");
        assertEquals(3, ph.getDailyPrices("AAPL").size(), "Daily store should be seeded");
        assertEquals(154.0, ph.getDailyPrices("AAPL").get(2), 0.001, "Daily prices should be closes");
    }

    @Test
    void testClearRemovesBothStores() {
        PriceHistory ph = new PriceHistory();
        ph.record("AAPL", 150.0, 1_000_000);
        ph.recordDaily("AAPL", 150.0, 1_000_000);
        ph.clear("AAPL");

        assertTrue(ph.getPrices("AAPL").isEmpty());
        assertTrue(ph.getDailyPrices("AAPL").isEmpty());
    }

    @Test
    void testDailyAndIntradayAreIndependent() {
        PriceHistory ph = new PriceHistory();
        ph.record("AAPL", 100.0, 1_000_000);
        ph.record("AAPL", 101.0, 1_000_000);
        ph.recordDaily("AAPL", 200.0, 2_000_000);

        List<Double> intraday = ph.getPrices("AAPL");
        List<Double> daily = ph.getDailyPrices("AAPL");

        assertEquals(2, intraday.size());
        assertEquals(1, daily.size());
        assertEquals(200.0, daily.get(0), 0.001);
        assertEquals(100.0, intraday.get(0), 0.001);
    }
}
