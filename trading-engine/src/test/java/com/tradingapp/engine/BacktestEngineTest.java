package com.tradingapp.engine;

import com.tradingapp.data.HistoricalBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine(new IndicatorEngine(), new FeeCalculator());

    @Test
    void emptyBarsReturnsZeroTrades() {
        BacktestConfig config = new BacktestConfig(
                List.of("TEST"),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 3, 31),
                100_000.0);

        BacktestResult result = engine.run(config, Collections.emptyMap());

        assertEquals(0, result.getTotalTrades());
        assertTrue(result.getEquityCurve().isEmpty());
        assertEquals(0.0, result.getTotalReturnPct(), 0.001);
        assertEquals(0.0, result.getMaxDrawdownPct(), 0.001);
        assertEquals(0.0, result.winRate(), 0.001);
    }

    @Test
    void insufficientHistoryProducesNoTrades() {
        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 2);
        for (int i = 0; i < 20; i++) {
            bars.add(new HistoricalBar("TEST", start.plusDays(i), 100.0, 100.0, 100.0, 100.0, 1_000_000L));
        }

        BacktestConfig config = new BacktestConfig(
                List.of("TEST"), start, start.plusDays(19), 100_000.0);

        BacktestResult result = engine.run(config, Map.of("TEST", bars));

        assertEquals(0, result.getTotalTrades(), "Fewer than 30 bars should produce no trades");
    }

    @Test
    void equityCurveHasSameSizeAsTradingDays() {
        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 2);
        double price = 100.0;
        for (int i = 0; i < 60; i++) {
            // Bars 0-19: flat; bars 20-39: declining 2/bar; bars 40-59: rising 2/bar
            if (i >= 20 && i < 40) price -= 2.0;
            else if (i >= 40) price += 2.0;
            bars.add(new HistoricalBar("TEST", start.plusDays(i), price, price, price, price, 2_000_000L));
        }

        BacktestConfig config = new BacktestConfig(
                List.of("TEST"), start, start.plusDays(59), 100_000.0);

        BacktestResult result = engine.run(config, Map.of("TEST", bars));

        assertEquals(60, result.getEquityCurve().size(), "Equity curve should have one point per trading day");
        assertEquals(100_000.0, result.getEquityCurve().get(0).getPortfolioValue(), 0.01,
                "First equity point should equal starting balance");
        assertTrue(result.getMaxDrawdownPct() >= 0.0, "Max drawdown must be non-negative");
    }
}
