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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Screens candidate symbols from MasterUniverse against the current 29-symbol options allowlist.
 *
 * Baseline: the 29 symbols currently in options.symbol.allowlist (live app.properties).
 * NFLX is excluded from options trading, leaving one open slot.
 *
 * For each candidate: run baseline + that symbol (added to both watchlist and allowlist),
 * measure return delta. Ranks all candidates and prints the top results.
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.SymbolScreeningRunner
 */
public class SymbolScreeningRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Current 29-symbol live options allowlist (from options.symbol.allowlist in app.properties)
    private static final List<String> BASELINE_WATCHLIST = List.of(
            "SPY", "NOC", "NVDA", "MSFT", "COST",
            "VRTX", "AMGN", "CRWD", "GS", "PLTR",
            "LRCX", "DE", "ORCL", "LLY", "BLK",
            "NOW", "MA", "REGN", "META", "AMAT",
            "KLAC", "CAT", "UNH", "LMT", "JPM",
            "MU", "HD", "MCD", "V");

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not configured.");
            System.exit(1);
        }

        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(800);

        // Candidates: MasterUniverse symbols not in the current allowlist.
        // Exclude QQQ/TSLA (always options-excluded) and NFLX (currently excluded from options).
        Set<String> current = new HashSet<>(BASELINE_WATCHLIST);
        current.addAll(Set.of("QQQ", "TSLA", "NFLX")); // options-ineligible
        List<String> candidates = new ArrayList<>();
        for (String sym : MasterUniverse.SYMBOLS) {
            if (!current.contains(sym)) candidates.add(sym);
        }

        System.out.println("=== Symbol Screening Runner ===");
        System.out.println("Baseline: " + BASELINE_WATCHLIST.size() + "-symbol options-only watchlist");
        System.out.println("Candidates: " + candidates.size() + " symbols from MasterUniverse");
        System.out.println("Period  : " + startDate + " → " + endDate);
        System.out.println();
        System.out.println("Loading bars from cache...");

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();

        // Load baseline + all candidates (all cached from DynamicWatchlistRunner)
        List<String> allNeeded = new ArrayList<>(BASELINE_WATCHLIST);
        allNeeded.addAll(candidates);
        int total = allNeeded.size(), idx = 0;
        for (String sym : allNeeded) {
            final int cur = ++idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }
        System.out.printf("Loaded %d symbols. Running screening...%n%n", barsBySymbol.size());

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator())
                .setCollectEventLog(false);
        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;

        // Run baseline first
        System.out.println("=== BASELINE (29 symbols, current live allowlist) ===");
        double baselineReturn = runOnce(engine, cfg, maxExposure, barsBySymbol,
                BASELINE_WATCHLIST, Set.copyOf(BASELINE_WATCHLIST));
        System.out.printf("Baseline return: %.2f%%%n%n", baselineReturn);

        // Screen each candidate
        record CandidateResult(String symbol, double returnPct, double delta,
                               double maxDD, int trades, double winRate) {}
        List<CandidateResult> results = new ArrayList<>();

        int done = 0;
        for (String sym : candidates) {
            if (!barsBySymbol.containsKey(sym)) {
                System.out.println("SKIP " + sym + ": no bar data");
                continue;
            }
            List<String> wl = new ArrayList<>(BASELINE_WATCHLIST);
            wl.add(sym);
            Set<String> opts = new HashSet<>(BASELINE_WATCHLIST);
            opts.add(sym);

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = runFull(engine, cfg, maxExposure, barsBySymbol, wl, opts);
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            double delta = r.getTotalReturnPct() - baselineReturn;
            done++;
            System.out.printf("[%d/%d] %-6s  Return=%.2f%%  Delta=%+.2f%%  MaxDD=%.2f%%  Trades=%d  WR=%.1f%%  (%.1fs)%n",
                    done, candidates.size(), sym,
                    r.getTotalReturnPct(), delta, r.getMaxDrawdownPct(),
                    r.getTotalTrades(), wr,
                    (System.currentTimeMillis() - t0) / 1000.0);
            results.add(new CandidateResult(sym, r.getTotalReturnPct(), delta,
                    r.getMaxDrawdownPct(), r.getTotalTrades(), wr));
        }

        // Sort by delta descending
        results.sort(Comparator.comparingDouble(CandidateResult::delta).reversed());

        System.out.println();
        System.out.println("=== SCREENING RESULTS (ranked by return delta vs baseline) ===");
        System.out.printf("Baseline: %.2f%% return  (29 symbols, current live allowlist)%n%n", baselineReturn);
        System.out.printf("%-8s  %8s  %8s  %8s  %7s  %7s%n",
                "Symbol", "Return", "Delta", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(60));
        int rank = 0;
        for (CandidateResult cr : results) {
            rank++;
            String marker = rank <= 5 ? " ◄ TOP 5" : "";
            System.out.printf("%-8s  %7.2f%%  %+7.2f%%  %7.2f%%  %7d  %6.1f%%%s%n",
                    cr.symbol(), cr.returnPct(), cr.delta(), cr.maxDD(),
                    cr.trades(), cr.winRate(), marker);
        }

        System.out.println();
        System.out.println("=== TOP CANDIDATES TO REPLACE NFLX ===");
        results.stream().limit(5).forEach(cr ->
                System.out.printf("  %-6s  delta=%+.2f%%  return=%.2f%%  WR=%.1f%%%n",
                        cr.symbol(), cr.delta(), cr.returnPct(), cr.winRate()));
    }

    private static double runOnce(IntradayBacktestEngine engine, AppConfig cfg, double maxExposure,
                                   Map<String, List<IntradayBar>> bars,
                                   List<String> watchlist, Set<String> allowlist) throws Exception {
        return runFull(engine, cfg, maxExposure, bars, watchlist, allowlist).getTotalReturnPct();
    }

    private static IntradayBacktestResult runFull(IntradayBacktestEngine engine, AppConfig cfg,
                                                   double maxExposure,
                                                   Map<String, List<IntradayBar>> bars,
                                                   List<String> watchlist, Set<String> allowlist) throws Exception {
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT"));
        router.setStopLossFrac(cfg.getOptionsStopLossFrac());
        router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
        if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
        router.setOptionsAllowlist(allowlist);
        router.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
        router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
        router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());

        return engine.run(watchlist, bars, 100_000.0, router, msg -> {},
                Set.of(), loop -> {
                    router.setUptrendSupplier(loop::isUptrend);
                    loop.setStockTradingEnabled(false);
                    loop.setMaxConcurrentStockPositions(10);
                    loop.setAvoidOvernightHolds(false);
                });
    }
}
