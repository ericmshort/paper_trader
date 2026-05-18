package com.tradingapp.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FeeCalculatorTest {

    private final FeeCalculator calc = new FeeCalculator();

    @Test
    void testFeePerShare() {
        assertEquals(1.00, calc.calculateFee(100), 0.001);
    }

    @Test
    void testFeeZeroShares() {
        assertEquals(0.0, calc.calculateFee(0), 0.001);
    }

    @Test
    void testMaxShares_FivePercent() {
        assertEquals(25, calc.maxShares(100000.0, 200.0));
    }

    @Test
    void testMaxShares_SmallBalance() {
        assertEquals(0, calc.maxShares(500.0, 300.0));
    }

    @Test
    void testMaxShares_RoundsDown() {
        assertEquals(1, calc.maxShares(10000.0, 333.0));
    }
}
