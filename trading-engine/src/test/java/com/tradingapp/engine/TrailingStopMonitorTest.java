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
    void testTriggerAtExactlyFivePercent() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertTrue(monitor.check("AAPL", 95.0));
    }

    @Test
    void testNoTriggerJustAboveFivePercent() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 95.01));
    }

    @Test
    void testPeakUpdatesOnHigherPrice() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 110.0));  // rises, peak now 110
        assertFalse(monitor.check("AAPL", 106.0));  // 106 > 104.5 = 110*0.95, no trigger
        assertFalse(monitor.check("AAPL", 104.51)); // 104.51 > 104.5, no trigger
        assertTrue(monitor.check("AAPL", 104.5));   // 104.5 <= 110 * 0.95, triggers
    }

    @Test
    void testResetClearsPeak() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        monitor.reset("AAPL");
        monitor.updatePeak("AAPL", 50.0);
        assertTrue(monitor.check("AAPL", 47.5));
    }
}
