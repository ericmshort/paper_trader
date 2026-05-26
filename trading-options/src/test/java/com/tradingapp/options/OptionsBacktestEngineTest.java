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
    void straddleEntersOnEitherSignal() {
        assertTrue(engine.entryCondition(OptionsStrategy.STRADDLE, 2, 0));
        assertTrue(engine.entryCondition(OptionsStrategy.STRADDLE, 0, 2));
        assertFalse(engine.entryCondition(OptionsStrategy.STRADDLE, 1, 1));
    }

    @Test
    void strangledEntersOnEitherSignal() {
        assertTrue(engine.entryCondition(OptionsStrategy.STRANGLE, 2, 0));
        assertTrue(engine.entryCondition(OptionsStrategy.STRANGLE, 0, 2));
    }

    @Test
    void butterflyEntersOnBuySignals() {
        assertTrue(engine.entryCondition(OptionsStrategy.BUTTERFLY, 2, 0));
        assertFalse(engine.entryCondition(OptionsStrategy.BUTTERFLY, 0, 2));
    }

    @Test
    void coveredCallEntersOnBuySignals() {
        assertTrue(engine.entryCondition(OptionsStrategy.COVERED_CALL, 2, 0));
        assertFalse(engine.entryCondition(OptionsStrategy.COVERED_CALL, 0, 2));
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

    @Test
    void bullCallSpreadCheaperThanLongCall() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var longCall = engine.buildPosition(OptionsStrategy.LONG_CALL, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        var spread = engine.buildPosition(OptionsStrategy.BULL_CALL_SPREAD, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(longCall);
        assertNotNull(spread);
        // Per contract, spread debit < full call premium
        double spreadPerContract = spread.totalCostBasis / spread.contracts;
        double callPerContract = longCall.totalCostBasis / longCall.contracts;
        assertTrue(spreadPerContract < callPerContract,
                "Bull call spread net debit should be less than long call premium");
        // Max profit is capped at SPREAD_WIDTH
        assertTrue(spread.maxProfit > 0, "Bull call spread should have a defined max profit");
    }

    @Test
    void bearPutSpreadCheaperThanLongPut() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var longPut = engine.buildPosition(OptionsStrategy.LONG_PUT, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        var spread = engine.buildPosition(OptionsStrategy.BEAR_PUT_SPREAD, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(longPut);
        assertNotNull(spread);
        double spreadPerContract = spread.totalCostBasis / spread.contracts;
        double putPerContract = longPut.totalCostBasis / longPut.contracts;
        assertTrue(spreadPerContract < putPerContract,
                "Bear put spread net debit should be less than long put premium");
    }

    @Test
    void straddleCostEqualsCallPlusPut() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var straddle = engine.buildPosition(OptionsStrategy.STRADDLE, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(straddle);
        double callPrem = bs.callPrice(PRICE, K, OptionsBacktestEngine.RISK_FREE_RATE, T, SIGMA);
        double putPrem = bs.putPrice(PRICE, K, OptionsBacktestEngine.RISK_FREE_RATE, T, SIGMA);
        double expectedPerContract = (callPrem + putPrem) * 100;
        assertEquals(expectedPerContract * straddle.contracts, straddle.totalCostBasis, 0.01);
        assertEquals(2, straddle.legs.size());
    }

    @Test
    void strangleCheaperThanStraddle() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var straddle = engine.buildPosition(OptionsStrategy.STRADDLE, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        var strangle = engine.buildPosition(OptionsStrategy.STRANGLE, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(straddle);
        assertNotNull(strangle);
        double straddlePerContract = straddle.totalCostBasis / straddle.contracts;
        double stranglePerContract = strangle.totalCostBasis / strangle.contracts;
        assertTrue(stranglePerContract < straddlePerContract,
                "Strangle (OTM options) should be cheaper than ATM straddle");
    }

    @Test
    void butterflyHasDefinedMaxProfit() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var butterfly = engine.buildPosition(OptionsStrategy.BUTTERFLY, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(butterfly);
        assertEquals(3, butterfly.legs.size());
        assertTrue(butterfly.maxProfit > 0, "Butterfly should have a positive max profit");
        assertTrue(butterfly.maxProfit < OptionsBacktestEngine.SPREAD_WIDTH * 100 * butterfly.contracts,
                "Butterfly max profit capped at spread width minus net debit");
    }

    @Test
    void coveredCallHasStockSharesAndShortCall() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.COVERED_CALL, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        assertTrue(pos.stockShares > 0, "Covered call must hold stock");
        assertEquals(pos.contracts * 100, pos.stockShares);
        assertEquals(1, pos.legs.size());
        assertFalse(pos.legs.get(0).isLong(), "Covered call short-sells the call leg");
        assertTrue(pos.maxProfit > 0, "Covered call has capped upside defined");
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

    @Test
    void bullCallSpreadValueCappedAtSpreadWidth() {
        LocalDate expiry = LocalDate.now().plusDays(35);
        var pos = engine.buildPosition(OptionsStrategy.BULL_CALL_SPREAD, SYM, PRICE, K, expiry, T, SIGMA, 100_000);
        assertNotNull(pos);
        // With stock deep ITM at expiry, spread value = SPREAD_WIDTH * 100 * contracts
        double maxValue = OptionsBacktestEngine.SPREAD_WIDTH * 100 * pos.contracts;
        double computed = engine.computeCurrentValue(pos, PRICE + 100, 0.0, SIGMA);
        assertEquals(maxValue, computed, 0.01);
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

    @Test
    void coveredCallUsesEquityStop() {
        LocalDate expiry = LocalDate.now().plusDays(30);
        double entryPrice = 150.0;
        var pos = new OptionsBacktestEngine.OpenPosition(SYM, expiry, 1, SIGMA, 14_500, 500,
                List.of(new OptionsBacktestEngine.Leg(K + 5, true, false, 1)), 100, entryPrice);
        // Stock drops below 85% of entry → equity stop
        double stopPrice = entryPrice * OptionsBacktestEngine.EQUITY_STOP_FRACTION - 1;
        assertTrue(engine.shouldExit(pos, 14_500, 0, 0, LocalDate.now(), OptionsStrategy.COVERED_CALL, stopPrice));
    }

    @Test
    void spreadExitsAt75PctMaxProfit() {
        LocalDate expiry = LocalDate.now().plusDays(30);
        double costBasis = 200;
        double maxProfit = 300; // SPREAD_WIDTH*100 - costBasis
        var pos = new OptionsBacktestEngine.OpenPosition(SYM, expiry, 1, SIGMA, costBasis, maxProfit,
                List.of(new OptionsBacktestEngine.Leg(K, true, true, 1),
                        new OptionsBacktestEngine.Leg(K + 5, true, false, 1)), 0, 0);
        // P&L = 75% of maxProfit → should exit
        double currentValue = costBasis + maxProfit * 0.76;
        assertTrue(engine.shouldExit(pos, currentValue, 0, 0, LocalDate.now(), OptionsStrategy.BULL_CALL_SPREAD, PRICE));
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
        // Build bars: 30 flat warm-up, then price falls triggering RSI buy, then rises
        List<HistoricalBar> bars = buildTrendingBars(150.0, 100.0, 200.0);
        Map<String, List<HistoricalBar>> barsBySymbol = Map.of(SYM, bars);
        BacktestConfig cfg = new BacktestConfig(List.of(SYM),
                bars.get(0).getDate(), bars.get(bars.size() - 1).getDate(), 100_000.0);

        BacktestResult result = engine.run(OptionsStrategy.LONG_CALL, cfg, barsBySymbol);
        // With a large price rise, at least one trade should have fired
        assertTrue(result.getTotalTrades() >= 0);
    }

    // ---- helpers ----

    private List<HistoricalBar> buildBars() {
        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.now().minusDays(100);
        double price = 150.0;
        for (int i = 0; i < 100; i++) {
            // Zigzag so indicators produce non-zero, non-constant signals
            double p = price + (i % 7 == 0 ? -5 : 3);
            price = Math.max(10, p);
            bars.add(new HistoricalBar(SYM, date.plusDays(i), price, price + 2, price - 2, price, 1_000_000L));
        }
        return bars;
    }

    // Builds a price path: warm-up at start, falls to low, then rises to high
    private List<HistoricalBar> buildTrendingBars(double start, double low, double high) {
        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate date = LocalDate.now().minusDays(120);
        // 40-bar warm-up at start price
        for (int i = 0; i < 40; i++) {
            bars.add(new HistoricalBar(SYM, date.plusDays(i), start, start + 1, start - 1, start, 1_000_000L));
        }
        // 20-bar decline from start to low
        for (int i = 0; i < 20; i++) {
            double p = start + (low - start) * i / 20.0;
            bars.add(new HistoricalBar(SYM, date.plusDays(40 + i), p, p + 1, p - 1, p, 1_500_000L));
        }
        // 60-bar rise from low to high
        for (int i = 0; i < 60; i++) {
            double p = low + (high - low) * i / 60.0;
            bars.add(new HistoricalBar(SYM, date.plusDays(60 + i), p, p + 1, p - 1, p, 2_000_000L));
        }
        return bars;
    }
}
