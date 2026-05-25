package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        // Zigzag prices to produce non-zero historical volatility
        double[] basePrices = {148, 152, 147, 153, 146, 154, 145, 155, 144, 156,
                               148, 151, 147, 153, 149, 152, 148, 154, 146, 153,
                               150, 152, 148, 151, 150};
        for (double p : basePrices) {
            ph.record(SYMBOL, p, 1_000_000.0);
        }
        return ph;
    }

    @Test
    void testCallOpenedOnBuySignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 2, 0, "test signals", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"),
                "CALL position should be opened on 2 buy signals");
    }

    @Test
    void testPutOpenedOnSellSignals() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        router.evaluate(SYMBOL, PRICE, 0, 2, "test signals", "");

        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_PUT"),
                "PUT position should be opened on 2 sell signals");
    }

    @Test
    void testCallClosedOnReversal() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("rev.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open call
        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");
        assertTrue(account.getOptionsPositions().containsKey(SYMBOL + "_CALL"));

        // Reverse: sell signal closes the call
        router.evaluate(SYMBOL, PRICE, 0, 2, "sell", "");
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

        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

        assertTrue(account.getOptionsPositions().isEmpty(),
                "No position should be opened when vol = 0");
    }

    @Test
    void testCallClosedOnPremiumStopLoss() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call_stop.db").toString());
        OptionsSignalRouter router = buildRouter(account, log);

        // Open a call ATM at $150
        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");
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
        router.evaluate(SYMBOL, PRICE, 0, 2, "sell", "");
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
        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");
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

        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

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
        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

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
        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

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
        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");
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
        router.evaluate(SYMBOL, PRICE, 0, 2, "sell", "");
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

        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

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

        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

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

        router.evaluate(SYMBOL, PRICE, 0, 2, "sell", "");

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

        router.evaluate(SYMBOL, PRICE, 2, 0, "buy", "");

        assertTrue(account.getOptionsPositions().isEmpty(),
                "No position should open with only 1 price in history");
    }
}
