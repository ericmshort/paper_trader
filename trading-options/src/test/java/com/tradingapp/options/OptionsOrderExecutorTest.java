package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OptionsOrderExecutorTest {

    @TempDir
    Path tempDir;

    private static final LocalDate EXPIRY = LocalDate.now().plusDays(30);

    private OptionsOrderExecutor build(Account account, TransactionLog log) {
        return new OptionsOrderExecutor(account, log);
    }

    @Test
    void testBuyCallDeductsBalanceAndFee() {
        Account account = new Account(); // 100_000 balance
        TransactionLog log = new TransactionLog(tempDir.resolve("test.db").toString());
        OptionsOrderExecutor exec = build(account, log);

        exec.buyCall("AAPL", 150.0, EXPIRY, 1, 5.0, "test signals", "");

        // fillPremium = 5.05, totalCost = 5.05 * 100 * 1 + 0.65 = 505.65
        assertEquals(100_000.0 - 505.65, account.getBalance(), 0.001);
        List<TransactionRecord> records = log.findAll();
        assertEquals(1, records.size());
        assertEquals(TransactionRecord.TransactionAction.CALL_BUY, records.get(0).getAction());
    }

    @Test
    void testBuyCallInsufficientBalance() {
        Account account = new Account();
        account.setBalance(0.0);
        TransactionLog log = new TransactionLog(tempDir.resolve("insuf.db").toString());
        OptionsOrderExecutor exec = build(account, log);

        exec.buyCall("AAPL", 150.0, EXPIRY, 1, 5.0, "test", "");

        assertEquals(0.0, account.getBalance(), 0.001);
        assertTrue(account.getOptionsPositions().isEmpty());
        assertTrue(log.findAll().isEmpty());
    }

    @Test
    void testCloseCallPosition() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("close.db").toString());
        OptionsOrderExecutor exec = build(account, log);

        // Buy 1 call at premium 5.0: fillPremium = 5.05, balance = 100_000 - 505.65 = 99_494.35
        exec.buyCall("AAPL", 150.0, EXPIRY, 1, 5.0, "buy signals", "");
        double balanceAfterBuy = account.getBalance();
        assertEquals(99_494.35, balanceAfterBuy, 0.001);

        // Close at premium 8.0: net = 8.0*100 - 0.65 = 799.35
        exec.closePosition("AAPL_CALL", 8.0, "sell signals");
        assertEquals(99_494.35 + 799.35, account.getBalance(), 0.001); // 100_293.70

        List<TransactionRecord> records = log.findAll();
        assertEquals(2, records.size());
        // findAll returns newest first
        assertEquals(TransactionRecord.TransactionAction.CALL_SELL, records.get(0).getAction());
        assertTrue(account.getOptionsPositions().isEmpty());
    }

    @Test
    void testBuyPutCreatesPosition() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put.db").toString());
        OptionsOrderExecutor exec = build(account, log);

        exec.buyPut("AAPL", 145.0, EXPIRY, 1, 3.0, "sell signals", "");

        assertTrue(account.getOptionsPositions().containsKey("AAPL_PUT"));
        assertEquals("PUT", account.getOptionsPositions().get("AAPL_PUT").getType());
    }
}
