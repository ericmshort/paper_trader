package com.tradingapp.options;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class BlackScholesEngineTest {

    private final BlackScholesEngine bs = new BlackScholesEngine();

    // Known reference: S=100, K=100, r=0.04, T=0.5, sigma=0.2
    private static final double S = 100, K = 100, R = 0.04, T = 0.5, SIGMA = 0.2;

    @Test
    void testCallPriceKnownValue() {
        // S=100, K=100, r=0.04, T=0.5, sigma=0.2:
        // d1=0.2121, d2=0.0707 → C = 100*N(0.2121) - 100*e^-0.02*N(0.0707) ≈ 6.63
        double call = bs.callPrice(S, K, R, T, SIGMA);
        assertEquals(6.63, call, 0.01, "Call price should be ~6.63, got: " + call);
    }

    @Test
    void testPutPriceKnownValue() {
        // Put-call parity: P = C - S + K*exp(-r*T) ≈ 6.63 - 100 + 100*e^-0.02 ≈ 4.64
        double expectedPut = bs.callPrice(S, K, R, T, SIGMA) - S + K * Math.exp(-R * T);
        double put = bs.putPrice(S, K, R, T, SIGMA);
        assertEquals(expectedPut, put, 0.01, "Put price should satisfy put-call parity");
    }

    @Test
    void testCallDeltaRange() {
        GreeksResult g = bs.greeks(S, K, R, T, SIGMA, true);
        assertTrue(g.delta >= 0.0 && g.delta <= 1.0, "Call delta must be in [0,1], got: " + g.delta);
    }

    @Test
    void testGammaNonNegative() {
        GreeksResult g = bs.greeks(S, K, R, T, SIGMA, true);
        assertTrue(g.gamma >= 0.0, "Gamma must be >= 0, got: " + g.gamma);
    }

    @Test
    void testThetaNonPositive() {
        GreeksResult g = bs.greeks(S, K, R, T, SIGMA, true);
        assertTrue(g.theta <= 0.0, "Theta must be <= 0 for long call, got: " + g.theta);
    }

    @Test
    void testVegaNonNegative() {
        GreeksResult g = bs.greeks(S, K, R, T, SIGMA, true);
        assertTrue(g.vega >= 0.0, "Vega must be >= 0, got: " + g.vega);
    }

    @Test
    void testHistoricalVolFlatPrices() {
        List<Double> flat = Collections.nCopies(20, 150.0);
        assertEquals(0.0, bs.historicalVol(flat), "Flat prices should give 0 vol");
    }

    @Test
    void testHistoricalVolPositive() {
        List<Double> prices = IntStream.range(0, 21)
                .mapToDouble(i -> 100.0 + i * 0.5 + (i % 3 == 0 ? -0.3 : 0.3))
                .boxed().collect(Collectors.toList());
        assertTrue(bs.historicalVol(prices) > 0, "Varying prices should give positive vol");
    }

    @Test
    void testRoundStrikeLower() {
        assertEquals(145.0, bs.roundStrike(147.3), 0.001, "147.3 should round to 145");
    }

    @Test
    void testRoundStrikeUpper() {
        assertEquals(150.0, bs.roundStrike(147.6), 0.001, "147.6 should round to 150");
    }

    @Test
    void testNextMonthlyExpiryIsThirdFriday() {
        LocalDate expiry = bs.nextMonthlyExpiry();
        assertEquals(expiry.getDayOfWeek(), java.time.DayOfWeek.FRIDAY, "Expiry must be a Friday");
        int dom = expiry.getDayOfMonth();
        assertTrue(dom >= 15 && dom <= 21, "Third Friday must fall on day 15–21, got: " + dom);
    }

    @Test
    void testNextMonthlyExpiryAtLeast14DaysOut() {
        LocalDate expiry = bs.nextMonthlyExpiry();
        assertTrue(!expiry.isBefore(LocalDate.now().plusDays(14)),
                "Expiry must be at least 14 days from today, got: " + expiry);
    }
}
