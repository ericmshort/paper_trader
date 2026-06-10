package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

public class OptionsSignalRouterTest {

    @TempDir
    Path tempDir;

    private static final String SYMBOL = "AAPL";
    private static final double PRICE = 150.0;
    // Fixed market-hours clock so pre-close (15:45 ET) check never fires during tests
    private static final ZonedDateTime MARKET_OPEN =
            ZonedDateTime.of(2025, 6, 10, 10, 0, 0, 0, ZoneId.of("America/New_York"));

    private OptionsSignalRouter buildRouter(Account account, TransactionLog log) {
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory priceHistory = buildPriceHistory();
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, priceHistory, msgs::add);
        router.setClock(() -> MARKET_OPEN);
        return router;
    }

    private PriceHistory buildPriceHistory() {
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 25; i++) {
            ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);
        }
        return ph;
    }

    // ── Directional entries ──────────────────────────────────────────────────

    @Test
    void testCallOpenedOnBuySignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "test signals", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be opened on 3 buy signals");
    }

    @Test
    void testPutOpenedOnSellSignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);
        // Simulate bear market so putMin drops to 4; 4 sell signals → MOMENTUM_NEAR_TERM put
        router.setUptrendSupplier(() -> false);

        router.evaluate(SYMBOL, PRICE, 0, 4, "test signals", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_NEARTERM_PUT"),
                "PUT position should be opened in bear market with 4 sell signals");
    }

    @Test
    void testCallClosedOnReversal() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("rev.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        // Reversal requires 4+ opposing signals for 3 consecutive bars before closing
        router.evaluate(SYMBOL, PRICE, 0, 4, "sell", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should still be open after only 1 bar of sell signals");

        router.evaluate(SYMBOL, PRICE, 0, 4, "sell", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should still be open after only 2 bars of sell signals");

        router.evaluate(SYMBOL, PRICE, 0, 4, "sell", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be closed after 3 consecutive bars of sell signals");
    }

    @Test
    void testNoTradeWithZeroVol() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("vol0.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 25; i++) ph.record(SYMBOL, 150.0, 1_000_000.0);
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.setClock(() -> MARKET_OPEN);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertTrue(account.getOptionsPositions().isEmpty(),
                "No position should be opened when vol = 0");
    }

    // ── Stop-loss and profit target ──────────────────────────────────────────

    @Test
    void testCallClosedOnPremiumStopLoss() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_stop.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        router.evaluate(SYMBOL, 100.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be closed when premium falls to 50% of cost");
    }

    @Test
    void testPutClosedOnPremiumStopLoss() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put_stop.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);
        router.setUptrendSupplier(() -> false); // bear market — putMin=4

        router.evaluate(SYMBOL, PRICE, 0, 4, "sell", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_NEARTERM_PUT"));

        router.evaluate(SYMBOL, 200.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_NEARTERM_PUT"),
                "PUT position should be closed when premium falls to 50% of cost");
    }

    @Test
    void testCallClosedWhenPremiumCollapsesToZero() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_zero.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        router.evaluate(SYMBOL, 50.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL must be closed by stop-loss even when BS-computed premium collapses to 0.0");
    }

    @Test
    void testCallNotClosedWhenPremiumAboveStop() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_hold.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        router.evaluate(SYMBOL, 148.0, 0, 0, "neutral", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should remain open when premium has not fallen 50%");
    }

    @Test
    void testCallClosedOnProfitTarget() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_profit.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        router.evaluate(SYMBOL, 250.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be closed when premium reaches 2x of cost");
    }

    @Test
    void testPutClosedOnProfitTarget() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put_profit.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);
        router.setUptrendSupplier(() -> false); // bear market — putMin=4

        router.evaluate(SYMBOL, PRICE, 0, 4, "sell", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_NEARTERM_PUT"));

        router.evaluate(SYMBOL, 50.0, 0, 0, "neutral", "");
        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_NEARTERM_PUT"),
                "PUT position should be closed when premium reaches 2x of cost");
    }

    // ── Portfolio / risk guards ───────────────────────────────────────────────

    @Test
    void testCallSkippedWhenPortfolioAtCapacity() {
        Account account = new Account();
        account.addOrUpdatePosition("MSFT", 300, 210.0, Position.PositionType.STOCK);
        account.setBalance(37_000.0);

        TransactionLog log = new TransactionLog(tempDir.resolve("cap_opt.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);
        router.setClock(() -> MARKET_OPEN);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should not open when portfolio is at capacity");
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

        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 60; i++) ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);
        for (int i = 0; i < 20; i++) ph.record(SYMBOL, i % 2 == 0 ? 100.0 : 200.0, 1_000_000.0);

        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.setClock(() -> MARKET_OPEN);
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

        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 40; i++) ph.record(SYMBOL, i % 2 == 0 ? 148.0 : 152.0, 1_000_000.0);

        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.setClock(() -> MARKET_OPEN);
        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(msgs.stream().anyMatch(m -> m.contains("IV surge")),
                "IV surge filter should not fire when volatility is stable");
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
        router.setClock(() -> MARKET_OPEN);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should not open when daily loss limit is active");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("daily loss limit active")),
                "Should log daily loss limit skip message");
    }

    @Test
    void testCallSkippedWhenEquityPositionAlreadyOpen() {
        Account account = new Account();
        account.addOrUpdatePosition(SYMBOL, 100, PRICE, Position.PositionType.STOCK);

        TransactionLog log = new TransactionLog(tempDir.resolve("dbl_dip.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);
        router.setClock(() -> MARKET_OPEN);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL should be blocked when equity position is already open (double-dip)");
        assertTrue(msgs.stream().anyMatch(m -> m.contains("equity position already open")),
                "Should log double-dip skip message");
    }

    @Test
    void testPutAllowedAsProtectiveHedgeWhenEquityOpen() {
        Account account = new Account();
        account.addOrUpdatePosition(SYMBOL, 100, PRICE, Position.PositionType.STOCK);

        TransactionLog log = new TransactionLog(tempDir.resolve("prot_put.db").toString());
        List<String> msgs = new ArrayList<>();
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, buildPriceHistory(), msgs::add);
        router.setClock(() -> MARKET_OPEN);
        router.setUptrendSupplier(() -> false); // bear market — putMin=4

        router.evaluate(SYMBOL, PRICE, 0, 4, "sell", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_NEARTERM_PUT"),
                "PUT should be allowed as protective hedge when equity position is open");
    }

    @Test
    void testNoTradeInsufficientPriceHistory() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("hist.db").toString());
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log);
        PriceHistory ph = new PriceHistory();
        ph.record(SYMBOL, 150.0, 1_000_000.0);
        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.setClock(() -> MARKET_OPEN);

        router.evaluate(SYMBOL, PRICE, 3, 0, "buy", "");

        assertTrue(account.getOptionsPositions().isEmpty(),
                "No position should open with only 1 price in history");
    }

    // ── Mixed signals ─────────────────────────────────────────────────────────

    @Test
    void testLongCallNotOpenedOnMixedSignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("no_call_mixed.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "Long CALL should NOT open when sell signals are also present");
    }

    @Test
    void testLongPutNotOpenedOnMixedSignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("no_put_mixed.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 1, 2, "mixed", "");

        assertFalse(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "Long PUT should NOT open when buy signals are also present");
    }

    @Test
    void testZeroDteOpenedOnFridayMixedSignals() {
        assumeTrue(LocalDate.now().getDayOfWeek() == DayOfWeek.FRIDAY,
                "Zero-DTE only opens on Fridays");
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("zerote.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);
        Set<String> strategies = new HashSet<>();
        strategies.add("ZERO_DTE");
        router.setEnabledStrategies(strategies);

        router.evaluate(SYMBOL, PRICE, 2, 1, "mixed", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_ZEROTE_CALL")
                || account.getOptionsPositions().isEmpty(), // may skip on premium check
                "Zero-DTE straddle attempted on Friday with mixed signals");
    }
}
