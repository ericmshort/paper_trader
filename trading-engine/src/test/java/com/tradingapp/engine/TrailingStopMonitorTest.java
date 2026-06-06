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
    void testTriggerAtExactlyTwoPercent() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertTrue(monitor.check("AAPL", 98.0));
    }

    @Test
    void testNoTriggerJustAboveTwoPercent() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 98.01));
    }

    @Test
    void testPeakUpdatesOnHigherPrice() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        assertFalse(monitor.check("AAPL", 110.0));    // rises, peak now 110
        assertFalse(monitor.check("AAPL", 108.0));    // 108 > 107.8 = 110*0.98, no trigger
        assertFalse(monitor.check("AAPL", 107.81));   // 107.81 > 107.8, no trigger
        assertTrue(monitor.check("AAPL", 107.8));     // 107.8 <= 110 * 0.98, triggers
    }

    @Test
    void testResetClearsPeak() {
        TrailingStopMonitor monitor = new TrailingStopMonitor();
        monitor.updatePeak("AAPL", 100.0);
        monitor.reset("AAPL");
        monitor.updatePeak("AAPL", 50.0);
        assertTrue(monitor.check("AAPL", 48.0)); // 48 <= 50 * 0.98 = 49.0
    }
}
