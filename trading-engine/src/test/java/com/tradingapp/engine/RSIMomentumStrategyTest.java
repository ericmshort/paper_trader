package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.Position;
import com.tradingapp.account.SafetyStop;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class RSIMomentumStrategyTest {

    @TempDir
    Path tempDir;

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final ZonedDateTime MARKET_OPEN = ZonedDateTime.of(2026, 5, 15, 10, 0, 0, 0, ET);

    // --- RSIMomentumStrategy unit tests ---

    @Test
    void signalReturnsBuyWhenRSIOversold() {
        IndicatorEngine engine = new IndicatorEngine();
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(engine);

        // Prices with a strong downtrend force RSI < 30
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 10; i++) prices.add(100.0 + i);      // small gains
        for (int i = 0; i < 10; i++) prices.add(110.0 - i * 3);  // heavy losses

        SignalResult.Direction dir = strategy.signal(prices);
        assertEquals(SignalResult.Direction.BUY, dir, "Oversold RSI should produce BUY signal");
    }

    @Test
    void signalReturnsSellWhenRSIOverbought() {
        IndicatorEngine engine = new IndicatorEngine();
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(engine);

        // Prices with a strong uptrend force RSI > 70
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 5; i++) prices.add(100.0 - i);       // small losses
        for (int i = 0; i < 10; i++) prices.add(95.0 + i * 3);   // heavy gains

        SignalResult.Direction dir = strategy.signal(prices);
        assertEquals(SignalResult.Direction.SELL, dir, "Overbought RSI should produce SELL signal");
    }

    @Test
    void signalReturnsNeutralWithInsufficientData() {
        IndicatorEngine engine = new IndicatorEngine();
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(engine);

        // RSI needs period+1 = 15 bars minimum
        List<Double> prices = List.of(100.0, 101.0, 102.0);
        assertEquals(SignalResult.Direction.NEUTRAL, strategy.signal(prices));
    }

    @Test
    void trailingStopNotHitWhenPriceRisesAboveEntry() {
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        strategy.onPositionOpened(100.0);

        assertFalse(strategy.isTrailingStopHit(105.0), "Price above entry should not trigger stop");
        assertFalse(strategy.isTrailingStopHit(110.0), "Price at new peak should not trigger stop");
    }

    @Test
    void trailingStopHitAfterTwoPercentDropFromPeak() {
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        strategy.onPositionOpened(100.0);

        strategy.isTrailingStopHit(110.0);  // peak moves to 110
        assertTrue(strategy.isTrailingStopHit(107.8),
                "Price 2% below peak (110 * 0.98 = 107.8) should trigger trailing stop");
    }

    @Test
    void trailingStopNotHitJustAboveThreshold() {
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        strategy.onPositionOpened(100.0);

        strategy.isTrailingStopHit(100.0);  // peak stays at 100
        assertFalse(strategy.isTrailingStopHit(98.1),
                "Price just above 2% threshold should not trigger stop");
    }

    @Test
    void peakResetAfterPositionClosed() {
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        strategy.onPositionOpened(100.0);
        strategy.isTrailingStopHit(120.0);  // peak moves to 120
        strategy.onPositionClosed();
        strategy.onPositionOpened(50.0);    // new entry at 50

        assertFalse(strategy.isTrailingStopHit(49.5),
                "After position reset, stop should be based on new entry price, not old peak");
        assertTrue(strategy.isTrailingStopHit(48.9),
                "2% below new entry (50 * 0.98 = 49.0) should trigger stop");
    }

    @Test
    void trailingStopPctIsTwo() {
        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        assertEquals(0.02, strategy.getTrailingStopPct(), 1e-10);
    }

    // --- TradingLoop integration tests ---

    private TradingLoop buildLoopWithStrategy(Account account, QuoteProvider qp, PriceHistory ph,
                                              RSIMomentumStrategy strategy) throws Exception {
        SafetyStop safety = new SafetyStop(account);
        TransactionLog log = new TransactionLog(tempDir.resolve("rsi_" + System.nanoTime() + ".db").toString());
        FeeCalculator fees = new FeeCalculator();
        BrokerClient broker = new SimulatedBroker(new OrderExecutor(account, safety, log, fees));
        TradingLoop loop = new TradingLoop(qp, ph, new IndicatorEngine(), new TrailingStopMonitor(),
                broker, fees, List.of("AAPL"), msg -> {}, () -> {}, account, () -> MARKET_OPEN);
        loop.setMarketRegimeFilterEnabled(false);
        loop.registerStrategy("AAPL", strategy);
        return loop;
    }

    private QuoteProvider fixedQuote(String symbol, double price) {
        return new QuoteProvider() {
            @Override public QuoteModel getQuote(String s) {
                return QuoteModel.fromLive(s, price, 1_000_000L, System.currentTimeMillis());
            }
            @Override public List<QuoteModel> getQuotes(List<String> symbols) {
                return symbols.stream().map(this::getQuote).collect(Collectors.toList());
            }
            @Override public OptionsChain getOptionsChain(String s, LocalDate e) { return null; }
            @Override public List<com.tradingapp.data.HistoricalBar> getHistoricalBars(String s, LocalDate st, LocalDate en) { return List.of(); }
            @Override public String getName() { return "test"; }
            @Override public LocalDate getEarliestBacktestDate() { return LocalDate.of(2020, 1, 1); }
        };
    }

    @Test
    void loopBuysWhenRSIStrategySignalsBuy() throws Exception {
        Account account = new Account();

        // Seed price history to produce RSI < 30 (oversold)
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 10; i++) ph.recordDaily("AAPL", 100.0 + i, 1_000_000);
        for (int i = 0; i < 10; i++) ph.recordDaily("AAPL", 110.0 - i * 3, 1_000_000);

        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        // Verify the seeded history actually produces a BUY signal
        List<Double> seededPrices = ph.getDailyPrices("AAPL");
        assertEquals(SignalResult.Direction.BUY, strategy.signal(seededPrices),
                "Test pre-condition: seeded prices should be oversold");

        TradingLoop loop = buildLoopWithStrategy(account, fixedQuote("AAPL", 80.0), ph, strategy);
        loop.run();

        assertTrue(account.getPositions().containsKey("AAPL"),
                "Loop should open AAPL position when RSI momentum strategy signals BUY");
    }

    @Test
    void loopSellsOnTrailingStopBreach() throws Exception {
        Account account = new Account();
        account.addOrUpdatePosition("AAPL", 10, 100.0, Position.PositionType.STOCK);
        account.setBalance(100_000.0 - 1_000.0);

        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        strategy.onPositionOpened(100.0);
        strategy.isTrailingStopHit(110.0);  // peak now 110; stop triggers at or below 107.8

        // Price of 107.0 is 2.7% below peak — should trigger 2% trailing stop
        TradingLoop loop = buildLoopWithStrategy(account, fixedQuote("AAPL", 107.0), new PriceHistory(), strategy);
        loop.run();

        assertFalse(account.getPositions().containsKey("AAPL"),
                "Position should be closed when price drops 2% from peak");
    }

    @Test
    void loopSellsOnRSIOverboughtSignal() throws Exception {
        Account account = new Account();
        account.addOrUpdatePosition("AAPL", 10, 100.0, Position.PositionType.STOCK);
        account.setBalance(100_000.0 - 1_000.0);

        // Seed price history with strong uptrend (RSI > 70)
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 5; i++) ph.recordDaily("AAPL", 100.0 - i, 1_000_000);
        for (int i = 0; i < 10; i++) ph.recordDaily("AAPL", 95.0 + i * 3, 1_000_000);

        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        strategy.onPositionOpened(100.0);
        // Verify the seeded history actually produces a SELL signal
        List<Double> seededPrices = ph.getDailyPrices("AAPL");
        assertEquals(SignalResult.Direction.SELL, strategy.signal(seededPrices),
                "Test pre-condition: seeded prices should be overbought");

        TradingLoop loop = buildLoopWithStrategy(account, fixedQuote("AAPL", 125.0), ph, strategy);
        loop.run();

        assertFalse(account.getPositions().containsKey("AAPL"),
                "Position should be closed when RSI momentum strategy signals overbought");
    }

    @Test
    void loopDoesNotTradeWhenRSINeutral() throws Exception {
        Account account = new Account();

        // Very few bars → RSI returns NEUTRAL
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 3; i++) ph.recordDaily("AAPL", 150.0, 1_000_000);

        RSIMomentumStrategy strategy = new RSIMomentumStrategy(new IndicatorEngine());
        TradingLoop loop = buildLoopWithStrategy(account, fixedQuote("AAPL", 150.0), ph, strategy);
        loop.run();

        assertFalse(account.getPositions().containsKey("AAPL"),
                "No position should open when RSI is neutral (insufficient data)");
    }
}
