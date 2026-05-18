package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
