package com.tradingapp.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TrailingStopMonitorTest {

    @Test
    void testNoTriggerWhenPriceRises() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 101.0));
    }

    @Test
    void testTriggerAtExactlyFourPercent() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertTrue(monitor.check("AAPL", 96.0));
    }

    @Test
    void testNoTriggerJustAboveFourPercent() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 96.01));
    }

    @Test
    void testPeakUpdatesOnHigherPrice() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 110.0));    // rises, peak now 110
        assertFalse(monitor.check("AAPL", 106.0));    // 106 > 105.6 = 110*0.96, no trigger
        assertFalse(monitor.check("AAPL", 105.61));   // just above threshold, no trigger
        assertTrue(monitor.check("AAPL", 105.6));     // 105.6 <= 110 * 0.96, triggers
    }

    @Test
    void testResetClearsPeak() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        monitor.reset("AAPL");
        monitor.updatePeak("AAPL", 50.0);
        assertTrue(monitor.check("AAPL", 48.0)); // 48 <= 50 * 0.96 = 48.0
    }
}
