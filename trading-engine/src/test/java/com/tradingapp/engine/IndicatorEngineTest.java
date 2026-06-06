package com.tradingapp.engine;

import com.tradingapp.data.CandleBar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IndicatorEngineTest {

    private final IndicatorEngine engine = new IndicatorEngine();

    // ── RSI (period=9) ──────────────────────────────────────────────────────────

    @Test
    void testRSI_BuySignal() {
        // 10 declining prices → RSI well below 35
        List<Double> prices = List.of(100.0,99.0,98.0,97.0,96.0,95.0,94.0,93.0,92.0,91.0,90.0);
        SignalResult result = engine.computeRSI(prices);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Steady decline should give BUY (RSI < 35)");
    }

    @Test
    void testRSI_SellSignal() {
        List<Double> prices = List.of(90.0,91.0,92.0,93.0,94.0,95.0,96.0,97.0,98.0,99.0,100.0);
        SignalResult result = engine.computeRSI(prices);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Steady rise should give SELL (RSI > 65)");
    }

    @Test
    void testRSI_InsufficientData() {
        List<Double> prices = List.of(100.0,99.0,98.0,97.0,96.0,95.0,94.0,93.0,92.0);
        SignalResult result = engine.computeRSI(prices);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "9 prices is insufficient for RSI-9");
    }

    // ── Bollinger Bands ─────────────────────────────────────────────────────────

    @Test
    void testBollingerBands_BuySignal() {
        List<Double> prices = Collections.nCopies(20, 100.0);
        SignalResult result = engine.computeBollingerBands(prices, 94.0);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Price below lower band should be BUY");
    }

    @Test
    void testBollingerBands_SellSignal() {
        List<Double> prices = Collections.nCopies(20, 100.0);
        SignalResult result = engine.computeBollingerBands(prices, 106.0);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Price above upper band should be SELL");
    }

    @Test
    void testBollingerBands_InsufficientData() {
        List<Double> prices = Collections.nCopies(19, 100.0);
        SignalResult result = engine.computeBollingerBands(prices, 100.0);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "19 prices is insufficient for BB-20");
    }

    // ── Volume Surge ────────────────────────────────────────────────────────────

    @Test
    void testVolumeSurge_Buy() {
        List<Double> volumes = new ArrayList<>(Collections.nCopies(20, 1000.0));
        SignalResult result = engine.computeVolumeSurge(volumes, 2500.0, 2.0);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Volume surge + price up → BUY");
    }

    @Test
    void testVolumeSurge_Sell() {
        List<Double> volumes = new ArrayList<>(Collections.nCopies(20, 1000.0));
        SignalResult result = engine.computeVolumeSurge(volumes, 2500.0, -2.0);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Volume surge + price down → SELL");
    }

    @Test
    void testVolumeSurge_NoSurge() {
        List<Double> volumes = new ArrayList<>(Collections.nCopies(20, 1000.0));
        SignalResult result = engine.computeVolumeSurge(volumes, 1400.0, 2.0);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "Volume below 1.5× avg → NEUTRAL");
    }

    // ── VWAP ────────────────────────────────────────────────────────────────────

    @Test
    void testVwap_BuySignal() {
        // Single bar: VWAP = 100, current price = 101.5 (>0.1% above)
        CandleBar bar = makeBar(100.0, 100.0);
        SignalResult result = engine.computeVwap(List.of(bar), 101.5);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Price > VWAP → BUY");
    }

    @Test
    void testVwap_SellSignal() {
        CandleBar bar = makeBar(100.0, 100.0);
        SignalResult result = engine.computeVwap(List.of(bar), 98.5);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Price < VWAP → SELL");
    }

    @Test
    void testVwap_EmptyBars() {
        SignalResult result = engine.computeVwap(List.of(), 100.0);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "No bars → NEUTRAL");
    }

    // ── ORB ─────────────────────────────────────────────────────────────────────

    @Test
    void testOrb_BuyBreakout() {
        CandleBar orb  = makeBarOHLC(100.0, 105.0, 99.0, 102.0);
        CandleBar bar2 = makeBarOHLC(102.0, 103.0, 101.0, 103.0);
        SignalResult result = engine.computeOrb(List.of(orb, bar2), 106.0);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Above ORB high → BUY");
    }

    @Test
    void testOrb_SellBreakdown() {
        CandleBar orb  = makeBarOHLC(100.0, 105.0, 99.0, 102.0);
        CandleBar bar2 = makeBarOHLC(102.0, 103.0, 101.0, 101.0);
        SignalResult result = engine.computeOrb(List.of(orb, bar2), 98.0);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Below ORB low → SELL");
    }

    @Test
    void testOrb_FormationPeriod() {
        CandleBar orb = makeBarOHLC(100.0, 105.0, 99.0, 102.0);
        SignalResult result = engine.computeOrb(List.of(orb), 106.0);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "Only 1 bar — range still forming → NEUTRAL");
    }

    // ── Candlestick patterns ─────────────────────────────────────────────────────

    @Test
    void testBullishEngulfing() {
        CandleBar bearCandle = makeBarOHLC(105.0, 106.0, 100.0, 101.0); // bearish
        CandleBar bullCandle = makeBarOHLC(99.0,  107.0, 98.0,  107.0); // bullish, engulfs
        SignalResult result = engine.computeCandlestickPatterns(List.of(bearCandle, bullCandle));
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Bullish engulfing → BUY");
    }

    @Test
    void testBearishEngulfing() {
        CandleBar bullCandle = makeBarOHLC(100.0, 106.0, 99.0,  105.0); // bullish
        CandleBar bearCandle = makeBarOHLC(106.0, 107.0, 98.0,  98.5);  // bearish, engulfs
        SignalResult result = engine.computeCandlestickPatterns(List.of(bullCandle, bearCandle));
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Bearish engulfing → SELL");
    }

    @Test
    void testCandlestick_InsufficientData() {
        SignalResult result = engine.computeCandlestickPatterns(List.of());
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "No bars → NEUTRAL");
    }

    // ── evaluateAll ──────────────────────────────────────────────────────────────

    @Test
    void testEvaluateAll_ReturnsAtLeastThreeSignals() {
        List<Double> prices = new ArrayList<>();
        for (int i = 70; i < 100; i++) prices.add((double) i);
        List<Double> volumes = new ArrayList<>(Collections.nCopies(25, 1000.0));
        List<SignalResult> results = engine.evaluateAll(prices, volumes, 100.0);
        assertTrue(results.size() >= 2, "Should return at least RSI and Bollinger Bands signals");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private CandleBar makeBar(double price, double volume) {
        return new CandleBar("TEST", Instant.now(), CandleBar.Interval.FIVE_MIN, price, volume);
    }

    private CandleBar makeBarOHLC(double open, double high, double low, double close) {
        CandleBar bar = new CandleBar("TEST", Instant.now(), CandleBar.Interval.FIVE_MIN, open, 1000.0);
        bar.update(high,  100.0);
        bar.update(low,   100.0);
        bar.update(close, 100.0);
        return bar;
    }
}
