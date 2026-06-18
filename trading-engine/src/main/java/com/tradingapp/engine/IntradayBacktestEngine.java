package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.Position;
import com.tradingapp.account.SafetyStop;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.CandleHistory;
import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class IntradayBacktestEngine {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final IndicatorEngine indicators;
    private final FeeCalculator fees;
    // When false the full event log is not accumulated (saves ~1-2 GB per run in multi-config comparisons).
    private boolean collectEventLog = true;

    public IntradayBacktestEngine(IndicatorEngine indicators, FeeCalculator fees) {
        this.indicators = indicators;
        this.fees = fees;
    }

    public IntradayBacktestEngine setCollectEventLog(boolean collect) {
        this.collectEventLog = collect;
        return this;
    }

    public IntradayBacktestResult run(
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startingBalance,
            OptionsEvaluator optEval,
            Consumer<String> progressCallback) throws Exception {
        return run(watchlist, barsBySymbol, startingBalance, optEval, progressCallback, Set.of(), null);
    }

    public IntradayBacktestResult run(
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startingBalance,
            OptionsEvaluator optEval,
            Consumer<String> progressCallback,
            Set<String> inverseEtfSymbols) throws Exception {
        return run(watchlist, barsBySymbol, startingBalance, optEval, progressCallback, inverseEtfSymbols, null);
    }

    public IntradayBacktestResult run(
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startingBalance,
            OptionsEvaluator optEval,
            Consumer<String> progressCallback,
            Set<String> inverseEtfSymbols,
            java.util.function.Consumer<TradingLoop> loopConfig) throws Exception {
        return run(watchlist, barsBySymbol, startingBalance, optEval, progressCallback,
                inverseEtfSymbols, loopConfig, null);
    }

    /**
     * Dynamic-watchlist overload: {@code dailyWatchlistProvider} is called before each trading day
     * and returns the symbols to activate that day. Only those symbols' bars are loaded into the
     * replay provider, so TradingLoop naturally ignores the rest.
     */
    public IntradayBacktestResult run(
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startingBalance,
            OptionsEvaluator optEval,
            Consumer<String> progressCallback,
            Set<String> inverseEtfSymbols,
            java.util.function.Consumer<TradingLoop> loopConfig,
            java.util.function.Function<LocalDate, Set<String>> dailyWatchlistProvider) throws Exception {

        File tmpDb = File.createTempFile("backtest", ".db");
        tmpDb.deleteOnExit();
        TransactionLog txLog = new TransactionLog(tmpDb.getAbsolutePath());

        Account account = new Account();
        account.reset(startingBalance);

        PriceHistory priceHistory = new PriceHistory();
        SafetyStop safetyStop = new SafetyStop(account);
        OrderExecutor orderExecutor = new OrderExecutor(account, safetyStop, txLog, fees);
        SimulatedBroker broker = new SimulatedBroker(orderExecutor);
        TrailingStopMonitor trailingStop = new TrailingStopMonitor();

        AtomicReference<ZonedDateTime> virtualClock = new AtomicReference<>(
                ZonedDateTime.now(ET));

        if (optEval != null) {
            optEval.onBacktestInit(txLog, account, priceHistory, virtualClock::get);
        }

        Map<String, IntradayBar> currentBars = new HashMap<>();

        QuoteProvider replayProvider = new ReplayQuoteProvider(currentBars);

        List<String> eventLog = collectEventLog ? new ArrayList<>() : List.of();
        Consumer<String> logCollector = msg -> {
            if (collectEventLog) ((java.util.ArrayList<String>) eventLog).add(msg);
            if (progressCallback != null) progressCallback.accept(msg);
        };

        TradingLoop loop = new TradingLoop(
                replayProvider, priceHistory, indicators, trailingStop, broker, fees,
                watchlist, logCollector, () -> {}, account,
                virtualClock::get, optEval);
        loop.setTransactionLog(txLog);
        loop.setAvoidOvernightHolds(true);
        if (!inverseEtfSymbols.isEmpty()) loop.setInverseEtfSymbols(inverseEtfSymbols);
        if (loopConfig != null) loopConfig.accept(loop);

        TreeMap<LocalDate, TreeMap<ZonedDateTime, List<IntradayBar>>> byDateByTime = groupBars(barsBySymbol);

        List<LocalDate> tradingDays = new ArrayList<>(byDateByTime.keySet());
        Collections.sort(tradingDays);

        List<BacktestDataPoint> equityCurve = new ArrayList<>();
        double startValue = startingBalance;

        int dayNum = 0;
        for (LocalDate date : tradingDays) {
            dayNum++;
            if (progressCallback != null) progressCallback.accept("Day " + dayNum + "/" + tradingDays.size() + ": " + date);

            trailingStop.resetAll();
            currentBars.clear();  // drop prior day's quotes so rotated-out symbols get no quote

            CandleHistory candleHistory = new CandleHistory();
            loop.setCandleHistory(candleHistory);

            // Determine which symbols are active today (null provider = use full watchlist)
            Set<String> activeSymbols = dailyWatchlistProvider != null
                    ? dailyWatchlistProvider.apply(date)
                    : new java.util.HashSet<>(watchlist);

            if (optEval != null) {
                optEval.resetForDay(date);
            }

            TreeMap<ZonedDateTime, List<IntradayBar>> timeSlots = byDateByTime.get(date);

            for (Map.Entry<ZonedDateTime, List<IntradayBar>> entry : timeSlots.entrySet()) {
                ZonedDateTime ts = entry.getKey();
                List<IntradayBar> bars = entry.getValue();

                for (IntradayBar bar : bars) {
                    if (activeSymbols.contains(bar.symbol())) {
                        currentBars.put(bar.symbol(), bar);
                        candleHistory.recordTick(bar.symbol(), bar.close(), bar.volume(), ts.toInstant());
                    }
                }

                virtualClock.set(ts);
                loop.run();
            }

            double stockMV = account.getPositions().values().stream()
                    .mapToDouble(Position::getMarketValue).sum();
            double optsMV = account.getOptionsPositions().values().stream()
                    .filter(p -> p.getContracts() > 0)
                    .mapToDouble(p -> {
                        double mkt = p.getCurrentMarketPrice();
                        return (mkt >= 0 ? mkt : p.getPremiumPaid()) * 100 * p.getContracts();
                    })
                    .sum();
            double eodValue = account.getBalance() + stockMV + optsMV;
            equityCurve.add(new BacktestDataPoint(date, eodValue));
        }

        double finalBalance = equityCurve.isEmpty() ? startingBalance
                : equityCurve.get(equityCurve.size() - 1).getPortfolioValue();

        double totalReturnPct = startValue > 0 ? (finalBalance - startValue) / startValue * 100.0 : 0.0;
        double maxDrawdownPct = computeMaxDrawdown(equityCurve);
        int wins = txLog.countWins();
        int losses = txLog.countLosses();
        int totalTrades = wins + losses;
        List<TransactionRecord> trades = txLog.findAll();

        try { tmpDb.delete(); } catch (Exception ignored) {}

        return new IntradayBacktestResult(equityCurve, totalReturnPct, maxDrawdownPct,
                wins, losses, totalTrades, eventLog, finalBalance, trades);
    }

    private TreeMap<LocalDate, TreeMap<ZonedDateTime, List<IntradayBar>>> groupBars(
            Map<String, List<IntradayBar>> barsBySymbol) {
        TreeMap<LocalDate, TreeMap<ZonedDateTime, List<IntradayBar>>> result = new TreeMap<>();
        for (List<IntradayBar> bars : barsBySymbol.values()) {
            for (IntradayBar bar : bars) {
                LocalDate date = bar.time().toLocalDate();
                ZonedDateTime ts = bar.time();
                result.computeIfAbsent(date, d -> new TreeMap<>())
                      .computeIfAbsent(ts, t -> new ArrayList<>())
                      .add(bar);
            }
        }
        return result;
    }

    private double computeMaxDrawdown(List<BacktestDataPoint> curve) {
        double peak = Double.NEGATIVE_INFINITY;
        double maxDD = 0.0;
        for (BacktestDataPoint pt : curve) {
            double v = pt.getPortfolioValue();
            if (v > peak) peak = v;
            if (peak > 0) {
                double dd = (peak - v) / peak * 100.0;
                if (dd > maxDD) maxDD = dd;
            }
        }
        return maxDD;
    }

    static class ReplayQuoteProvider implements QuoteProvider {
        private final Map<String, IntradayBar> currentBars;

        ReplayQuoteProvider(Map<String, IntradayBar> currentBars) {
            this.currentBars = currentBars;
        }

        @Override
        public QuoteModel getQuote(String symbol) {
            IntradayBar bar = currentBars.get(symbol);
            if (bar == null) return null;
            long epochMs = bar.time().toInstant().toEpochMilli();
            return QuoteModel.fromLive(symbol, bar.close(), bar.volume(), epochMs);
        }

        @Override
        public List<QuoteModel> getQuotes(List<String> symbols) {
            List<QuoteModel> result = new ArrayList<>();
            for (String sym : symbols) {
                QuoteModel q = getQuote(sym);
                if (q != null) result.add(q);
            }
            return result;
        }

        @Override
        public OptionsChain getOptionsChain(String symbol, java.time.LocalDate expiry) {
            return OptionsChain.empty();
        }

        @Override
        public List<HistoricalBar> getHistoricalBars(String symbol, java.time.LocalDate start, java.time.LocalDate end) {
            return List.of();
        }

        @Override
        public String getName() { return "Replay"; }

        @Override
        public java.time.LocalDate getEarliestBacktestDate() { return java.time.LocalDate.EPOCH; }
    }
}
