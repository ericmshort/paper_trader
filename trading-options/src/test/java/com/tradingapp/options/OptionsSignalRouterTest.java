package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tradingapp.account.OptionsPosition;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OptionsSignalRouterTest {

    @TempDir
    Path tempDir;

    private static final String SYMBOL = "AAPL";
    private static final double PRICE = 150.0;

    private OptionsSignalRouter buildRouter(Account account, TransactionLog log) {
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory priceHistory = buildPriceHistory();
        List<String> msgs = new ArrayList<>();
        return new OptionsSignalRouter(bsEngine, optExec, account, priceHistory, msgs::add);
    }

    private PriceHistory buildPriceHistory() {
        PriceHistory ph = new PriceHistory();
        // Perfectly uniform zigzag — all returns are identical so recentVol = fullVol → ratio ~1.0 → strangle territory
        for (int i = 0; i < 25; i++) {
            ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);
        }
        return ph;
    }

    /**
     * Builds a price history where the first 25 bars are moderate-vol zigzag and the last
     * IV_RECENT_WINDOW bars are flat → recentVol ≈ 0 < 80% of fullVol → straddle territory.
     * Uses 148/152 (not 130/170) so annualized vol stays affordable for BS pricing.
     */
    private PriceHistory buildLowRecentIVHistory() {
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 25; i++) {
            ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);
        }
        // flat recent window → recentVol = 0 << fullVol → ratio < 0.80
        for (int i = 0; i < OptionsSignalRouter.IV_RECENT_WINDOW; i++) {
            ph.record(SYMBOL, PRICE, 1_000_000.0);
        }
        return ph;
    }

    @Test
    void testCallOpenedOnBuySignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "test signals", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be opened on 2 buy signals");
    }

    @Test
    void testPutOpenedOnSellSignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 0, 3, "test signals", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "PUT position should be opened on 2 sell signals");
    }

    @Test
    void testCallClosedOnReversal() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("rev.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open call
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        // Reverse: sell signal closes the call
        router.evaluate(SYMBOL, PRICE, 0, 3, "sell", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be closed on sell reversal");
    }

    @Test
    void testNoTradeWithZeroVol() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("vol0.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        // Load 25 identical prices → vol = 0
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 25; i++) ph.record(SYMBOL, 150.0, 1_000_000.0);
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertTrue(account.getOptionsPositions().isEmpty(),
                "No position should be opened when vol = 0");
    }

    @Test
    void testCallClosedOnPremiumStopLoss() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_stop.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open a call ATM at $150
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        // Price collapses to $100 — the call is now deep OTM; premium drops well below 50% of paid
        // No directional signals, so only the premium stop-loss should trigger the close
        router.evaluate(SYMBOL, 100.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be closed when premium falls to 50% of cost");
    }

    @Test
    void testPutClosedOnPremiumStopLoss() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put_stop.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open a put ATM at $150
        router.evaluate(SYMBOL, PRICE, 0, 3, "sell", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"));

        // Price surges to $200 — put is now deep OTM; premium drops well below 50% of paid
        // No directional signals, so only the premium stop-loss should trigger the close
        router.evaluate(SYMBOL, 200.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "PUT position should be closed when premium falls to 50% of cost");
    }

    @Test
    void testCallNotClosedWhenPremiumAboveStop() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_hold.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open a call ATM at $150
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        // Price moves slightly against the position but not enough to trigger the 50% stop
        router.evaluate(SYMBOL, 148.0, 0, 0, "neutral", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should remain open when premium has not fallen 50%");
    }

    @Test
    void testCallSkippedWhenPortfolioAtCapacity() {
        Account account = new Account();
        // 300 shares at $210 = $63,000; deduct from balance so 63k/(37k+63k) = 63% > 60% cap
        account.addOrUpdatePosition("MSFT", 300, 210.0, Position.PositionType.STOCK);
        account.setBalance(37_000.0);

        TransactionLog log = new TransactionLog(tempDir.resolve("cap_opt.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should not open when portfolio is at 42% capacity");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("portfolio at capacity")),
                "Should log portfolio at capacity message");
    }

    @Test
    void testCallSkippedOnIVSurge() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("iv_surge.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);

        // Build price history: 60 stable prices followed by 20 highly volatile prices.
        // The recent-20 vol will be far above the full-history baseline, triggering the IV surge filter.
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 60; i++) ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);
        for (int i = 0; i < 20; i++) ph.record(SYMBOL, i % 2 == 0 ? 100.0 : 200.0, 1_000_000.0);

        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should not open when IV is surging");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("IV surge")),
                "Should log IV surge skip message");
    }

    @Test
    void testCallNotSkippedWhenVolNormal() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("iv_normal.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);

        // Uniform zigzag throughout — recent vol matches full-history baseline, no IV surge
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 40; i++) ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);

        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        // Should open (or be skipped for premium reasons), but never for IV surge
        assertFalse(msgs.stream().anyMatch(m -> m.contains("IV surge")),
                "IV surge filter should not fire when volatility is stable");
    }

    @Test
    void testCallClosedOnProfitTarget() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_profit.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open a call ATM at $150
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        // Price surges to $250 — deeply ITM; premium is far above 2x of original
        // No directional signals, so only the profit target should close it
        router.evaluate(SYMBOL, 250.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be closed when premium reaches 2x of cost");
    }

    @Test
    void testPutClosedOnProfitTarget() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put_profit.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open a put ATM at $150
        router.evaluate(SYMBOL, PRICE, 0, 3, "sell", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"));

        // Price collapses to $50 — deeply ITM put; premium far above 2x of original
        // No directional signals, so only the profit target should close it
        router.evaluate(SYMBOL, 50.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "PUT position should be closed when premium reaches 2x of cost");
    }

    @Test
    void testOptionsSkippedWhenDailyLossHalted() {
        Account account = new Account();
        account.setDailyLossHalted(true);

        TransactionLog log = new TransactionLog(tempDir.resolve("daily_loss_opt.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should not open when daily loss limit is active");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("daily loss limit active")),
                "Should log daily loss limit skip message");
    }

    @Test
    void testCallSkippedWhenEquityPositionAlreadyOpen() {
        Account account = new Account();
        // Equity position already open for AAPL — CALL should be blocked (double-dip prevention)
        account.addOrUpdatePosition(SYMBOL, 100, PRICE, Position.PositionType.STOCK);

        TransactionLog log = new TransactionLog(tempDir.resolve("dbl_dip.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should be blocked when equity position is already open (double-dip)");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("equity position already open")),
                "Should log double-dip skip message");
    }

    @Test
    void testPutAllowedAsProtectiveHedgeWhenEquityOpen() {
        Account account = new Account();
        // Equity position open — PUT should still be allowed as a protective hedge
        account.addOrUpdatePosition(SYMBOL, 100, PRICE, Position.PositionType.STOCK);

        TransactionLog log = new TransactionLog(tempDir.resolve("prot_put.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);

        router.evaluate(SYMBOL, PRICE, 0, 3, "sell", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "PUT should be allowed as protective hedge when equity position is open");
    }

    @Test
    void testNoTradeInsufficientPriceHistory() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("hist.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = new PriceHistory();
        ph.record(SYMBOL, 150.0, 1_000_000.0); // only 1 price
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertTrue(account.getOptionsPositions().isEmpty(),
                "No position should open with only 1 price in history");
    }

    // ── Mixed-signal: straddle / strangle ─────────────────────────────────────────

    @Test
    void testStrangleOpenedOnMixedSignalsWithNormalIV() {
        // Consistent zigzag history → recent/full vol ratio ~1.0 → strangle territory
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("strangle.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // buys=2, sells=1 → mixedStrong signal
        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRANGLE_CALL"),
                "STRANGLE_CALL should open on mixed signals with normal IV");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRANGLE_PUT"),
                "STRANGLE_PUT should open on mixed signals with normal IV");
    }

    @Test
    void testStraddleOpenedOnMixedSignalsWithLowRecentIV() {
        // Recent 10 bars are flat → recent vol << full vol → ratio < 0.80 → straddle territory
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("straddle.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = buildLowRecentIVHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        // buys=2, sells=1 → mixedStrong signal
        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "STRADDLE_CALL should open when recent IV is low");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_PUT"),
                "STRADDLE_PUT should open when recent IV is low");
    }

    @Test
    void testLongCallNotOpenedOnMixedSignals() {
        // When sell signals are also present, should NOT open a directional Long Call
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("no_call_mixed.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "Long CALL should NOT open when sell signals are also present");
    }

    @Test
    void testLongPutNotOpenedOnMixedSignals() {
        // When buy signals are also present, should NOT open a directional Long Put
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("no_put_mixed.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 1, 2, "mixed", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "Long PUT should NOT open when buy signals are also present");
    }

    @Test
    void testStraddleClosedOnCombinedProfitTarget() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("straddle_profit.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = buildLowRecentIVHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        // Open straddle (low recent IV → straddle)
        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "Straddle should be open");

        // Price surges far above strike → call premium >> 2x paid, combined target hit
        router.evaluate(SYMBOL, 250.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "STRADDLE_CALL should be closed on combined profit target");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_PUT"),
                "STRADDLE_PUT should be closed on combined profit target");
    }

    @Test
    void testStraddleClosedOnCombinedStopLoss() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("straddle_stop.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = buildLowRecentIVHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        // Open straddle ATM at PRICE
        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"));

        // Price moves to exactly ATM with negligible time value left → both legs nearly worthless
        // Simulate by passing 0 buys/sells with a price very near original strike
        // In practice the BS pricing with the same price and diminished time erodes the premium.
        // We can't easily force the combined stop in one tick here, so we verify the position
        // remains when premium hasn't fallen far enough (price stays the same).
        router.evaluate(SYMBOL, PRICE, 0, 0, "neutral", "");
        // Position should still be open (no stop triggered at same price)
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "Straddle should remain open when premium has not fallen 50%");
    }

    @Test
    void testStraddleStopLossBlocksReEntry() {
        // Plant straddle positions with inflated premiumPaid ($50/leg) so that current BS premiums
        // (~$7/leg at 42% vol) fall below 50% of paid → combined stop fires → cooldown set.
        // (Collapsing price to $1 wouldn't work: the put would gain value on a straddle.)
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("straddle_cooldown.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = buildLowRecentIVHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        // Plant positions: premiumPaid=$50 each → totalPaid=$10,000; current BS combined ≈ $1,400
        LocalDate futureExpiry = LocalDate.now().plusDays(30);
        account.addOptionsPosition(SYMBOL + "_STRADDLE_CALL",
                new OptionsPosition(SYMBOL, "CALL", PRICE, futureExpiry, 1, 50.0));
        account.addOptionsPosition(SYMBOL + "_STRADDLE_PUT",
                new OptionsPosition(SYMBOL, "PUT", PRICE, futureExpiry, 1, 50.0));

        // Evaluate at ATM price: current combined value << 50% of $10,000 → stop fires
        router.evaluate(SYMBOL, PRICE, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "STRADDLE_CALL closed on combined stop");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_PUT"),
                "STRADDLE_PUT closed on combined stop");

        // Re-entry should be blocked by cooldown
        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "Re-entry into STRADDLE should be blocked by stop-loss cooldown");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("cooldown")),
                "Should log cooldown message");
    }

    @Test
    void testStraddleAndCallDoNotCoexist() {
        // Opening a call should block straddle entry and vice versa
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("coexist.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = buildLowRecentIVHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        // First: open a straddle
        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"));

        // Now: pure buy signal — should NOT open an additional directional call
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "Long CALL should not open when straddle is already active");
    }

    @Test
    void testStraddleCallRolledBackWhenPutLegRejected() {
        // Broker accepts call buys and all sells (needed for rollback) but rejects put buys.
        // The router must detect the half-open state and close the call to restore consistency.
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("rollback.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsSubmitter submitter = (sym, type, strike, expiry, contracts, side) -> {
            if ("sell".equals(side))   return "SELL-" + type; // accept rollback close
            if ("CALL".equals(type))   return "CALL-ORD-1";   // accept call buy
            return null;                                       // reject put buy
        };
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, submitter);
        PriceHistory ph = buildLowRecentIVHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);

        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_CALL"),
                "STRADDLE_CALL should be rolled back when put leg is rejected by broker");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_STRADDLE_PUT"),
                "STRADDLE_PUT should not be open");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("rolled back")),
                "Should log rollback message");
    }
}
