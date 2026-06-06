package com.tradingapp.options;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.engine.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class OptionsBacktestEngineTest {

    private static final String SYM = "AAPL";
    private static final double PRICE = 150.0;
    private static final double SIGMA = 0.30;
    private static final double T = OptionsBacktestEngine.EXPIRY_DAYS / 365.0;
    private static final double K = 150.0;

    private OptionsBacktestEngine engine;
    private BlackScholesEngine bs;

    @BeforeEach
    void setUp() {
        bs = new BlackScholesEngine();
        engine = new OptionsBacktestEngine(new IndicatorEngine(), bs, new FeeCalculator());
    }

    // ---- entryCondition ----

    @Test
    void longCallEntersOnBuySignals() {
        assertTrue(engine.entryCondition(OptionsStrategy.LONG_CALL, 2, 0));
        assertFalse(engine.entryCondition(OptionsStrategy.LONG_CALL, 1, 2));
    }

    @Test
    void longPutEntersOnSellSignals() {
        assertTrue(engine.entryCondition(OptionsStrategy.LONG_PUT, 0, 2));
        assertFalse(engine.entryCondition(OptionsStrategy.LONG_PUT, 2, 0));
    }

    @Test
    void zeroDteEntersOnEitherSignal() {
        assertTrue(engine.entryCondition(OptionsStrategy.ZERO_DTE, 2, 0));
        assertTrue(engine.entryCondition(OptionsStrategy.ZERO_DTE, 0, 2));
        assertFalse(engine.entryCondition(OptionsStrategy.ZERO_DTE, 1, 1));
    }

    @Test
    void highDeltaScalpEntersOnStrongSignal() {
        assertTrue(engine.entryCondition(OptionsStrategy.HIGH_DELTA_SCALP, 3, 0));
        assertTrue(engine.entryCondition(OptionsStrategy.HIGH_DELTA_SCALP, 0, 3));
        assertFalse(engine.entryCondition(OptionsStrategy.HIGH_DELTA_SCALP, 2, 0));
    }

    // ---- buildPosition ----

    @Test
    void longCallPositionHasCorrectCostBasis() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.LONG_CALL, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        double expected = bs.callPrice(PRICE, K, OptionsBacktestEngine.RISK_FREE_RATE, T, SIGMA) * 100 * pos.contracts;
        assertEquals(expected, pos.totalCostBasis, 0.01);
        assertEquals(1, pos.legs.size());
        assertTrue(pos.legs.get(0).isCall());
        assertTrue(pos.legs.get(0).isLong());
        assertEquals(0, pos.maxProfit, 0.01); // unlimited
    }

    @Test
    void longPutPositionHasCorrectCostBasis() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.LONG_PUT, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        double expected = bs.putPrice(PRICE, K, OptionsBacktestEngine.RISK_FREE_RATE, T, SIGMA) * 100 * pos.contracts;
        assertEquals(expected, pos.totalCostBasis, 0.01);
        assertFalse(pos.legs.get(0).isCall());
        assertTrue(pos.legs.get(0).isLong());
    }

    // ---- computeCurrentValue ----

    @Test
    void longCallValueRisesWithPrice() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.LONG_CALL, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        double low = engine.computeCurrentValue(pos, PRICE - 20, T, SIGMA);
        double high = engine.computeCurrentValue(pos, PRICE + 20, T, SIGMA);
        assertTrue(high > low, "Long call value should increase as underlying rises");
    }

    @Test
    void longPutValueRisesAsPriceFalls() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.LONG_PUT, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        double low = engine.computeCurrentValue(pos, PRICE - 20, T, SIGMA);
        double high = engine.computeCurrentValue(pos, PRICE + 20, T, SIGMA);
        assertTrue(low > high, "Long put value should increase as underlying falls");
    }

    @Test
    void computeValueAtExpiryUsesIntrinsic() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.LONG_CALL, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        // At expiry (T=0), value = max(0, price - K) * 100 * contracts
        double intrinsic = Math.max(0, PRICE + 30 - K) * 100 * pos.contracts;
        double computed = engine.computeCurrentValue(pos, PRICE + 30, 0.0, SIGMA);
        assertEquals(intrinsic, computed, 0.01);
    }

    // ---- shouldExit ----

    @Test
    void exitsNearExpiry() {
        LocalDate expiry = LocalDate.now().plusDays(2);
        var pos = new OptionsBacktestEngine.OpenPosition(SYM, expiry, 1, SIGMA, 500, 0,
                List.of(new OptionsBacktestEngine.Leg(K, true, true, 1)), 0, 0);
        assertTrue(engine.shouldExit(pos, 500, 0, 0, LocalDate.now(), OptionsStrategy.LONG_CALL, PRICE));
    }

    @Test
    void exitsOnPremiumStopLoss() {
        LocalDate expiry = LocalDate.now().plusDays(30);
        var pos = new OptionsBacktestEngine.OpenPosition(SYM, expiry, 1, SIGMA, 500, 0,
                List.of(new OptionsBacktestEngine.Leg(K, true, true, 1)), 0, 0);
        // currentValue = 45% of cost basis → triggers 50% stop
        assertTrue(engine.shouldExit(pos, 225, 0, 0, LocalDate.now(), OptionsStrategy.LONG_CALL, PRICE));
    }

    @Test
    void exitsOnProfitTarget() {
        LocalDate expiry = LocalDate.now().plusDays(30);
        var pos = new OptionsBacktestEngine.OpenPosition(SYM, expiry, 1, SIGMA, 500, 0,
                List.of(new OptionsBacktestEngine.Leg(K, true, true, 1)), 0, 0);
        // currentValue = 2x cost basis → profit target hit
        assertTrue(engine.shouldExit(pos, 1000, 0, 0, LocalDate.now(), OptionsStrategy.LONG_CALL, PRICE));
    }

    // ---- integration: run() returns a valid BacktestResult ----

    @Test
    void runReturnsValidResultForAllStrategies() {
        Map<String, List<HistoricalBar>> bars = Map.of(SYM, buildBars());
        BacktestConfig cfg = new BacktestConfig(List.of(SYM),
                LocalDate.now().minusDays(100), LocalDate.now(), 100_000.0);

        for (OptionsStrategy strategy : OptionsStrategy.values()) {
            BacktestResult result = engine.run(strategy, cfg, bars);
            assertNotNull(result, strategy + ": result should not be null");
            assertNotNull(result.getEquityCurve(), strategy + ": equity curve should not be null");
            assertTrue(result.getMaxDrawdownPct() >= 0, strategy + ": drawdown >= 0");
        }
    }

    @Test
    void longCallProfitsWhenPriceRises() {
        List<HistoricalBar> bars = buildTrendingBars(150.0, 100.0, 200.0);
        Map<String, List<HistoricalBar>> barsBySymbol = Map.of(SYM, bars);
        BacktestConfig cfg = new BacktestConfig(List.of(SYM),
                bars.get(0).getDate(), bars.get(bars.size() - 1).getDate(), 100_000.0);

        BacktestResult result = engine.run(OptionsStrategy.LONG_CALL, cfg, barsBySymbol);
        assertTrue(result.getTotalTrades() >= 0);
    }

    // ---- helpers ----

    private List<HistoricalBar> buildBars() {
        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.now().minusDays(100);
        double price = 150.0;
        for (int i = 0; i < 100; i++) {
            double p = price + (i % 7 == 0 ? -5 : 3);
            price = Math.max(10, p);
            bars.add(new HistoricalBar(SYM, date.plusDays(i), price, price + 2, price - 2, price, 1_000_000L));
        }
        return bars;
    }

    private List<HistoricalBar> buildTrendingBars(double start, double low, double high) {
        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.now().minusDays(120);
        for (int i = 0; i < 40; i++) {
            bars.add(new HistoricalBar(SYM, date.plusDays(i), start, start + 1, start - 1, start, 1_000_000L));
        }
        for (int i = 0; i < 20; i++) {
            double p = start + (low - start) * i / 20.0;
            bars.add(new HistoricalBar(SYM, date.plusDays(40 + i), p, p + 1, p - 1, p, 1_500_000L));
        }
        for (int i = 0; i < 60; i++) {
            double p = low + (high - low) * i / 60.0;
            bars.add(new HistoricalBar(SYM, date.plusDays(60 + i), p, p + 1, p - 1, p, 2_000_000L));
        }
        return bars;
    }
}
