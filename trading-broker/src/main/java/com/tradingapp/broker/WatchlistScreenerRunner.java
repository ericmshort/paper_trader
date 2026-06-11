package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.engine.TradingLoop;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;

import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Collectors;

/**
 * Screens all cached symbols to find the best 8 replacements for the removed net-losers.
 *
 * Methodology: for each candidate, run a paired [SPY, CANDIDATE] backtest under the
 * corrected D2 config (options-only, LONG_PUT min=3, uptrendSupplier wired) and compute
 * the return delta vs a SPY-alone baseline. A positive delta means the symbol adds value.
 *
 * Re-tests the 8 original losers (CVX,SBUX,REGN,ADBE,LOW,AVGO,NOW,HD) — they were
 * screened under the flawed backtest and may be viable under D2.
 *
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.WatchlistScreenerRunner
 */
public class WatchlistScreenerRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Current 22-symbol good list — no need to re-screen these
    private static final Set<String> CURRENT_GOOD = Set.of(
            "SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "META", "AMZN", "PLTR",
            "LLY", "ORCL", "RTX", "GS", "TSM", "TGT", "MA", "UNH", "GILD", "AXP", "MRNA", "COP", "XOM");

    // ETFs and clearly unsuitable symbols — skip entirely
    private static final Set<String> SKIP = Set.of(
            "IWM", "SQQQ", "SDS", "SPXU", "TQQQ",   // leveraged/inverse ETFs
            "AMC", "GME", "SNDL", "MARA", "RIOT", "NOK"); // meme/penny/no real options

    // Original losers — flagged for review in results
    private static final Set<String> WAS_LOSER = Set.of(
            "CVX", "SBUX", "REGN", "ADBE", "LOW", "AVGO", "NOW", "HD");

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in app.properties");
            System.exit(1);
        }

        // Discover cached symbols
        Path cacheRoot = Path.of(System.getProperty("user.home"), ".tradingapp", "bar-cache", "1min");
        List<String> allCached = Files.list(cacheRoot)
                .map(p -> p.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());

        List<String> candidates = allCached.stream()
                .filter(s -> !CURRENT_GOOD.contains(s))
                .filter(s -> !SKIP.contains(s))
                .collect(Collectors.toList());

        System.out.println("=== Watchlist Screener ===");
        System.out.println("Cached pool: " + allCached.size() + " symbols");
        System.out.println("Candidates:  " + candidates.size() + " — " + candidates);
        System.out.println("Re-testing " + WAS_LOSER.size() + " original losers under corrected D2 config.");

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(140);

        // Load all needed bar data (mostly from cache)
        System.out.println("\nLoading bar data for " + (candidates.size() + 1) + " symbols...");
        Map<String, List<IntradayBar>> allBars = new LinkedHashMap<>();
        List<String> toLoad = new ArrayList<>();
        toLoad.add("SPY");
        toLoad.addAll(candidates);

        int total = toLoad.size();
        int[] counter = {0};
        for (String sym : toLoad) {
            counter[0]++;
            final int cur = counter[0];
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) allBars.put(sym, bars);
            } catch (Exception e) {
                System.out.printf("[%d/%d] SKIP %s: %s%n", cur, total, sym, e.getMessage());
            }
        }
        System.out.println("Loaded " + allBars.size() + " symbols.");

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());

        // SPY-alone baseline
        System.out.println("\nRunning SPY-alone baseline...");
        long t0 = System.currentTimeMillis();
        IntradayBacktestResult spyOnly = runPair(engine, null, allBars, maxExposure);
        double spyReturn = spyOnly.getTotalReturnPct();
        System.out.printf("SPY-only: %.2f%%  (%.1fs)%n%n", spyReturn,
                (System.currentTimeMillis() - t0) / 1000.0);

        // Screen each candidate
        record SymResult(String symbol, double delta, double pairReturn,
                         int trades, int wins, double maxDD) {}
        List<SymResult> results = new ArrayList<>();

        int n = 0;
        for (String sym : candidates) {
            if (!allBars.containsKey(sym)) continue;
            n++;
            System.out.printf("[%d/%d] %-8s  ", n, candidates.size(), sym);
            System.out.flush();
            long ts = System.currentTimeMillis();
            IntradayBacktestResult r = runPair(engine, sym, allBars, maxExposure);
            double delta = r.getTotalReturnPct() - spyReturn;
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("pair=%+6.2f%%  delta=%+6.2f%%  trades=%3d  WR=%.0f%%  MaxDD=%.2f%%  (%.1fs)%s%n",
                    r.getTotalReturnPct(), delta,
                    r.getTotalTrades(), wr, r.getMaxDrawdownPct(),
                    (System.currentTimeMillis() - ts) / 1000.0,
                    WAS_LOSER.contains(sym) ? "  [WAS LOSER]" : "");
            results.add(new SymResult(sym, delta, r.getTotalReturnPct(),
                    r.getTotalTrades(), r.getWins(), r.getMaxDrawdownPct()));
        }

        results.sort(Comparator.comparingDouble(SymResult::delta).reversed());

        System.out.println("\n=== FULL RANKING (vs SPY-only baseline of " + String.format("%.2f", spyReturn) + "%) ===");
        System.out.printf("%-8s  %8s  %9s  %6s  %7s  %6s  %s%n",
                "Symbol", "Delta", "PairRet", "Trades", "WinRate", "MaxDD", "Notes");
        System.out.println("-".repeat(72));
        for (SymResult r : results) {
            double wr = r.trades() > 0 ? 100.0 * r.wins() / r.trades() : 0.0;
            String notes = WAS_LOSER.contains(r.symbol()) ? "[WAS LOSER]" : "";
            System.out.printf("%-8s  %+7.2f%%  %+8.2f%%  %6d  %6.1f%%  %5.2f%%  %s%n",
                    r.symbol(), r.delta(), r.pairReturn(), r.trades(), wr, r.maxDD(), notes);
        }

        System.out.println("\n=== TOP 8 REPLACEMENT CANDIDATES ===");
        System.out.printf("  %-8s  %8s  %9s  %6s  %7s  %s%n",
                "Symbol", "Delta", "PairRet", "Trades", "WinRate", "Notes");
        System.out.println("  " + "-".repeat(58));
        results.stream().limit(8).forEach(r -> {
            double wr = r.trades() > 0 ? 100.0 * r.wins() / r.trades() : 0.0;
            System.out.printf("  %-8s  %+7.2f%%  %+8.2f%%  %6d  %6.1f%%  %s%n",
                    r.symbol(), r.delta(), r.pairReturn(), r.trades(), wr,
                    WAS_LOSER.contains(r.symbol()) ? "[WAS LOSER — REDEEMED]" : "[NEW]");
        });

        long positiveCount = results.stream().filter(r -> r.delta() > 0).count();
        System.out.println("\n" + positiveCount + " of " + results.size()
                + " candidates show positive delta vs SPY-only baseline.");
        if (positiveCount < 8) {
            System.out.println("Only " + positiveCount + " positive-delta symbols found in cache.");
            System.out.println("Recommend fetching additional candidates before finalizing watchlist.");
        }
    }

    private static IntradayBacktestResult runPair(
            IntradayBacktestEngine engine,
            String candidate,
            Map<String, List<IntradayBar>> allBars,
            double maxExposure) throws Exception {

        List<String> watchlist = candidate == null
                ? List.of("SPY")
                : List.of("SPY", candidate);

        Map<String, List<IntradayBar>> bars = new LinkedHashMap<>();
        bars.put("SPY", allBars.get("SPY"));
        if (candidate != null) bars.put(candidate, allBars.get(candidate));

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_PUT"));
        // Empty allowlist = all symbols in watchlist may trade options
        router.setOptionsAllowlist(Set.of());
        router.setCallsDisabledSymbols(Set.of());
        router.setPutsDisabledSymbols(Set.of());
        router.setDowntrendPutMinSignals(3);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setStockTradingEnabled(false);
        };

        return engine.run(watchlist, bars, 100_000.0, router, msg -> {}, Set.of(), loopConfig);
    }
}
