package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.SafetyStop;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TradingLoopTest {

    @TempDir
    Path tempDir;

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private TradingLoop buildLoopWithEvaluator(ZonedDateTime fixedTime, SignalWeightEvaluator weightEval,
                                               Runnable afterMarket, List<String> research) throws Exception {
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = new TransactionLog(tempDir.resolve("wt.db").toString());
        FeeCalculator fees = new FeeCalculator();
        BrokerClient broker = new SimulatedBroker(new OrderExecutor(account, safety, log, fees));
        return new TradingLoop(null, new PriceHistory(), new IndicatorEngine(), new TrailingStopMonitor(),
                broker, fees, List.of(),
                research::add, () -> {}, account,
                () -> fixedTime, null, weightEval, afterMarket);
    }

    private TradingLoop buildLoop(ZonedDateTime fixedTime, List<String> research) throws Exception {
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = new TransactionLog(tempDir.resolve("test.db").toString());
        FeeCalculator fees = new FeeCalculator();
        BrokerClient broker = new SimulatedBroker(new OrderExecutor(account, safety, log, fees));
        return new TradingLoop(
                null, new PriceHistory(), new IndicatorEngine(), new TrailingStopMonitor(),
                broker, fees, List.of("AAPL"),
                research::add, () -> {}, account,
                () -> fixedTime);
    }

    @Test
    void testMarketClosedBeforeOpen() throws Exception {
        List<String> research = new ArrayList<>();
        ZonedDateTime beforeOpen = ZonedDateTime.of(2026, 5, 15, 8, 0, 0, 0, ET);
        TradingLoop loop = buildLoop(beforeOpen, research);
        loop.run();
        assertEquals(1, research.size(), "Should emit exactly one message when market is closed");
        assertTrue(research.get(0).contains("Market closed"), "Message should say Market closed, got: " + research.get(0));
    }

    @Test
    void testMarketClosedAfterClose() throws Exception {
        List<String> research = new ArrayList<>();
        ZonedDateTime afterClose = ZonedDateTime.of(2026, 5, 15, 17, 0, 0, 0, ET);
        TradingLoop loop = buildLoop(afterClose, research);
        loop.run();
        assertEquals(1, research.size());
        assertTrue(research.get(0).contains("Market closed"));
    }

    @Test
    void testTradingHaltedMessageEmitted() throws Exception {
        List<String> research = new ArrayList<>();
        ZonedDateTime marketOpen = ZonedDateTime.of(2026, 5, 15, 10, 0, 0, 0, ET);
        Account account = new Account();
        account.setTradingHalted(true);
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = new TransactionLog(tempDir.resolve("halt.db").toString());
        FeeCalculator fees = new FeeCalculator();
        BrokerClient broker = new SimulatedBroker(new OrderExecutor(account, safety, log, fees));
        TradingLoop loop = new TradingLoop(
                null, new PriceHistory(), new IndicatorEngine(), new TrailingStopMonitor(),
                broker, fees, List.of("AAPL"),
                research::add, () -> {}, account,
                () -> marketOpen);
        loop.run();
        assertEquals(1, research.size());
        assertTrue(research.get(0).contains("TRADING HALTED"));
    }

    @Test
    void afterMarketCallbackFiresOncePerDay() throws Exception {
        List<String> research = new ArrayList<>();
        List<String> trainLog = new ArrayList<>();
        ZonedDateTime afterClose = ZonedDateTime.of(2026, 5, 15, 17, 0, 0, 0, ET);
        TradingLoop loop = buildLoopWithEvaluator(afterClose, null, () -> trainLog.add("trained"), research);

        loop.run(); // first call — should trigger training
        loop.run(); // second call same day — should NOT trigger again
        assertEquals(1, trainLog.size(), "Callback should fire exactly once per calendar day");
    }

    @Test
    void extractFeatureCsvReturnsFiveValues() throws Exception {
        List<String> research = new ArrayList<>();
        ZonedDateTime afterClose = ZonedDateTime.of(2026, 5, 15, 17, 0, 0, 0, ET);
        TradingLoop loop = buildLoopWithEvaluator(afterClose, null, null, research);

        List<SignalResult> signals = List.of(
            SignalResult.buy("RSI", 25.0),
            SignalResult.sell("MACD", 0.5),
            SignalResult.neutral("BollingerBands", 102.0)
        );
        // Access via a subclass to expose the private helper for testing
        java.lang.reflect.Method m = TradingLoop.class.getDeclaredMethod("extractFeatureCsv", List.class);
        m.setAccessible(true);
        String csv = (String) m.invoke(loop, signals);
        String[] parts = csv.split(",");
        assertEquals(5, parts.length);
        assertEquals(25.0, Double.parseDouble(parts[0]), 0.001); // RSI
        assertEquals(0.5, Double.parseDouble(parts[1]), 0.001);  // MACD
        assertEquals(102.0, Double.parseDouble(parts[2]), 0.001); // BollingerBands
    }

    @Test
    void testNoTradeWhenOnlyOneBuySignal() throws Exception {
        List<String> research = new ArrayList<>();
        ZonedDateTime marketOpen = ZonedDateTime.of(2026, 5, 15, 10, 0, 0, 0, ET);
        // IndicatorEngine with no real data will return NEUTRAL for all indicators
        // so 0 BUY signals < threshold of 2 — no trade should be placed
        Account account = new Account();
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = new TransactionLog(tempDir.resolve("nosig.db").toString());
        FeeCalculator fees = new FeeCalculator();
        BrokerClient broker = new SimulatedBroker(new OrderExecutor(account, safety, log, fees));
        TradingLoop loop = new TradingLoop(
                null, new PriceHistory(), new IndicatorEngine(), new TrailingStopMonitor(),
                broker, fees, List.of(),  // empty watchlist: no API call, no trades
                research::add, () -> {}, account,
                () -> marketOpen);
        loop.run();
        assertEquals(100_000.0, account.getBalance(), 0.001, "No trade should fire with empty watchlist");
        assertTrue(log.findAll().isEmpty(), "No transactions should be logged");
    }
}
