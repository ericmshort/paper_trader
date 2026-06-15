package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.SignalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the newer strategy routing logic: OPENING_BREAKOUT,
 * STOCHASTIC_REVERSAL, RELATIVE_STRENGTH_DIVERGENCE, MACD_CROSSOVER.
 *
 * All tests use the in-process OptionsOrderExecutor (no Alpaca calls).
 * Run with: mvn test -pl trading-options -Dtest=OptionsNewStrategyTest
 */
public class OptionsNewStrategyTest {

    @TempDir
    Path tempDir;

    private static final String SYM = "SPY";
    private static final double PRICE = 560.0;

    // ─────────────────────────────────────────────────────────────────────────
    // OPENING_BREAKOUT
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void openingBreakoutCallOpensWithBreakoutCallKey() {
        OptionsSignalRouter router = buildRouter(inFirstHour(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("RSI", 1.0),
                        SignalResult.buy("MACD", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT should open " + SYM + "_BREAKOUT_CALL");
        assertFalse(account().getOptionsPositions().containsKey(SYM + "_CALL"),
                "Should use strategy-specific key, not generic _CALL");
    }

    @Test
    void openingBreakoutPutOpensWithBreakoutPutKey() {
        OptionsSignalRouter router = buildRouter(inFirstHour(), false); // downtrend — puts allowed
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));
        router.setUptrendSupplier(() -> false);
        router.setDowntrendPutMinSignals(2);

        router.evaluateWithSignals(SYM, PRICE, 0, 2, "sell", "",
                List.of(SignalResult.sell("ORB", 1.0),
                        SignalResult.sell("RSI", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_PUT"),
                "OPENING_BREAKOUT should open " + SYM + "_BREAKOUT_PUT");
    }

    @Test
    void openingBreakoutDoesNotFireAfterFirstHour() {
        OptionsSignalRouter router = buildRouter(afterFirstHour(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT must not fire after the 9:35-10:30 window");
    }

    @Test
    void openingBreakoutDoesNotFireWithoutOrbSignal() {
        OptionsSignalRouter router = buildRouter(inFirstHour(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));

        // 2 buy signals but no ORB indicator
        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("RSI", 1.0),
                        SignalResult.buy("MACD", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT requires an ORB signal to fire");
    }

    @Test
    void openingBreakoutDoesNotFireWithOnlyOneBuySignal() {
        OptionsSignalRouter router = buildRouter(inFirstHour(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));

        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT requires ORB + at least 2 confirming signals (buySignals >= 2)");
    }

    @Test
    void openingBreakoutCallBlockedInDowntrend() {
        OptionsSignalRouter router = buildRouter(inFirstHour(), false);
        router.setUptrendSupplier(() -> false); // confirmed downtrend
        // inDowntrend check inside router uses SPY-based supplier; calls are blocked in downtrend
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("RSI", 1.0),
                        SignalResult.buy("MACD", 1.0)));

        // Calls blocked in downtrend; puts need orbSell+sellSignals >= putMin
        assertFalse(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT CALL should be blocked in a confirmed SPY downtrend");
    }

    @Test
    void openingBreakoutDoesNotOpenWhenDirectionalAlreadyExists() {
        Account acct = new Account();
        acct.setBalance(100_000.0);
        TransactionLog log = new TransactionLog(tempDir.resolve("ob_dup.db").toString());
        OptionsSignalRouter router = buildRouterWith(acct, log, inFirstHour(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));

        // Open a directional position manually
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        LocalTime t = LocalTime.of(9, 45);
        double T = 5.0 / 365.0;
        double sigma = 0.15;
        new OptionsOrderExecutor(acct, log).buyCallAs(
                SYM + "_CALL", SYM, 560.0,
                nextFriday(), 1, bsEngine.callPrice(PRICE, 560.0, 0.045, T, sigma),
                "existing", "");

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertFalse(acct.getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT should not open a second directional when one already exists");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STOCHASTIC_REVERSAL
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void stochasticReversalCallOpensWithStochCallKey() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("STOCHASTIC_REVERSAL"));

        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("STOCHASTIC", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_STOCH_CALL"),
                "STOCHASTIC_REVERSAL should open " + SYM + "_STOCH_CALL");
    }

    @Test
    void stochasticReversalPutOpensWithStochPutKey() {
        OptionsSignalRouter router = buildRouter(midDay(), false);
        router.setUptrendSupplier(() -> false);
        router.setDowntrendPutMinSignals(1);
        router.setEnabledStrategies(Set.of("STOCHASTIC_REVERSAL"));

        router.evaluateWithSignals(SYM, PRICE, 0, 1, "sell", "",
                List.of(SignalResult.sell("STOCHASTIC", 1.0),
                        SignalResult.sell("RSI", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_STOCH_PUT"),
                "STOCHASTIC_REVERSAL should open " + SYM + "_STOCH_PUT");
    }

    @Test
    void stochasticReversalDoesNotFireWithoutStochasticSignal() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("STOCHASTIC_REVERSAL"));

        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("RSI", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_STOCH_CALL"),
                "STOCHASTIC_REVERSAL requires a STOCHASTIC indicator signal to fire");
    }

    @Test
    void stochasticReversalDoesNotFireWithoutAnyConfirmingSignal() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("STOCHASTIC_REVERSAL"));

        // buySignals=0 (stoch alone counts but stochBuy && buySignals >= 1 requires 1+)
        router.evaluateWithSignals(SYM, PRICE, 0, 0, "neutral", "",
                List.of(SignalResult.buy("STOCHASTIC", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_STOCH_CALL"),
                "STOCHASTIC_REVERSAL requires buySignals >= 1 as confirmation");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MACD_CROSSOVER
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void macdCrossoverCallOpensWithMacdCallKey() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("MACD_CROSSOVER"));

        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("MACD", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_MACD_CALL"),
                "MACD_CROSSOVER should open " + SYM + "_MACD_CALL");
    }

    @Test
    void macdCrossoverPutOpensWithMacdPutKey() {
        OptionsSignalRouter router = buildRouter(midDay(), false);
        router.setUptrendSupplier(() -> false);
        router.setDowntrendPutMinSignals(1);
        router.setEnabledStrategies(Set.of("MACD_CROSSOVER"));

        router.evaluateWithSignals(SYM, PRICE, 0, 1, "sell", "",
                List.of(SignalResult.sell("MACD", 1.0),
                        SignalResult.sell("RSI", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_MACD_PUT"),
                "MACD_CROSSOVER should open " + SYM + "_MACD_PUT");
    }

    @Test
    void macdCrossoverDoesNotFireWithoutMacdSignal() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("MACD_CROSSOVER"));

        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("RSI", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_MACD_CALL"),
                "MACD_CROSSOVER requires a MACD indicator signal to fire");
    }

    @Test
    void macdCrossoverDoesNotOpenWhenDirectionalAlreadyExists() {
        Account acct = new Account();
        acct.setBalance(100_000.0);
        TransactionLog log = new TransactionLog(tempDir.resolve("macd_dup.db").toString());
        OptionsSignalRouter router = buildRouterWith(acct, log, midDay(), true);
        router.setEnabledStrategies(Set.of("MACD_CROSSOVER"));

        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = 5.0 / 365.0;
        new OptionsOrderExecutor(acct, log).buyCallAs(
                SYM + "_CALL", SYM, 560.0, nextFriday(), 1,
                bsEngine.callPrice(PRICE, 560.0, 0.045, T, 0.15), "existing", "");

        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("MACD", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertFalse(acct.getOptionsPositions().containsKey(SYM + "_MACD_CALL"),
                "MACD_CROSSOVER should not open when a directional position already exists");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RELATIVE_STRENGTH_DIVERGENCE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rsDivergenceCallOpensWithRsCallKey() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("RELATIVE_STRENGTH_DIVERGENCE"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("RELATIVE_STRENGTH", 1.0),
                        SignalResult.buy("RSI", 1.0),
                        SignalResult.buy("MACD", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_RS_CALL"),
                "RS_DIVERGENCE should open " + SYM + "_RS_CALL");
    }

    @Test
    void rsDivergencePutOpensWithRsPutKey() {
        OptionsSignalRouter router = buildRouter(midDay(), false);
        router.setUptrendSupplier(() -> false);
        router.setDowntrendPutMinSignals(2);
        router.setEnabledStrategies(Set.of("RELATIVE_STRENGTH_DIVERGENCE"));

        router.evaluateWithSignals(SYM, PRICE, 0, 2, "sell", "",
                List.of(SignalResult.sell("RELATIVE_STRENGTH", 1.0),
                        SignalResult.sell("RSI", 1.0),
                        SignalResult.sell("MACD", 1.0)));

        assertTrue(account().getOptionsPositions().containsKey(SYM + "_RS_PUT"),
                "RS_DIVERGENCE should open " + SYM + "_RS_PUT");
    }

    @Test
    void rsDivergenceDoesNotFireWithoutRsSignal() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("RELATIVE_STRENGTH_DIVERGENCE"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("RSI", 1.0),
                        SignalResult.buy("MACD", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_RS_CALL"),
                "RS_DIVERGENCE requires a RELATIVE_STRENGTH indicator signal to fire");
    }

    @Test
    void rsDivergenceRequiresTwoConfirmingSignals() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        router.setEnabledStrategies(Set.of("RELATIVE_STRENGTH_DIVERGENCE"));

        // Only 1 buy signal total — RS_DIVERGENCE needs buySignals >= 2
        router.evaluateWithSignals(SYM, PRICE, 1, 0, "buy", "",
                List.of(SignalResult.buy("RELATIVE_STRENGTH", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_RS_CALL"),
                "RS_DIVERGENCE requires buySignals >= 2 as confirmation (not just RS alone)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Strategy mutual exclusion
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void onlyOneStrategyOpensPerTick() {
        // When multiple strategies have their signals, only the first one to fire should open
        OptionsSignalRouter router = buildRouter(inFirstHour(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT", "STOCHASTIC_REVERSAL", "MACD_CROSSOVER"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("STOCHASTIC", 1.0),
                        SignalResult.buy("MACD", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        long openCount = account().getOptionsPositions().values().stream()
                .filter(p -> SYM.equals(p.getSymbol()))
                .count();
        assertEquals(1, openCount,
                "Only one directional position should open per tick, even with multiple strategy signals active");
    }

    @Test
    void newStrategiesRespectDailyLossHalt() {
        Account acct = new Account();
        acct.setBalance(100_000.0);
        acct.setDailyLossHalted(true);
        TransactionLog log = new TransactionLog(tempDir.resolve("halt.db").toString());
        OptionsSignalRouter router = buildRouterWith(acct, log, midDay(), true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT", "STOCHASTIC_REVERSAL",
                "RELATIVE_STRENGTH_DIVERGENCE", "MACD_CROSSOVER"));

        router.evaluateWithSignals(SYM, PRICE, 3, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("STOCHASTIC", 1.0),
                        SignalResult.buy("MACD", 1.0),
                        SignalResult.buy("RELATIVE_STRENGTH", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertTrue(acct.getOptionsPositions().isEmpty(),
                "No position should open when dailyLossHalted=true");
    }

    @Test
    void newStrategiesRespectWhenDisabled() {
        OptionsSignalRouter router = buildRouter(midDay(), true);
        // Enable only legacy strategies — new ones should not fire
        router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT"));

        router.evaluateWithSignals(SYM, PRICE, 2, 0, "buy", "",
                List.of(SignalResult.buy("ORB", 1.0),
                        SignalResult.buy("STOCHASTIC", 1.0),
                        SignalResult.buy("MACD", 1.0),
                        SignalResult.buy("RELATIVE_STRENGTH", 1.0),
                        SignalResult.buy("RSI", 1.0)));

        assertFalse(account().getOptionsPositions().containsKey(SYM + "_BREAKOUT_CALL"),
                "OPENING_BREAKOUT should not fire when not in enabledStrategies");
        assertFalse(account().getOptionsPositions().containsKey(SYM + "_STOCH_CALL"),
                "STOCHASTIC_REVERSAL should not fire when not in enabledStrategies");
        assertFalse(account().getOptionsPositions().containsKey(SYM + "_MACD_CALL"),
                "MACD_CROSSOVER should not fire when not in enabledStrategies");
        assertFalse(account().getOptionsPositions().containsKey(SYM + "_RS_CALL"),
                "RS_DIVERGENCE should not fire when not in enabledStrategies");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    // Each test that calls buildRouter() gets its own fresh account/log
    private Account lastAccount;

    private Account account() { return lastAccount; }

    private OptionsSignalRouter buildRouter(ZonedDateTime clockTime, boolean uptrend) {
        Account acct = new Account();
        acct.setBalance(100_000.0);
        TransactionLog log = new TransactionLog(
                tempDir.resolve("test-" + System.nanoTime() + ".db").toString());
        return buildRouterWith(acct, log, clockTime, uptrend);
    }

    private OptionsSignalRouter buildRouterWith(Account acct, TransactionLog log,
                                                ZonedDateTime clockTime, boolean uptrend) {
        lastAccount = acct;
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(acct, log);

        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 40; i++) ph.record(SYM, i % 2 == 0 ? 558.0 : 562.0, 80_000_000.0);

        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, acct, ph, msgs::add);
        router.setClock(() -> clockTime);
        router.setUptrendSupplier(() -> uptrend);
        router.setAvoidOvernightHolds(false);
        router.setEntryCutoff(null);
        return router;
    }

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static ZonedDateTime inFirstHour() {
        // 9:45 ET — inside the 9:35-10:30 OPENING_BREAKOUT window
        return ZonedDateTime.now(ET).withHour(9).withMinute(45).withSecond(0).withNano(0);
    }

    private static ZonedDateTime afterFirstHour() {
        // 10:45 ET — outside the OPENING_BREAKOUT window
        return ZonedDateTime.now(ET).withHour(10).withMinute(45).withSecond(0).withNano(0);
    }

    private static ZonedDateTime midDay() {
        // 11:00 ET — standard mid-session time for other strategies
        return ZonedDateTime.now(ET).withHour(11).withMinute(0).withSecond(0).withNano(0);
    }

    private static java.time.LocalDate nextFriday() {
        java.time.LocalDate d = java.time.LocalDate.now().plusDays(7);
        while (d.getDayOfWeek() != java.time.DayOfWeek.FRIDAY) d = d.plusDays(1);
        return d;
    }
}
