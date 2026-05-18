package com.tradingapp.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionLogTest {

    @TempDir
    Path tempDir;

    private String tempDbPath() {
        return tempDir.resolve("test-transactions.db").toString();
    }

    @Test
    void testInsertAndRetrieve() {
        TransactionLog log = new TransactionLog(tempDbPath());
        TransactionRecord record = new TransactionRecord(
                "AAPL", TransactionRecord.TransactionAction.BUY,
                10, 180.0, 0.10, 98200.0,
                "RSI oversold signal", "RSI=28.5"
        );
        log.insert(record);
        List<TransactionRecord> all = log.findAll();
        assertEquals(1, all.size(), "Should have exactly 1 record");
        TransactionRecord retrieved = all.get(0);
        assertEquals("AAPL", retrieved.getSymbol());
        assertEquals(TransactionRecord.TransactionAction.BUY, retrieved.getAction());
        assertEquals(10, retrieved.getQuantity());
        assertEquals(180.0, retrieved.getPricePerUnit(), 0.001);
        assertEquals("RSI oversold signal", retrieved.getReason());
        assertTrue(retrieved.getId() > 0, "Auto-generated ID should be positive");
    }

    @Test
    void testPersistsAcrossInstances() {
        String dbPath = tempDbPath();
        TransactionLog log1 = new TransactionLog(dbPath);
        TransactionRecord record = new TransactionRecord(
                "MSFT", TransactionRecord.TransactionAction.SELL,
                5, 420.0, 0.05, 99500.0,
                "MACD crossover sell", "MACD=-0.5"
        );
        log1.insert(record);

        TransactionLog log2 = new TransactionLog(dbPath);
        List<TransactionRecord> all = log2.findAll();
        assertEquals(1, all.size(), "Record should persist across TransactionLog instances");
        assertEquals("MSFT", all.get(0).getSymbol());
    }

    @Test
    void testCountWinsAndLosses() {
        TransactionLog log = new TransactionLog(tempDbPath());
        log.insert(new TransactionRecord("AAPL", TransactionRecord.TransactionAction.SELL,
                100, 2.0, 0.05, 99800.0, "sell signal", "RSI=72"));
        log.insert(new TransactionRecord("TSLA", TransactionRecord.TransactionAction.SELL,
                1, 0.02, 1.0, 99799.0, "sell signal", "MACD"));
        assertEquals(1, log.countWins(), "Should count 1 win (revenue > fee)");
        assertEquals(1, log.countLosses(), "Should count 1 loss (revenue <= fee)");
    }
}
