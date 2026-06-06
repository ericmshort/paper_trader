package com.tradingapp.ai;

import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TradeFeatureExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsPairedTradesWithCorrectWinFlag() {
        TransactionLog log = new TransactionLog(tempDir.resolve("test.db").toString());

        TransactionRecord buy1 = new TransactionRecord("AAPL", TransactionRecord.TransactionAction.BUY,
                10, 100.0, 0.0, 9000.0, "signals", "BUY");
        buy1.setFeatures("45.0,0.5,1.5,0.0,0.0,0.0");
        log.insert(buy1);

        TransactionRecord sell1 = new TransactionRecord("AAPL", TransactionRecord.TransactionAction.SELL,
                10, 110.0, 0.0, 10100.0, "signals", "SELL");
        log.insert(sell1);

        TransactionRecord buy2 = new TransactionRecord("MSFT", TransactionRecord.TransactionAction.BUY,
                5, 200.0, 0.0, 8000.0, "signals", "BUY");
        buy2.setFeatures("35.0,0.2,2.0,0.0,0.0,0.0");
        log.insert(buy2);

        TransactionRecord sell2 = new TransactionRecord("MSFT", TransactionRecord.TransactionAction.SELL,
                5, 190.0, 0.0, 8950.0, "signals", "SELL");
        log.insert(sell2);

        List<LabeledTrade> trades = new TradeFeatureExtractor().extract(log);

        assertEquals(2, trades.size());
        assertTrue(trades.get(0).isWin(), "AAPL sell > buy price — should be win");
        assertFalse(trades.get(1).isWin(), "MSFT sell < buy price — should be loss");
        assertEquals(45.0, trades.get(0).getFeatures().getRsi(), 0.001);
    }

    @Test
    void skipsUnpairedBuyRecords() {
        TransactionLog log = new TransactionLog(tempDir.resolve("test2.db").toString());

        TransactionRecord buy = new TransactionRecord("GOOG", TransactionRecord.TransactionAction.BUY,
                3, 150.0, 0.0, 8550.0, "signals", "BUY");
        buy.setFeatures("50.0,0.1,148.0,0.5,1.2");
        log.insert(buy);

        List<LabeledTrade> trades = new TradeFeatureExtractor().extract(log);
        assertEquals(0, trades.size());
    }

    @Test
    void skipsBuyRecordsWithoutFeatures() {
        TransactionLog log = new TransactionLog(tempDir.resolve("test3.db").toString());

        TransactionRecord buy = new TransactionRecord("TSLA", TransactionRecord.TransactionAction.BUY,
                2, 300.0, 0.0, 7400.0, "signals", "BUY");
        log.insert(buy);

        TransactionRecord sell = new TransactionRecord("TSLA", TransactionRecord.TransactionAction.SELL,
                2, 310.0, 0.0, 8020.0, "signals", "SELL");
        log.insert(sell);

        List<LabeledTrade> trades = new TradeFeatureExtractor().extract(log);
        assertEquals(0, trades.size());
    }
}
