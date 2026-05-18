package com.tradingapp.engine;

import com.tradingapp.account.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class OrderExecutorTest {

    @TempDir
    Path tempDir;

    private TransactionLog buildLog() throws Exception {
        return new TransactionLog(tempDir.resolve("test.db").toString());
    }

    @Test
    void testBuyDeductsBalanceAndFee() throws Exception {
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = buildLog();
        OrderExecutor executor = new OrderExecutor(account, safety, log, new FeeCalculator());

        TransactionRecord record = executor.buy("AAPL", 10, 150.0, "RSI=28", "RSI oversold");

        assertNotNull(record);
        assertEquals(98499.90, account.getBalance(), 0.01);
        assertEquals(1, log.findAll().size());
        assertEquals(TransactionRecord.TransactionAction.BUY, record.getAction());
        assertEquals("AAPL", record.getSymbol());
        assertEquals(10, record.getQuantity());
        assertEquals(150.0, record.getPricePerUnit(), 0.001);
        assertEquals(0.10, record.getFeeCharged(), 0.001);
    }

    @Test
    void testSellIncreasesBalanceMinusFee() throws Exception {
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = buildLog();
        OrderExecutor executor = new OrderExecutor(account, safety, log, new FeeCalculator());

        executor.buy("AAPL", 10, 150.0, "RSI=28", "RSI oversold");
        executor.sell("AAPL", 10, 160.0, "RSI=72", "RSI overbought");

        assertEquals(100099.80, account.getBalance(), 0.01);
        assertEquals(2, log.findAll().size());
    }

    @Test
    void testBuyRefusedWhenSafetyStopHalted() throws Exception {
        Account account = new Account();
        account.setBalance(50.0);
        account.setTradingHalted(true);
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = buildLog();
        OrderExecutor executor = new OrderExecutor(account, safety, log, new FeeCalculator());

        TransactionRecord result = executor.buy("AAPL", 1, 10.0, "", "");

        assertNull(result);
        assertEquals(50.0, account.getBalance(), 0.001);
    }

    @Test
    void testBuyWithFeaturesStoresFeaturesCsv() throws Exception {
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = buildLog();
        OrderExecutor executor = new OrderExecutor(account, safety, log, new FeeCalculator());

        String features = "45.0,0.5,102.0,2.0,1.5";
        executor.buy("AAPL", 10, 150.0, "RSI=28", "RSI oversold", features);

        var records = log.findAll();
        assertEquals(1, records.size());
        assertEquals(features, records.get(0).getFeatures());
    }

    @Test
    void testBuyWithoutFeaturesStoresNullFeatures() throws Exception {
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = buildLog();
        OrderExecutor executor = new OrderExecutor(account, safety, log, new FeeCalculator());

        executor.buy("AAPL", 10, 150.0, "RSI=28", "RSI oversold");

        var records = log.findAll();
        assertNull(records.get(0).getFeatures());
    }

    @Test
    void testBuyRefusedWhenBalanceBelowStop() throws Exception {
        Account account = new Account();
        account.setBalance(0.0);
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = buildLog();
        OrderExecutor executor = new OrderExecutor(account, safety, log, new FeeCalculator());

        TransactionRecord result = executor.buy("AAPL", 1, 10.0, "", "");

        assertNull(result);
    }
}
