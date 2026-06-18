package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.data.DayTraderWatchList;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares 4 new options strategies against the current baseline over the 2-year bar cache.
 *
 * Configs tested:
 *   0  Baseline   — HIGH_DELTA_SCALP, MOMENTUM_NEAR_TERM, LONG_CALL, LONG_PUT  (current production)
 *   1  +OPENING_BREAKOUT       — ORB-confirmed first-hour call/put, near-term ATM
 *   2  +STOCHASTIC_REVERSAL    — Stochastic %K extreme (<15 / >85), near-term ATM
 *   3  +RELATIVE_STRENGTH_DIV  — Stock out/underperforms SPY ≥1.5%, standard expiry
 *   4  +MACD_CROSSOVER         — EMA12/26 line crosses signal line, near-term ATM
 *   5  All 8 strategies        — full combination
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.NewStrategyComparisonRunner
 */
public class NewStrategyComparisonRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final Set<String> BASELINE = Set.of(
            "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT");

    private static final String[][] CONFIGS = {
        { "Baseline (current 4)",            },
        { "Baseline + OPENING_BREAKOUT",      "OPENING_BREAKOUT"            },
        { "Baseline + STOCHASTIC_REVERSAL",   "STOCHASTIC_REVERSAL"         },
        { "Baseline + RS_DIVERGENCE",         "RELATIVE_STRENGTH_DIVERGENCE"},
        { "Baseline + MACD_CROSSOVER",        "MACD_CROSSOVER"              },
        { "All 8 strategies",                 "OPENING_BREAKOUT", "STOCHASTIC_REVERSAL",
                                              "RELATIVE_STRENGTH_DIVERGENCE", "MACD_CROSSOVER" },
    };

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not configured.");
            System.exit(1);
        }

        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(800);

        System.out.println("=== New Strategy Comparison Runner ===");
        System.out.printf("Period: %s → %s%n%n", startDate, endDate);

        List<String> watchlist = DayTraderWatchList.SYMBOLS;
        Set<String> optAllowlist = new java.util.HashSet<>(watchlist);

        System.out.println("Loading bars from cache...");
        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = watchlist.size(), idx = 0;
        for (String sym : watchlist) {
            final int cur = ++idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }
        System.out.printf("Loaded %d/%d symbols.%n%n", barsBySymbol.size(), total);

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;

        IntradayBacktestEngine engine = new IntradayBacktestEngine(
                new IndicatorEngine(), new FeeCalculator())
                .setCollectEventLog(false);

        System.out.printf("%-42s  %9s  %9s  %6s  %5s  %5s%n",
                "Config", "Return%", "MaxDD%", "Trades", "Wins", "Losses");
        System.out.println("-".repeat(82));

        for (String[] config : CONFIGS) {
            String label = config[0];

            Set<String> strategies = new java.util.HashSet<>(BASELINE);
            for (int i = 1; i < config.length; i++) strategies.add(config[i]);

            OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
            OptionsSignalRouter router = new OptionsSignalRouter(
                    new BlackScholesEngine(), optExec, new Account(), new PriceHistory(),
                    msg -> {}, null);
            router.setMaxPortfolioExposure(maxExposure);
            router.setEnabledStrategies(strategies);
            router.setStopLossFrac(cfg.getOptionsStopLossFrac());
            router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
            if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
            router.setOptionsAllowlist(optAllowlist);
            router.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
            router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
            router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult result = engine.run(watchlist, barsBySymbol, 100_000.0, router,
                    msg -> {}, Set.of(),
                    loop -> {
                        loop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
                        loop.setStockTradingEnabled(false);
                        loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                        loop.setEarningsBlackoutDays(cfg.getEarningsBlackoutDays());
                    });
            long elapsed = System.currentTimeMillis() - t0;

            System.out.printf("%-42s  %+8.2f%%  %8.2f%%  %6d  %5d  %5d  (%ds)%n",
                    label,
                    result.getTotalReturnPct(),
                    result.getMaxDrawdownPct(),
                    result.getTotalTrades(),
                    result.getWins(),
                    result.getLosses(),
                    elapsed / 1000);
        }

        System.out.println();
        System.out.println("Note: all configs share the same bar data and include MACD+STOCHASTIC+RS");
        System.out.println("      signals in the vote count. The baseline return reflects those additions.");
    }
}
