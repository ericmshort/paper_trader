package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.data.MasterUniverse;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares four nightly watchlist ranking strategies over a 2-year backtest window.
 *
 * Strategies:
 *   STATIC     — fixed 30-symbol Config-D watchlist (baseline, no rotation)
 *   MOMENTUM   — top 28 by 5-day return relative to SPY, anchors SPY+QQQ always included
 *   VOLATILITY — top 28 by 20-day annualized realized volatility, anchors always included
 *   COMPOSITE  — top 28 by 50% normalized-momentum + 50% normalized-vol, anchors always included
 *
 * All four use Config-D options settings:
 *   HIGH_DELTA_SCALP + MOMENTUM_NEAR_TERM + LONG_CALL + LONG_PUT
 *   stop-loss=0.30, entry-cutoff=14:45, avoid-overnight, holiday guard
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.DynamicWatchlistRunner
 */
public class DynamicWatchlistRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not configured in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(800);

        System.out.println("=== Dynamic Watchlist Runner ===");
        System.out.println("Comparing STATIC / MOMENTUM / VOLATILITY / COMPOSITE ranking strategies");
        System.out.println("Period  : " + startDate + " → " + endDate);
        System.out.println("Universe: " + MasterUniverse.SYMBOLS.size() + " symbols (bars cached per-day under ~/.tradingapp/bar-cache)");
        System.out.println();
        System.out.println("Fetching bars — previously cached symbols load instantly, new ones fetch from Alpaca...");

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = MasterUniverse.SYMBOLS.size(), idx = 0;
        for (String sym : MasterUniverse.SYMBOLS) {
            final int cur = ++idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }
        System.out.printf("%nLoaded bar data for %d / %d symbols.%n%n", barsBySymbol.size(), total);

        // Build ranking engine and precompute all daily rankings up front
        System.out.println("Computing nightly rankings for all strategies...");
        DailyRankingEngine rankingEngine = new DailyRankingEngine(barsBySymbol);

        Map<DailyRankingEngine.Strategy, Map<LocalDate, Set<String>>> watchlistsByStrategy =
                new EnumMap<>(DailyRankingEngine.Strategy.class);
        Map<DailyRankingEngine.Strategy, Map<LocalDate, Set<String>>> allowlistsByStrategy =
                new EnumMap<>(DailyRankingEngine.Strategy.class);
        for (DailyRankingEngine.Strategy s : DailyRankingEngine.Strategy.values()) {
            watchlistsByStrategy.put(s, rankingEngine.buildDailyWatchlists(s));
            allowlistsByStrategy.put(s, rankingEngine.buildDailyAllowlists(s));
        }
        System.out.println("Rankings ready. Running backtests...\n");

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator())
                .setCollectEventLog(false);
        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        List<String> allSymbols = new ArrayList<>(barsBySymbol.keySet());

        record RunResult(DailyRankingEngine.Strategy strategy, IntradayBacktestResult result) {}
        List<RunResult> results = new ArrayList<>();

        for (DailyRankingEngine.Strategy strategy : DailyRankingEngine.Strategy.values()) {
            System.out.println("=== " + strategy.name() + " ===");

            Map<LocalDate, Set<String>> watchlists = watchlistsByStrategy.get(strategy);
            Map<LocalDate, Set<String>> allowlists = allowlistsByStrategy.get(strategy);

            OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
            OptionsSignalRouter router = new OptionsSignalRouter(
                    new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
            router.setMaxPortfolioExposure(maxExposure);
            router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT"));
            router.setStopLossFrac(cfg.getOptionsStopLossFrac());
            router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
            if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
            router.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
            router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
            router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
            // Holiday guard stays on (default true) — same as Config D
            router.setDailyAllowlistProvider(date -> {
                Set<String> al = allowlists.get(date);
                return al != null ? al : Set.of();
            });

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(
                    allSymbols, barsBySymbol, 100_000.0, router, msg -> {},
                    Set.of(),
                    loop -> {
                        router.setUptrendSupplier(loop::isUptrend);
                        loop.setStockTradingEnabled(false);
                        loop.setMaxConcurrentStockPositions(10);
                        loop.setAvoidOvernightHolds(false);
                    },
                    date -> {
                        Set<String> wl = watchlists.get(date);
                        return wl != null ? wl : Set.of();
                    });

            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            System.out.printf("Done in %.1fs  Return=%.2f%%  MaxDD=%.2f%%  Trades=%d  WR=%.1f%%%n%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
            results.add(new RunResult(strategy, r));
        }

        // Comparison table
        RunResult staticRun = results.stream()
                .filter(rr -> rr.strategy() == DailyRankingEngine.Strategy.STATIC)
                .findFirst().orElseThrow();

        System.out.println("=== RANKING STRATEGY COMPARISON ===");
        System.out.printf("%-12s  %8s  %8s  %7s  %7s  %12s  %10s%n",
                "Strategy", "Return", "MaxDD", "Trades", "WinRate", "Balance", "vs STATIC");
        System.out.println("-".repeat(82));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            double delta = r.getTotalReturnPct() - staticRun.result().getTotalReturnPct();
            String deltaStr = rr.strategy() == DailyRankingEngine.Strategy.STATIC
                    ? "  (baseline)"
                    : String.format("%+8.2fpp", delta);
            System.out.printf("%-12s  %7.2f%%  %7.2f%%  %7d  %6.1f%%  $%,12.2f  %s%n",
                    rr.strategy().name(),
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), wr, r.getFinalBalance(), deltaStr);
        }
    }
}
