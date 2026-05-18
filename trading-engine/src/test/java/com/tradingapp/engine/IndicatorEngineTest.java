package com.tradingapp.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IndicatorEngineTest {

    private final IndicatorEngine engine = new IndicatorEngine();

    // RSI tests

    @Test
    void testRSI_BuySignal() {
        List<Double> prices = List.of(100.0,99.0,98.0,97.0,96.0,95.0,94.0,93.0,92.0,91.0,90.0,89.0,88.0,87.0,86.0);
        SignalResult result = engine.computeRSI(prices);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Steady decline should give BUY (RSI < 30)");
        assertTrue(result.getValue() < 30, "RSI should be below 30, got: " + result.getValue());
    }

    @Test
    void testRSI_SellSignal() {
        List<Double> prices = List.of(86.0,87.0,88.0,89.0,90.0,91.0,92.0,93.0,94.0,95.0,96.0,97.0,98.0,99.0,100.0);
        SignalResult result = engine.computeRSI(prices);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Steady rise should give SELL (RSI > 70)");
        assertTrue(result.getValue() > 70, "RSI should be above 70, got: " + result.getValue());
    }

    @Test
    void testRSI_InsufficientData() {
        List<Double> prices = List.of(100.0,99.0,98.0,97.0,96.0,95.0,94.0,93.0,92.0,91.0,90.0,89.0,88.0);
        SignalResult result = engine.computeRSI(prices);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "13 prices is insufficient for RSI-14");
    }

    // Bollinger Bands tests

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

    // MACD tests

    @Test
    void testMACD_BuySignal() {
        // 27 flat prices then 3 rising: momentum accelerates → MACD line rises above signal line
        List<Double> prices = new ArrayList<>(Collections.nCopies(27, 90.0));
        prices.add(91.0); prices.add(92.0); prices.add(93.0);
        SignalResult result = engine.computeMACD(prices);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "Accelerating upward momentum: MACD > signal → BUY");
    }

    @Test
    void testMACD_SellSignal() {
        // 27 flat prices then 3 falling: momentum decelerates → MACD line falls below signal line
        List<Double> prices = new ArrayList<>(Collections.nCopies(27, 90.0));
        prices.add(89.0); prices.add(88.0); prices.add(87.0);
        SignalResult result = engine.computeMACD(prices);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "Accelerating downward momentum: MACD < signal → SELL");
    }

    @Test
    void testMACD_InsufficientData() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 25; i++) prices.add(100.0);
        SignalResult result = engine.computeMACD(prices);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "25 prices is insufficient for MACD-26");
    }

    // MA crossover tests

    @Test
    void testMACrossover_BuySignal() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 150; i++) prices.add(50.0);
        for (int i = 0; i < 50; i++) prices.add(100.0);
        SignalResult result = engine.computeMACrossover(prices);
        assertEquals(SignalResult.Direction.BUY, result.getDirection(), "50-MA > 200-MA (golden cross) → BUY");
    }

    @Test
    void testMACrossover_SellSignal() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 150; i++) prices.add(100.0);
        for (int i = 0; i < 50; i++) prices.add(50.0);
        SignalResult result = engine.computeMACrossover(prices);
        assertEquals(SignalResult.Direction.SELL, result.getDirection(), "50-MA < 200-MA (death cross) → SELL");
    }

    @Test
    void testMACrossover_InsufficientData() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 199; i++) prices.add(100.0);
        SignalResult result = engine.computeMACrossover(prices);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "199 prices is insufficient for MA-200");
    }

    // Volume surge tests

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
        SignalResult result = engine.computeVolumeSurge(volumes, 1500.0, 2.0);
        assertEquals(SignalResult.Direction.NEUTRAL, result.getDirection(), "Volume below 2× avg → NEUTRAL");
    }

    // evaluateAll test

    @Test
    void testEvaluateAll_ReturnsAtLeastThreeSignals() {
        List<Double> prices = new ArrayList<>();
        for (int i = 70; i < 100; i++) prices.add((double) i);
        List<Double> volumes = new ArrayList<>(Collections.nCopies(25, 1000.0));
        List<SignalResult> results = engine.evaluateAll(prices, volumes, 100.0);
        assertTrue(results.size() >= 3, "Should return at least RSI, MACD, and Bollinger Bands signals");
    }
}
