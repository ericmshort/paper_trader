package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.LargeCapWatchList;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs three backtest passes over the same bar data:
 *   1. Stocks only  (options router disabled)
 *   2. Options only (stock trading disabled)
 *   3. Combined     (both enabled, shared capital)
 *
 * This reveals whether stocks and options complement or compete with each other.
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.CombinedBacktestRunner
 */
public class CombinedBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Stock risk params (matching live app)
    private static final double TRAILING_STOP_PCT  = 0.02;
    private static final double MAX_LOSS_PER_TRADE = 0.005;
    private static final double CIRCUIT_BREAKER_PCT = 0.02;
    private static final int    MAX_POSITIONS       = 8;
    private static final int    REGIME_MA_DAYS      = 20;

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();

        // --- Build combined symbol universe ---
        List<String> stockSymbols = cfg.getStockWatchlist().isEmpty()
                ? new ArrayList<>(LargeCapWatchList.SYMBOLS)
                : new ArrayList<>(cfg.getStockWatchlist());
        List<String> optionsSymbols = cfg.getOptionsWatchlist().isEmpty()
                ? new ArrayList<>(DayTraderWatchList.SYMBOLS)
                : new ArrayList<>(cfg.getOptionsWatchlist());

        List<String> allSymbols = new ArrayList<>(stockSymbols);
        for (String s : optionsSymbols) {
            if (!allSymbols.contains(s)) allSymbols.add(s);
        }
        if (!allSymbols.contains("SPY")) allSymbols.add("SPY");

        // --- Date range: 2 years back from most-recent cached date ---
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            endDate = endDate.minusDays(1);
        }
        LocalDate startDate = endDate.minusDays(730);

        System.out.println("=== Combined Stock + Options Backtest ===");
        System.out.printf("Period       : %s → %s%n", startDate, endDate);
        System.out.printf("Stock syms   : %d  Options syms: %d  Total fetch: %d%n",
                stockSymbols.size(), optionsSymbols.size(), allSymbols.size());
        System.out.printf("Stock config : trailing stop=%.0f%%  max loss/trade=%.2f%%  circuit breaker=%.0f%%  max positions=%d  regime MA=%d-day%n",
                TRAILING_STOP_PCT * 100, MAX_LOSS_PER_TRADE * 100, CIRCUIT_BREAKER_PCT * 100, MAX_POSITIONS, REGIME_MA_DAYS);
        System.out.printf("Options cfg  : stop loss=%.0f%%  profit target=%.1fx  reversal signals=%d%n",
                cfg.getOptionsStopLossFrac() * 100, cfg.getProfitTarget(), cfg.getReversalMinSignals());

        // --- Fetch bars ---
        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = allSymbols.size();
        int idx = 0;
        for (String sym : allSymbols) {
            idx++;
            final int cur = idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.printf("[%d/%d] SKIP %s: %s%n", cur, total, sym, e.getMessage());
            }
        }
        System.out.printf("Loaded %d/%d symbols. Running 3 passes...%n%n", barsBySymbol.size(), total);

        VixCache vixCache = new VixCache();
        vixCache.load(startDate, endDate);

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        Set<String> optAllowlist = cfg.getOptionsSymbolAllowlist().isEmpty()
                ? Set.copyOf(optionsSymbols) : cfg.getOptionsSymbolAllowlist();

        // Shared stock loop configurator
        Consumer<com.tradingapp.engine.TradingLoop> stockConfig = loop -> {
            loop.setStockTradingEnabled(true);
            loop.setTrailingStopPct(TRAILING_STOP_PCT);
            loop.setMaxLossPerTradePct(MAX_LOSS_PER_TRADE);
            loop.setCircuitBreakerPct(CIRCUIT_BREAKER_PCT);
            loop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
            loop.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
            loop.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
            loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
            loop.setMaxConcurrentStockPositions(MAX_POSITIONS);
            loop.setRegimeMaDays(REGIME_MA_DAYS);
            loop.setAccurateOptionsValuation(false);
        };

        // --- Pass 1: Stocks only ---
        System.out.println("Pass 1/3: Stocks only...");
        long t0 = System.currentTimeMillis();
        IntradayBacktestResult stocksResult = engine.run(
                new ArrayList<>(barsBySymbol.keySet()), barsBySymbol, 100_000.0,
                null, msg -> {}, Set.of(),
                loop -> { stockConfig.accept(loop); });
        System.out.printf("  Done in %.1fs  Return: %+.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                stocksResult.getTotalReturnPct(), stocksResult.getMaxDrawdownPct(),
                stocksResult.getTotalTrades(), stocksResult.getWins(), stocksResult.getLosses());

        // --- Pass 2: Options only ---
        System.out.println("Pass 2/3: Options only...");
        long t1 = System.currentTimeMillis();
        OptionsSignalRouter router2 = buildRouter(cfg, vixCache, optAllowlist);
        IntradayBacktestResult optionsResult = engine.run(
                new ArrayList<>(barsBySymbol.keySet()), barsBySymbol, 100_000.0,
                router2, msg -> {}, Set.of(),
                loop -> {
                    router2.setUptrendSupplier(loop::isUptrend);
                    loop.setStockTradingEnabled(false);
                    loop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
                    loop.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
                    loop.setAccurateOptionsValuation(true);
                    router2.setClosePositionsOnHalt(true);
                });
        System.out.printf("  Done in %.1fs  Return: %+.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n%n",
                (System.currentTimeMillis() - t1) / 1000.0,
                optionsResult.getTotalReturnPct(), optionsResult.getMaxDrawdownPct(),
                optionsResult.getTotalTrades(), optionsResult.getWins(), optionsResult.getLosses());

        // --- Pass 3: Combined ---
        System.out.println("Pass 3/3: Combined (stocks + options, shared capital)...");
        long t2 = System.currentTimeMillis();
        OptionsSignalRouter router3 = buildRouter(cfg, vixCache, optAllowlist);
        IntradayBacktestResult combinedResult = engine.run(
                new ArrayList<>(barsBySymbol.keySet()), barsBySymbol, 100_000.0,
                router3, msg -> {}, Set.of(),
                loop -> {
                    stockConfig.accept(loop);
                    router3.setUptrendSupplier(loop::isUptrend);
                    loop.setAccurateOptionsValuation(true);
                    router3.setClosePositionsOnHalt(true);
                });
        System.out.printf("  Done in %.1fs  Return: %+.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n%n",
                (System.currentTimeMillis() - t2) / 1000.0,
                combinedResult.getTotalReturnPct(), combinedResult.getMaxDrawdownPct(),
                combinedResult.getTotalTrades(), combinedResult.getWins(), combinedResult.getLosses());

        // --- Summary table ---
        double spyReturn = computeSpyReturn(barsBySymbol);
        System.out.printf("%-20s  %8s  %8s  %7s  %7s  %9s%n",
                "Mode", "Return", "MaxDD", "Trades", "WinRate", "vs SPY");
        System.out.println("-".repeat(68));
        printRow("Stocks only",   stocksResult,   spyReturn);
        printRow("Options only",  optionsResult,  spyReturn);
        printRow("Combined",      combinedResult, spyReturn);
        if (!Double.isNaN(spyReturn)) {
            System.out.printf("%-20s  %+7.2f%%%n", "SPY buy-and-hold", spyReturn);
        }

        double stocksPct   = stocksResult.getTotalReturnPct();
        double optionsPct  = optionsResult.getTotalReturnPct();
        double combinedPct = combinedResult.getTotalReturnPct();
        double additive    = stocksPct + optionsPct;
        System.out.printf("%nAdditive sum (stocks + options): %+.2f%%%n", additive);
        System.out.printf("Actual combined result:          %+.2f%%%n", combinedPct);
        System.out.printf("Interaction effect:              %+.2f pp (%s)%n",
                combinedPct - additive,
                combinedPct >= additive ? "complementary" : "competing for capital");
    }

    private static void printRow(String label, IntradayBacktestResult r, double spyReturn) {
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
        String alpha = Double.isNaN(spyReturn) ? "  n/a" : String.format("%+.2f pp", r.getTotalReturnPct() - spyReturn);
        System.out.printf("%-20s  %+7.2f%%  %7.2f%%  %7d  %6.1f%%  %9s%n",
                label, r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr, alpha);
    }

    private static OptionsSignalRouter buildRouter(AppConfig cfg, VixCache vixCache, Set<String> optAllowlist) {
        BlackScholesEngine bs = new BlackScholesEngine();
        bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsSignalRouter router = new OptionsSignalRouter(
                bs, new OptionsOrderExecutor(new Account(), null),
                new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
        router.setEnabledStrategies(cfg.getEnabledStrategies());
        router.setStopLossFrac(cfg.getOptionsStopLossFrac());
        router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
        if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
        if (cfg.getOptionsEntryStartTime() != null) router.setEntryStartTime(cfg.getOptionsEntryStartTime());
        router.setOptionsAllowlist(optAllowlist);
        router.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
        router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
        router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
        router.setReversalMinSignals(cfg.getReversalMinSignals());
        router.setProfitTarget(cfg.getProfitTarget());
        router.setEntryConfirmationTicks(1);
        router.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());
        return router;
    }

    private static double computeSpyReturn(Map<String, List<IntradayBar>> barsBySymbol) {
        List<IntradayBar> spyBars = barsBySymbol.get("SPY");
        if (spyBars == null || spyBars.size() < 2) return Double.NaN;
        spyBars.sort(Comparator.comparing(IntradayBar::time));
        return (spyBars.get(spyBars.size() - 1).close() - spyBars.get(0).close()) / spyBars.get(0).close() * 100.0;
    }
}
