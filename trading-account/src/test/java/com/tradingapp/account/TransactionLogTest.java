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
        // BUY at $100, then sell at $110 (win) and at $90 (loss)
        log.insert(new TransactionRecord("AAPL", TransactionRecord.TransactionAction.BUY,
                10, 100.0, 0.10, 99000.0, "buy signal", "RSI=30"));
        log.insert(new TransactionRecord("AAPL", TransactionRecord.TransactionAction.SELL,
                10, 110.0, 0.10, 99000.0, "sell signal", "RSI=72"));
        log.insert(new TransactionRecord("TSLA", TransactionRecord.TransactionAction.BUY,
                5, 200.0, 0.05, 98000.0, "buy signal", "RSI=35"));
        log.insert(new TransactionRecord("TSLA", TransactionRecord.TransactionAction.SELL,
                5, 190.0, 0.05, 97500.0, "sell signal", "trailing stop"));
        assertEquals(1, log.countWins(), "AAPL sold above entry price is a win");
        assertEquals(1, log.countLosses(), "TSLA sold below entry price is a loss");
    }
}
