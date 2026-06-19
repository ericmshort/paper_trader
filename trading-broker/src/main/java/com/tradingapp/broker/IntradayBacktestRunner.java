package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.MasterUniverse;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.BacktestDataPoint;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Standalone CLI runner: fetches ~100 trading days of 1-min bars, replays them through
 * the real TradingLoop/OptionsSignalRouter stack, and writes a detailed analysis report.
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.IntradayBacktestRunner
 */
public class IntradayBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ET);

    record RunResult(String label, IntradayBacktestResult result, Set<String> strategies,
                     List<String> watchlist, double profitTarget, int reversalMinSignals,
                     int reversalMinConsecutive, double dailyLossLimitPct,
                     double overnightMinPremiumFrac, boolean avoidOvernightHolds) {}

    // Lightweight summary kept between runs in strategy-compare mode (avoids holding full result in memory)
    record RunSummary(String label, Set<String> strategies, double returnPct, double maxDd, int trades, int wins, int losses) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-report.txt");
        Files.createDirectories(reportPath.getParent());

        System.out.println("=== Intraday Backtest Runner ===");
        System.out.println("Report will be written to: " + reportPath);

        // --- Fetch bars ---
        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        String mode = System.getProperty("backtest.mode", "");

        // today-compare extends endDate to today so today's bars are fetched and simulated
        LocalDate endDate = LocalDate.now(ET);
        if (!"today-compare".equals(mode)) {
            endDate = endDate.minusDays(1);
            while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                endDate = endDate.minusDays(1);
            }
        }
        LocalDate startDate = endDate.minusDays(800);

        // Empty = confirmation run; pass comma-separated symbols via -Dbacktest.candidates=AMD,AVGO,...
        String candidatesStr = System.getProperty("backtest.candidates", "");
        List<String> newCandidates = candidatesStr.isBlank() ? List.of()
                : Arrays.stream(candidatesStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        List<String> baseWatchlist = new ArrayList<>(
                cfg.getOptionsWatchlist().isEmpty() ? DayTraderWatchList.SYMBOLS : cfg.getOptionsWatchlist());
        List<String> allSymbols = new ArrayList<>(baseWatchlist);
        allSymbols.addAll(newCandidates);

        // For symbol-scan mode, also fetch all MasterUniverse symbols not already covered
        Set<String> SCAN_EXCLUDED = Set.of("QQQ", "TSLA", "COIN", "GD", "ADBE", "TGT", "AMZN", "PANW");
        if ("symbol-scan".equals(mode)) {
            for (String s : MasterUniverse.SYMBOLS) {
                if (!allSymbols.contains(s) && !SCAN_EXCLUDED.contains(s)) allSymbols.add(s);
            }
        }

        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();

        System.out.println("Fetching bars " + startDate + " -> " + endDate + " for " + allSymbols.size() + " symbols...");
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
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }

        if (barsBySymbol.isEmpty()) {
            System.err.println("ERROR: no bar data fetched");
            System.exit(1);
        }
        System.out.println("Fetched data for " + barsBySymbol.size() + " symbols. Running sim...");

        VixCache vixCache = new VixCache();
        vixCache.load(startDate, endDate);

        // Entry-start delay: from -Dbacktest.entryStartTime=HH:mm or app.properties
        String entryStartProp = System.getProperty("backtest.entryStartTime", "");
        LocalTime backtestEntryStartTime = entryStartProp.isBlank()
                ? cfg.getOptionsEntryStartTime()
                : LocalTime.parse(entryStartProp, java.time.format.DateTimeFormatter.ofPattern("H:mm"));

        // Optional strategy exclusions: -Dbacktest.disableStrategies=OPENING_BREAKOUT,ZERO_DTE,...
        String disableProp = System.getProperty("backtest.disableStrategies", "");
        Set<String> disabledStrategies = disableProp.isBlank() ? Set.of()
                : Arrays.stream(disableProp.split(",")).map(String::trim).collect(Collectors.toSet());

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());

        Set<String> BASE_OPTS      = cfg.getOptionsSymbolAllowlist();
        Set<String> CALLS_DISABLED = cfg.getOptionsCallsDisabled();

        // Candidates for symbol-scan mode: MasterUniverse symbols not in current allowlist, with cached bars
        List<String> scanCandidates = "symbol-scan".equals(mode)
                ? MasterUniverse.SYMBOLS.stream()
                        .filter(s -> !BASE_OPTS.contains(s) && !SCAN_EXCLUDED.contains(s) && barsBySymbol.containsKey(s))
                        .collect(Collectors.toList())
                : List.of();

        double defaultLossLimitPct = cfg.getDailyLossLimitPct();
        record RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                      double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive, double profitTarget,
                      double overnightMinPremiumFrac, Boolean avoidOvernightHolds) {
            // reversal-compare: explicit reversal settings, default profit target, inherit floor/overnight from cfg
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                   double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, reversalMinSignals, reversalMinConsecutive, 2.5, -1, null);
            }
            // most modes: optimal reversal settings + default profit target, inherit floor/overnight from cfg
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies, double dailyLossLimitPct) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, 5, 2, 2.5, -1, null);
            }
            // profit-target-compare: explicit profit target, inherit floor/overnight
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                   double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive, double profitTarget) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, reversalMinSignals, reversalMinConsecutive, profitTarget, -1, null);
            }
            // overnight-floor-compare: explicit floor, inherit overnight
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                   double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive, double profitTarget,
                   double overnightMinPremiumFrac) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, reversalMinSignals, reversalMinConsecutive, profitTarget, overnightMinPremiumFrac, null);
            }
        }

        java.util.List<RunCfg> runs = new java.util.ArrayList<>();

        if ("strategy-compare".equals(mode)) {
            // Run each strategy individually, then the current config as the combined baseline
            List<String> ALL_STRATEGIES = List.of(
                    "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT",
                    "ZERO_DTE", "OPENING_BREAKOUT", "STOCHASTIC_REVERSAL",
                    "RELATIVE_STRENGTH_DIVERGENCE", "MACD_CROSSOVER");
            for (String s : ALL_STRATEGIES) {
                runs.add(new RunCfg(String.format("%-35s", s), baseWatchlist, BASE_OPTS, Set.of(s), defaultLossLimitPct));
            }
            // Add the current live config as the combined baseline at the end
            runs.add(new RunCfg("CURRENT CONFIG (combined)", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else if ("loss-limit-compare".equals(mode)) {
            runs.add(new RunCfg("Daily loss limit  5% (baseline)", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), 5.0));
            runs.add(new RunCfg("Daily loss limit 10%",            baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), 10.0));
        } else if ("add-candidates".equals(mode)) {
            // Clean 2-run compare: baseline vs baseline + all newCandidates combined
            List<String> allWl = new ArrayList<>(baseWatchlist);
            java.util.HashSet<String> allOpts = new java.util.HashSet<>(BASE_OPTS);
            for (String sym : newCandidates) {
                if (barsBySymbol.containsKey(sym)) { allWl.add(sym); allOpts.add(sym); }
            }
            runs.add(new RunCfg("Baseline: " + baseWatchlist.size() + " symbols", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
            runs.add(new RunCfg("All candidates: " + allWl.size() + " symbols (" + String.join(",", newCandidates) + ")", allWl, Set.copyOf(allOpts), cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else if ("symbol-scan".equals(mode)) {
            // Run base watchlist + all candidate symbols to rank which to add next
            System.out.println("Symbol scan: testing " + scanCandidates.size() + " candidates: " + scanCandidates);
            List<String> scanWatchlist = new ArrayList<>(baseWatchlist);
            scanWatchlist.addAll(scanCandidates);
            Set<String> scanAllowlist = new java.util.HashSet<>(BASE_OPTS);
            scanAllowlist.addAll(scanCandidates);
            runs.add(new RunCfg("SYMBOL SCAN: " + scanCandidates.size() + " candidates",
                    scanWatchlist, Set.copyOf(scanAllowlist), cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else if ("reversal-compare".equals(mode)) {
            // Test signal reversal sensitivity: vary minSignals and minConsecutive thresholds.
            // Baseline is the current hardcoded behaviour (3 signals, 2 consecutive ticks).
            record RevCfg(int minSig, int minCons) {}
            List<RevCfg> revCfgs = List.of(
                new RevCfg(3, 2),   // current baseline
                new RevCfg(3, 3),   // require 3 consecutive ticks
                new RevCfg(4, 2),   // require 4 signals
                new RevCfg(4, 3),   // 4 signals + 3 ticks
                new RevCfg(5, 2),   // require 5 signals
                new RevCfg(99, 2)   // effectively disabled
            );
            for (RevCfg rc : revCfgs) {
                String label = rc.minSig() >= 99
                        ? String.format("%-38s", "reversal DISABLED")
                        : String.format("%-38s", "signals>=" + rc.minSig() + " for " + rc.minCons() + " ticks (current: signals>=3 for 2)");
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, rc.minSig(), rc.minCons()));
            }
        } else if ("profit-target-compare".equals(mode)) {
            // Test profit target sensitivity: vary the multiple at which we close a winner.
            // Current default is 2.0x (100% gain). Lower values fire more often; higher values let winners run.
            double currentPt = cfg.getProfitTarget();
            for (double pt : new double[]{1.25, 1.50, 1.75, 2.00, 2.50}) {
                String label = String.format("%-40s", String.format("profit target %.2fx (current: %.2fx)", pt, currentPt));
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, pt));
            }
            runs.add(new RunCfg(String.format("%-40s", "profit target DISABLED            "),
                    baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct, 5, 2, 99.0));
        } else if ("profit-target-high".equals(mode)) {
            // Continuation of profit-target-compare for the high-value cases (run separately to avoid OOM).
            double currentPt = cfg.getProfitTarget();
            for (double pt : new double[]{2.50}) {
                String label = String.format("%-40s", String.format("profit target %.2fx (current: %.2fx)", pt, currentPt));
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, pt));
            }
            runs.add(new RunCfg(String.format("%-40s", "profit target DISABLED            "),
                    baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct, 5, 2, 99.0));
        } else if ("overnight-floor-compare".equals(mode)) {
            double currentFloor = cfg.getOvernightMinPremiumFrac();
            for (double frac : new double[]{0.35, 0.40, 0.50, 0.60, 0.70, 0.80}) {
                String tag = frac == currentFloor ? " (current)" : "";
                String label = String.format("%-44s", String.format(
                        "overnight floor %.0f%%%s", frac * 100, tag));
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, cfg.getProfitTarget(), frac));
            }
        } else if ("avoid-overnight-compare".equals(mode)) {
            runs.add(new RunCfg(String.format("%-44s", "avoidOvernightHolds=false (baseline)"),
                    baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct,
                    5, 2, cfg.getProfitTarget(), cfg.getOvernightMinPremiumFrac(), false));
            runs.add(new RunCfg(String.format("%-44s", "avoidOvernightHolds=true  (EOD force-close)"),
                    baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct,
                    5, 2, cfg.getProfitTarget(), cfg.getOvernightMinPremiumFrac(), true));
        } else if ("orb-optimize".equals(mode)) {
            // OPENING_BREAKOUT alone is the anchor; test each complement pair/triple.
            Set<String> orb = Set.of("OPENING_BREAKOUT");
            runs.add(new RunCfg(String.format("%-44s", "ORB alone"),
                    baseWatchlist, BASE_OPTS, orb, defaultLossLimitPct));
            for (String partner : List.of("MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT",
                                          "MACD_CROSSOVER", "STOCHASTIC_REVERSAL",
                                          "RELATIVE_STRENGTH_DIVERGENCE", "HIGH_DELTA_SCALP")) {
                Set<String> s = new java.util.LinkedHashSet<>(orb);
                s.add(partner);
                runs.add(new RunCfg(String.format("%-44s", "ORB + " + partner),
                        baseWatchlist, BASE_OPTS, s, defaultLossLimitPct));
            }
            // Best-looking 2-strategy combos paired with ORB
            for (String[] pair : new String[][]{
                    {"MOMENTUM_NEAR_TERM", "MACD_CROSSOVER"},
                    {"MOMENTUM_NEAR_TERM", "STOCHASTIC_REVERSAL"},
                    {"MACD_CROSSOVER", "STOCHASTIC_REVERSAL"}}) {
                Set<String> s = new java.util.LinkedHashSet<>(orb);
                s.add(pair[0]); s.add(pair[1]);
                runs.add(new RunCfg(String.format("%-44s", "ORB + " + pair[0] + " + " + pair[1]),
                        baseWatchlist, BASE_OPTS, s, defaultLossLimitPct));
            }
            // Current full config as comparison baseline
            runs.add(new RunCfg(String.format("%-44s", "CURRENT CONFIG (combined)"),
                    baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else if ("today-compare".equals(mode)) {
            runs.add(new RunCfg("TODAY " + endDate + ": current config", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else if (newCandidates.isEmpty()) {
            // Watchlist is at capacity — single confirmation run with all current symbols
            runs.add(new RunCfg("FINAL: all " + baseWatchlist.size() + " symbols (capacity confirmation)", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else {
            // Screening mode — baseline + one run per candidate + combined
            runs.add(new RunCfg("A: Baseline", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
            for (String sym : newCandidates) {
                List<String> wl = new ArrayList<>(baseWatchlist);
                if (barsBySymbol.containsKey(sym)) wl.add(sym);
                java.util.HashSet<String> opts = new java.util.HashSet<>(BASE_OPTS);
                opts.add(sym);
                runs.add(new RunCfg(String.format("%-6s", sym) + ": baseline + " + sym, wl, Set.copyOf(opts), cfg.getEnabledStrategies(), defaultLossLimitPct));
            }
            List<String> allWl = new ArrayList<>(baseWatchlist);
            java.util.HashSet<String> allOpts = new java.util.HashSet<>(BASE_OPTS);
            for (String sym : newCandidates) {
                if (barsBySymbol.containsKey(sym)) { allWl.add(sym); allOpts.add(sym); }
            }
            runs.add(new RunCfg("ALL: baseline + all candidates", allWl, Set.copyOf(allOpts), cfg.getEnabledStrategies(), defaultLossLimitPct));
        }

        java.util.List<RunResult> results = new java.util.ArrayList<>();
        java.util.List<RunSummary> summaries = new java.util.ArrayList<>();

        for (RunCfg cfg2 : runs) {
            System.out.println("\n=== " + cfg2.label() + " ===");
            OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
            BlackScholesEngine bs = new BlackScholesEngine();
            bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
            OptionsSignalRouter router = new OptionsSignalRouter(
                    bs, optExec, new Account(), new PriceHistory(), msg -> {}, null);
            router.setMaxPortfolioExposure(maxExposure);
            Set<String> runStrategies = disabledStrategies.isEmpty() ? cfg2.strategies()
                    : cfg2.strategies().stream().filter(s -> !disabledStrategies.contains(s))
                            .collect(Collectors.toSet());
            router.setEnabledStrategies(runStrategies);
            if (backtestEntryStartTime != null) router.setEntryStartTime(backtestEntryStartTime);
            router.setStopLossFrac(cfg.getOptionsStopLossFrac());
            boolean avoidOvernight = cfg2.avoidOvernightHolds() != null ? cfg2.avoidOvernightHolds() : cfg.isAvoidOvernightHolds();
            router.setAvoidOvernightHolds(avoidOvernight);
            if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
            router.setOptionsAllowlist(cfg2.optAllowlist());
            router.setCallsDisabledSymbols(CALLS_DISABLED);
            router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
            router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
            router.setReversalMinSignals(cfg2.reversalMinSignals());
            router.setReversalMinConsecutive(cfg2.reversalMinConsecutive());
            router.setProfitTarget(cfg2.profitTarget());
            router.setEntryConfirmationTicks(1); // backtest uses 1-min bars; no tick confirmation needed
            double floorFrac = cfg2.overnightMinPremiumFrac() >= 0 ? cfg2.overnightMinPremiumFrac() : cfg.getOvernightMinPremiumFrac();
            router.setOvernightMinPremiumFrac(floorFrac);

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(cfg2.watchlist(), barsBySymbol, 100_000.0, router, msg -> {},
                    Set.of(), loop -> {
                        router.setUptrendSupplier(loop::isUptrend);
                        loop.setStockTradingEnabled(false);
                        loop.setMaxConcurrentStockPositions(10);
                        loop.setAvoidOvernightHolds(false);
                        loop.setDailyLossLimitPct(cfg2.dailyLossLimitPct() / 100.0);
                        loop.setAccurateOptionsValuation(true);
                        router.setClosePositionsOnHalt(true);
                    });
            System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), r.getWins(), r.getLosses());

            // Keep full results for single-run and small 2-run compares; use summaries for large multi-run modes.
            boolean keepFull = newCandidates.isEmpty() || cfg2.label().startsWith("ALL:")
                    || "loss-limit-compare".equals(mode) || "add-candidates".equals(mode)
                    || "today-compare".equals(mode);
            if (!keepFull || "strategy-compare".equals(mode) || "reversal-compare".equals(mode)
                    || "overnight-floor-compare".equals(mode) || "avoid-overnight-compare".equals(mode)
                    || "orb-optimize".equals(mode)) {
                summaries.add(new RunSummary(cfg2.label().trim(), cfg2.strategies(),
                        r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                        r.getTotalTrades(), r.getWins(), r.getLosses()));
            } else {
                results.add(new RunResult(cfg2.label(), r, cfg2.strategies(),
                        cfg2.watchlist(), cfg2.profitTarget(), cfg2.reversalMinSignals(),
                        cfg2.reversalMinConsecutive(), cfg2.dailyLossLimitPct(),
                        floorFrac, avoidOvernight));
            }
        }

        if ("strategy-compare".equals(mode)) {
            summaries.sort(Comparator.comparingDouble(RunSummary::returnPct).reversed());
            System.out.printf("%n%-37s  %8s  %8s  %7s  %7s%n", "Strategy", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(80));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-37s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("orb-optimize".equals(mode)) {
            summaries.sort(Comparator.comparingDouble(RunSummary::returnPct).reversed());
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "ORB Combination", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("reversal-compare".equals(mode)) {
            System.out.printf("%n%-42s  %8s  %8s  %7s  %7s%n", "Reversal Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(85));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-42s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("avoid-overnight-compare".equals(mode)) {
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "avoidOvernightHolds Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("overnight-floor-compare".equals(mode)) {
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Overnight Floor Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            double[] floors = new double[]{0.35, 0.40, 0.50, 0.60, 0.70, 0.80};
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate, floors);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("profit-target-compare".equals(mode) || "profit-target-high".equals(mode)) {
            System.out.printf("%n%-42s  %8s  %8s  %7s  %7s%n", "Profit Target Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(85));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-42s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        // Print summary table — summaries first (intermediate runs), then full results
        System.out.printf("%-40s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(80));
        for (RunSummary s : summaries) {
            double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
            System.out.printf("%-40s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
        }
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("%-40s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), wr);
        }

        // Write report for the highest-return full result (or the only result if single-run)
        if (!results.isEmpty()) {
            RunResult best = results.stream()
                    .max(Comparator.comparingDouble(rr -> rr.result().getTotalReturnPct()))
                    .orElseThrow();
            String runTs = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
            Path timedPath = reportPath.getParent().resolve("backtest-" + runTs + ".txt");
            for (Path p : List.of(timedPath, reportPath)) {
                try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(p))) {
                    writeReport(out, best.result(), startDate, endDate, cfg, best);
                }
            }
            System.out.println("\nReport written for best run (" + best.label().trim() + "):");
            System.out.println("  Timestamped : " + timedPath);
            System.out.println("  Latest      : " + reportPath);

            if ("symbol-scan".equals(mode) && !scanCandidates.isEmpty()) {
                printCandidateRanking(best.result(), new java.util.HashSet<>(scanCandidates));
            }

            appendHistory(cfg, results, startDate, endDate);
        }
        if (!summaries.isEmpty()) {
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("History appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
        }

        if ("today-compare".equals(mode) && !results.isEmpty()) {
            printTodayComparison(results.get(0).result(), endDate);
        }
    }

    private static void printTodayComparison(IntradayBacktestResult result, LocalDate today) {
        System.out.println("\n=== TODAY vs LIVE COMPARISON: " + today + " ===");

        // --- Backtest: extract today's single-day P&L from equity curve ---
        List<BacktestDataPoint> curve = result.getEquityCurve();
        double simDayPct = Double.NaN;
        for (int i = 0; i < curve.size(); i++) {
            if (curve.get(i).getDate().equals(today)) {
                double prev = i > 0 ? curve.get(i - 1).getPortfolioValue() : 100_000.0;
                simDayPct = (curve.get(i).getPortfolioValue() - prev) / prev * 100.0;
                break;
            }
        }

        // Count today's simulated round-trips
        long dayStart = today.atStartOfDay(ET).toInstant().toEpochMilli();
        long dayEnd   = today.plusDays(1).atStartOfDay(ET).toInstant().toEpochMilli();
        Map<String, List<TransactionRecord>> simBuyStack = new HashMap<>();
        int simRoundTrips = 0, simRoundWins = 0;
        List<String> simTxLines = new ArrayList<>();
        List<TransactionRecord> sorted = new ArrayList<>(result.getTrades());
        sorted.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));
        for (TransactionRecord r : sorted) {
            if (r.getTimestamp() < dayStart || r.getTimestamp() >= dayEnd) continue;
            String sym = r.getSymbol();
            String action = r.getAction().name();
            boolean isBuy  = action.equals("BUY")  || action.equals("CALL_BUY")  || action.equals("PUT_BUY");
            boolean isSell = action.equals("SELL") || action.equals("CALL_SELL") || action.equals("PUT_SELL");
            if (isBuy) {
                simBuyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(r);
            } else if (isSell) {
                List<TransactionRecord> buys = simBuyStack.get(sym);
                if (buys != null && !buys.isEmpty()) {
                    TransactionRecord buy = buys.remove(0);
                    boolean isOpts = action.startsWith("CALL_") || action.startsWith("PUT_");
                    double pnl = (r.getPricePerUnit() - buy.getPricePerUnit()) * r.getQuantity() * (isOpts ? 100.0 : 1.0)
                            - buy.getFeeCharged() - r.getFeeCharged();
                    simRoundTrips++;
                    if (pnl >= 0) simRoundWins++;
                    String type = action.contains("CALL") ? "CALL" : action.contains("PUT") ? "PUT" : "STK";
                    simTxLines.add(String.format("  SIM   %-6s %-4s  entry=$%7.2f exit=$%7.2f x%d  P&L=$%8.2f  %s",
                            sym, type, buy.getPricePerUnit(), r.getPricePerUnit(), r.getQuantity(), pnl,
                            r.getReason() != null ? r.getReason() : ""));
                }
            }
        }
        int simOpenAtEnd = simBuyStack.values().stream().mapToInt(List::size).sum();
        int simTotalOpened = simRoundTrips + simOpenAtEnd;

        // --- Live log: parse actual trade entries and closes ---
        Path logPath = Path.of(System.getProperty("user.home"), ".tradingapp", "day-trader",
                "events-" + today + ".log");
        int liveOpened = 0, liveClosed = 0;
        double livePnlKnown = 0;
        boolean liveHalted = false;
        String haltTime = null;
        List<String> liveTxLines = new ArrayList<>();
        record LiveEntry(String sym, int qty, double prem) {}
        java.util.Deque<Object> unused = new java.util.ArrayDeque<>(); // keep compiler happy
        Map<String, java.util.Deque<LiveEntry>> liveBuyStack = new HashMap<>();

        try {
            java.util.regex.Pattern entryPat = java.util.regex.Pattern.compile(
                    "^\\[([\\d: -]+)\\]\\s+(\\S+).*(?:CALL|PUT) K=.*x(\\d+)\\s+prem=([\\d.]+)");
            java.util.regex.Pattern closePat = java.util.regex.Pattern.compile(
                    "^\\[([\\d: -]+)\\]\\s+(\\S+).*closed:.*SELL prem=([\\d.]+)");
            java.util.regex.Pattern haltPat  = java.util.regex.Pattern.compile(
                    "^\\[([\\d: -]+)\\]\\s+DAILY LOSS LIMIT");

            for (String line : Files.readAllLines(logPath)) {
                java.util.regex.Matcher m;
                m = entryPat.matcher(line);
                if (m.find() && !line.contains("closed")) {
                    liveOpened++;
                    String sym = m.group(2).split("\\s+")[0];
                    int qty    = Integer.parseInt(m.group(3));
                    double pr  = Double.parseDouble(m.group(4));
                    liveBuyStack.computeIfAbsent(sym, k -> new java.util.ArrayDeque<>())
                            .add(new LiveEntry(sym, qty, pr));
                    continue;
                }
                m = closePat.matcher(line);
                if (m.find()) {
                    liveClosed++;
                    String sym    = m.group(2);
                    double exitPr = Double.parseDouble(m.group(3));
                    java.util.Deque<LiveEntry> stack = liveBuyStack.get(sym);
                    if (stack != null && !stack.isEmpty()) {
                        LiveEntry e = stack.poll();
                        double pnl = (exitPr - e.prem()) * e.qty() * 100.0;
                        livePnlKnown += pnl;
                        String reason = line.substring(line.indexOf("closed:") + 8).trim();
                        liveTxLines.add(String.format("  LIVE  %-6s CALL  entry=$%7.2f exit=$%7.2f x%d  P&L=$%8.2f  %s",
                                sym, e.prem(), exitPr, e.qty(), pnl, reason));
                    }
                    continue;
                }
                m = haltPat.matcher(line);
                if (m.find() && !line.contains("active")) {
                    liveHalted = true;
                    haltTime = m.group(1).trim();
                }
            }
        } catch (Exception e) {
            System.out.println("  (could not read live log: " + e.getMessage() + ")");
        }
        int liveHaltClosed = liveOpened - liveClosed;

        // --- Print side-by-side table ---
        System.out.printf("%n  %-32s  %-18s  %-18s%n", "", "BACKTEST (sim)", "LIVE APP (actual)");
        System.out.println("  " + "-".repeat(72));
        System.out.printf("  %-32s  %-18s  %-18s%n", "Today's day P&L",
                Double.isNaN(simDayPct) ? "n/a (no bars)" : String.format("%+.2f%%", simDayPct),
                liveHalted ? "≥ -5.00% (halted)" : "n/a");
        System.out.printf("  %-32s  %-18s  %-18s%n", "Positions opened",
                String.valueOf(simTotalOpened), String.valueOf(liveOpened));
        System.out.printf("  %-32s  %-18s  %-18s%n", "Closed before halt/EOD",
                String.valueOf(simRoundTrips), String.valueOf(liveClosed));
        System.out.printf("  %-32s  %-18s  %-18s%n", "Closed at halt/forced",
                String.valueOf(simOpenAtEnd),
                liveHalted ? String.valueOf(liveHaltClosed) : "0");
        if (simRoundTrips > 0) {
            System.out.printf("  %-32s  %-18s  %-18s%n", "Win rate (pre-halt closes)",
                    String.format("%.0f%%", 100.0 * simRoundWins / simRoundTrips),
                    liveClosed > 0 ? "0%" : "n/a");
        }
        if (livePnlKnown != 0 || !liveTxLines.isEmpty()) {
            System.out.printf("  %-32s  %-18s  %-18s%n", "Known pre-halt P&L",
                    "n/a", String.format("$%.2f", livePnlKnown));
        }
        if (haltTime != null) {
            System.out.printf("  %-32s  %-18s  %-18s%n", "Halt time", "n/a", haltTime);
        }

        System.out.println("\n  Round-trip detail:");
        if (simTxLines.isEmpty() && liveTxLines.isEmpty()) {
            System.out.println("  (no closed round-trips found for today)");
        }
        simTxLines.forEach(System.out::println);
        liveTxLines.forEach(System.out::println);

        if (!Double.isNaN(simDayPct) && liveHalted) {
            double gap = simDayPct - (-5.0);
            System.out.printf("%n  Backtest vs live gap (today): %+.2f pp%n", gap);
            System.out.println("  (positive = backtest was more optimistic than the live -5% result)");
        }

        // --- Historical daily P&L distribution ---
        int neg5More = 0, neg5to2 = 0, neg2to0 = 0, flat0to2 = 0, pos2to5 = 0, pos5More = 0;
        double prev = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            double pct = (pt.getPortfolioValue() - prev) / prev * 100.0;
            prev = pt.getPortfolioValue();
            if      (pct < -5) neg5More++;
            else if (pct < -2) neg5to2++;
            else if (pct <  0) neg2to0++;
            else if (pct <  2) flat0to2++;
            else if (pct <  5) pos2to5++;
            else               pos5More++;
        }
        int total = curve.size();
        System.out.println("\n  Historical daily P&L distribution (" + total + " trading days in backtest):");
        System.out.printf("    < -5%%        : %3d days (%4.1f%%)%n", neg5More, 100.0 * neg5More / total);
        System.out.printf("    -5%% to -2%%  : %3d days (%4.1f%%)%n", neg5to2,  100.0 * neg5to2  / total);
        System.out.printf("    -2%% to  0%%  : %3d days (%4.1f%%)%n", neg2to0,  100.0 * neg2to0  / total);
        System.out.printf("     0%% to  2%%  : %3d days (%4.1f%%)%n", flat0to2, 100.0 * flat0to2 / total);
        System.out.printf("     2%% to  5%%  : %3d days (%4.1f%%)%n", pos2to5,  100.0 * pos2to5  / total);
        System.out.printf("    > 5%%         : %3d days (%4.1f%%)%n", pos5More, 100.0 * pos5More / total);
    }

    private static void printCandidateRanking(IntradayBacktestResult result, Set<String> candidates) {
        Map<String, double[]> bySymbol = new TreeMap<>(); // [pnl, trades, wins]
        Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
        List<TransactionRecord> sorted = new ArrayList<>(result.getTrades());
        sorted.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));
        for (TransactionRecord r : sorted) {
            String sym = r.getSymbol();
            if (!candidates.contains(sym)) continue;
            String action = r.getAction().name();
            boolean isBuy  = action.equals("BUY")  || action.equals("CALL_BUY")  || action.equals("PUT_BUY");
            boolean isSell = action.equals("SELL") || action.equals("CALL_SELL") || action.equals("PUT_SELL");
            if (isBuy) {
                buyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(r);
            } else if (isSell) {
                List<TransactionRecord> buys = buyStack.get(sym);
                if (buys != null && !buys.isEmpty()) {
                    TransactionRecord buy = buys.remove(0);
                    boolean isOpts = action.startsWith("CALL_") || action.startsWith("PUT_");
                    double pnl = (r.getPricePerUnit() - buy.getPricePerUnit())
                            * r.getQuantity() * (isOpts ? 100.0 : 1.0)
                            - buy.getFeeCharged() - r.getFeeCharged();
                    double[] v = bySymbol.computeIfAbsent(sym, k -> new double[3]);
                    v[0] += pnl; v[1]++; if (pnl >= 0) v[2]++;
                }
            }
        }

        List<Map.Entry<String, double[]>> ranked = new ArrayList<>(bySymbol.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        System.out.println("\n=== CANDIDATE SYMBOL RANKING ===");
        System.out.printf("  %-8s  %12s  %6s  %7s%n", "Symbol", "Net P&L", "Trades", "WinRate");
        System.out.println("  " + "-".repeat(40));
        for (Map.Entry<String, double[]> e : ranked) {
            double[] v = e.getValue();
            double wr = v[1] > 0 ? v[2] / v[1] * 100 : 0;
            System.out.printf("  %-8s  $%,10.2f  %6.0f  %6.1f%%%n", e.getKey(), v[0], v[1], wr);
        }

        List<String> top5 = ranked.stream().limit(5).map(Map.Entry::getKey).collect(Collectors.toList());
        System.out.println("\n>>> Top 5 candidates to add: " + String.join(",", top5));
    }

    private static void appendHistorySummaries(AppConfig cfg, java.util.List<RunSummary> summaries,
                                               LocalDate startDate, LocalDate endDate) {
        appendHistorySummaries(cfg, summaries, startDate, endDate, null);
    }

    private static void appendHistorySummaries(AppConfig cfg, java.util.List<RunSummary> summaries,
                                               LocalDate startDate, LocalDate endDate,
                                               double[] perRunFloors) {
        try {
            Path histPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv");
            boolean isNew = !Files.exists(histPath);
            try (PrintWriter h = new PrintWriter(Files.newBufferedWriter(histPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                if (isNew) {
                    h.println("timestamp\tperiod\tstrategies\tstop_loss\tentry_cutoff\tallowlist_count\tovernight_floor\tlabel\treturn_pct\tmax_drawdown\ttrades\twin_rate");
                }
                String ts = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String period = startDate + " to " + endDate;
                String stopLoss = String.valueOf(cfg.getOptionsStopLossFrac());
                String cutoff = cfg.getOptionsEntryCutoff() != null ? cfg.getOptionsEntryCutoff().toString() : "";
                int allowlistCount = cfg.getOptionsSymbolAllowlist().size();
                for (int i = 0; i < summaries.size(); i++) {
                    RunSummary s = summaries.get(i);
                    double floor = (perRunFloors != null && i < perRunFloors.length)
                            ? perRunFloors[i] : cfg.getOvernightMinPremiumFrac();
                    double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                    h.printf("%s\t%s\t%s\t%s\t%s\t%d\t%.2f\t%s\t%.2f\t%.2f\t%d\t%.1f%n",
                            ts, period, String.join("|", s.strategies()),
                            stopLoss, cutoff, allowlistCount, floor,
                            s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not write backtest history: " + e.getMessage());
        }
    }

    private static void appendHistory(AppConfig cfg, java.util.List<RunResult> results,
                                       LocalDate startDate, LocalDate endDate) {
        try {
            Path histPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv");
            boolean isNew = !Files.exists(histPath);
            try (PrintWriter h = new PrintWriter(Files.newBufferedWriter(histPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                if (isNew) {
                    h.println("timestamp\tperiod\tstrategies\tstop_loss\tentry_cutoff\tallowlist_count\tovernight_floor\tlabel\treturn_pct\tmax_drawdown\ttrades\twin_rate");
                }
                String ts = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String period = startDate + " to " + endDate;
                String stopLoss = String.valueOf(cfg.getOptionsStopLossFrac());
                String cutoff = cfg.getOptionsEntryCutoff() != null ? cfg.getOptionsEntryCutoff().toString() : "";
                int allowlistCount = cfg.getOptionsSymbolAllowlist().size();
                double floor = cfg.getOvernightMinPremiumFrac();
                for (RunResult rr : results) {
                    IntradayBacktestResult r = rr.result();
                    double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
                    String strategies = String.join("|", rr.strategies());
                    h.printf("%s\t%s\t%s\t%s\t%s\t%d\t%.2f\t%s\t%.2f\t%.2f\t%d\t%.1f%n",
                            ts, period, strategies, stopLoss, cutoff, allowlistCount, floor,
                            rr.label().trim(), r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                            r.getTotalTrades(), wr);
                }
            }
            System.out.println("History appended to: " + histPath);
        } catch (Exception e) {
            System.err.println("Warning: could not write backtest history: " + e.getMessage());
        }
    }

    private static String normalizeExitReason(String reason) {
        if (reason == null || reason.isBlank()) return "Unknown";
        String r = reason.toLowerCase();
        if (r.startsWith("profit target"))       return "Profit target";
        if (r.startsWith("premium stop-loss"))   return "Stop-loss (premium)";
        if (r.startsWith("trailing stop"))       return "Stop-loss (trailing)";
        if (r.startsWith("signal reversal"))     return "Signal reversal";
        if (r.startsWith("expiry"))              return "Near expiry (<3 days)";
        if (r.startsWith("pre-close"))           return "Overnight close";
        if (r.startsWith("premium collapsed"))   return "Worthless (expired)";
        if (r.contains("halt"))                  return "Daily loss halt";
        return reason.length() > 32 ? reason.substring(0, 32) : reason;
    }

    private static void writeReport(PrintWriter out, IntradayBacktestResult result,
                                    LocalDate startDate, LocalDate endDate,
                                    AppConfig cfg, RunResult rr) {
        out.println("=== INTRADAY BACKTEST REPORT ===");
        out.println("Period : " + startDate + " to " + endDate);
        out.println("Generated: " + ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        out.println();

        // --- Configuration ---
        out.println("--- CONFIGURATION ---");
        out.printf("Label              : %s%n", rr.label().trim());
        out.printf("Strategies         : %s%n", String.join(", ", rr.strategies()));
        out.printf("Watchlist          : %d symbols (%s)%n",
                rr.watchlist().size(), String.join(", ", rr.watchlist()));
        out.printf("Options Allowlist  : %d symbols (%s)%n",
                cfg.getOptionsSymbolAllowlist().size(), String.join(", ", cfg.getOptionsSymbolAllowlist()));
        String entryStart = cfg.getOptionsEntryStartTime() != null
                ? cfg.getOptionsEntryStartTime() + " ET" : "market open";
        String entryCutoff = cfg.getOptionsEntryCutoff() != null
                ? cfg.getOptionsEntryCutoff() + " ET" : "none";
        out.printf("Entry Window       : %s - %s%n", entryStart, entryCutoff);
        out.printf("Stop Loss          : %.0f%% of premium%n", cfg.getOptionsStopLossFrac() * 100);
        out.printf("Profit Target      : %.2fx (%.0f%% gain)%n",
                rr.profitTarget(), (rr.profitTarget() - 1.0) * 100);
        out.printf("Reversal Exit      : signals>=%d for %d consecutive ticks%n",
                rr.reversalMinSignals(), rr.reversalMinConsecutive());
        out.printf("Avoid Overnight    : %s%n", rr.avoidOvernightHolds());
        out.printf("Overnight Floor    : %.0f%% of entry premium%n", rr.overnightMinPremiumFrac() * 100);
        out.printf("Daily Loss Limit   : %.1f%%%n", rr.dailyLossLimitPct());
        out.printf("Max Portfolio Exp. : %.1f%%%n", cfg.getMaxPortfolioExposurePct());
        out.printf("Calls Disabled     : %s%n",
                cfg.getOptionsCallsDisabled().isEmpty() ? "none" : String.join(", ", cfg.getOptionsCallsDisabled()));
        out.printf("Puts Disabled      : %s%n",
                cfg.getOptionsPutsDisabled().isEmpty() ? "none" : String.join(", ", cfg.getOptionsPutsDisabled()));
        out.printf("Downtrend Put Min  : %d signals%n", cfg.getDowntrendPutMinSignals());
        out.printf("Market Regime Filt : %s%n", cfg.isMarketRegimeFilterEnabled());
        out.println();

        // --- Summary stats ---
        out.println("--- SUMMARY ---");
        out.printf("Final Balance  : $%,.2f%n", result.getFinalBalance());
        out.printf("Total Return   : %.2f%%%n", result.getTotalReturnPct());
        out.printf("Max Drawdown   : %.2f%%%n", result.getMaxDrawdownPct());
        out.printf("Total Trades   : %d (wins=%d losses=%d)%n",
                result.getTotalTrades(), result.getWins(), result.getLosses());
        if (result.getTotalTrades() > 0) {
            out.printf("Win Rate       : %.1f%%%n",
                    100.0 * result.getWins() / result.getTotalTrades());
        }
        out.println();

        // --- Equity curve ---
        out.println("--- DAILY EQUITY CURVE ---");
        List<BacktestDataPoint> curve = result.getEquityCurve();
        double prev = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            double pct = prev > 0 ? (pt.getPortfolioValue() - prev) / prev * 100.0 : 0;
            out.printf("%s  $%,10.2f  %+.2f%%%n", pt.getDate(), pt.getPortfolioValue(), pct);
            prev = pt.getPortfolioValue();
        }
        out.println();

        // --- Transaction analysis ---
        List<TransactionRecord> trades = result.getTrades();
        if (!trades.isEmpty()) {
            out.println("--- TRADE LOG (chronological) ---");
            // findAll returns DESC; reverse to chronological
            List<TransactionRecord> chronological = new ArrayList<>(trades);
            chronological.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));

            // Group buys/sells by symbol to compute round-trip P&L
            Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
            record RoundTrip(String ts, String sym, String type, int qty,
                             double entry, double exit, double pnl, String reason) {}
            List<RoundTrip> roundTrips = new ArrayList<>();

            for (TransactionRecord r : chronological) {
                String sym = r.getSymbol();
                String action = r.getAction().name();
                String ts = DT_FMT.format(Instant.ofEpochMilli(r.getTimestamp()));
                out.printf("%s  %-20s %-12s qty=%4d  price=$%8.3f  fee=$%.2f  bal=$%,.2f  %s%n",
                        ts, sym, action, r.getQuantity(), r.getPricePerUnit(),
                        r.getFeeCharged(), r.getBalanceAfter(),
                        r.getReason() != null ? r.getReason() : "");

                boolean isBuy  = action.equals("BUY")  || action.equals("CALL_BUY")  || action.equals("PUT_BUY");
                boolean isSell = action.equals("SELL") || action.equals("CALL_SELL") || action.equals("PUT_SELL");

                if (isBuy) {
                    buyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(r);
                } else if (isSell) {
                    List<TransactionRecord> buys = buyStack.get(sym);
                    if (buys != null && !buys.isEmpty()) {
                        TransactionRecord buy = buys.remove(0);
                        boolean isOptions = action.startsWith("CALL_") || action.startsWith("PUT_");
                        double multiplier = isOptions ? 100.0 : 1.0;
                        double pnl = (r.getPricePerUnit() - buy.getPricePerUnit())
                                * r.getQuantity() * multiplier
                                - buy.getFeeCharged() - r.getFeeCharged();
                        String type = action.contains("CALL") ? "CALL" : action.contains("PUT") ? "PUT" : "STOCK";
                        roundTrips.add(new RoundTrip(ts, sym, type, r.getQuantity(),
                                buy.getPricePerUnit(), r.getPricePerUnit(), pnl,
                                r.getReason() != null ? r.getReason() : ""));
                    }
                }
            }
            out.println();

            // --- Round-trip P&L summary ---
            out.println("--- ROUND-TRIP P&L SUMMARY ---");
            double totalPnl = 0;
            int winners = 0, losers = 0;
            // symbol -> [totalPnl, trades, wins, sumWin, sumLoss]
            Map<String, double[]> bySymbol = new TreeMap<>();
            // type -> [totalPnl, trades]
            Map<String, double[]> byType   = new TreeMap<>();
            // normalized exit reason -> [trades, wins, totalPnl]
            Map<String, double[]> byReason = new TreeMap<>();

            for (RoundTrip rt : roundTrips) {
                totalPnl += rt.pnl();
                boolean win = rt.pnl() >= 0;
                if (win) winners++; else losers++;

                double[] sv = bySymbol.computeIfAbsent(rt.sym(), k -> new double[5]);
                sv[0] += rt.pnl(); sv[1]++; if (win) { sv[2]++; sv[3] += rt.pnl(); } else { sv[4] += rt.pnl(); }

                double[] tv = byType.computeIfAbsent(rt.type(), k -> new double[2]);
                tv[0] += rt.pnl(); tv[1]++;

                String bucket = normalizeExitReason(rt.reason());
                double[] rv = byReason.computeIfAbsent(bucket, k -> new double[3]);
                rv[0]++; if (win) rv[1]++; rv[2] += rt.pnl();

                out.printf("  %s  %-20s %-5s  entry=$%.3f exit=$%.3f qty=%d  P&L=$%.2f  %s%n",
                        rt.ts(), rt.sym(), rt.type(), rt.entry(), rt.exit(), rt.qty(), rt.pnl(), rt.reason());
            }
            out.println();
            out.printf("  Total Round-Trip P&L: $%.2f  (winners=%d losers=%d)%n", totalPnl, winners, losers);
            out.println();

            // --- Per-symbol breakdown (sorted losers first) ---
            out.println("--- PER-SYMBOL BREAKDOWN ---");
            out.printf("  %-8s  %10s  %6s  %7s  %10s  %10s  %10s%n",
                    "Symbol", "Net P&L", "Trades", "WinRate", "Avg Win", "Avg Loss", "Expectancy");
            out.println("  " + "-".repeat(75));
            bySymbol.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue()[0]))
                    .forEach(e -> {
                        double[] v = e.getValue();
                        double wr  = v[1] > 0 ? v[2] / v[1] : 0;
                        double avgW = v[2] > 0 ? v[3] / v[2] : 0;
                        double avgL = (v[1] - v[2]) > 0 ? v[4] / (v[1] - v[2]) : 0;
                        double exp  = wr * avgW + (1 - wr) * avgL;
                        out.printf("  %-8s  %10.2f  %6.0f  %6.1f%%  %10.2f  %10.2f  %10.2f%n",
                                e.getKey(), v[0], v[1], wr * 100, avgW, avgL, exp);
                    });
            out.println();

            // --- Exit reason breakdown (sorted by total P&L) ---
            out.println("--- EXIT REASON BREAKDOWN ---");
            out.printf("  %-32s  %6s  %7s  %11s  %10s%n",
                    "Reason", "Trades", "WinRate", "Total P&L", "Avg P&L");
            out.println("  " + "-".repeat(72));
            byReason.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue()[2]))
                    .forEach(e -> {
                        double[] v = e.getValue();
                        double wr  = v[0] > 0 ? v[1] / v[0] * 100 : 0;
                        out.printf("  %-32s  %6.0f  %6.1f%%  %11.2f  %10.2f%n",
                                e.getKey(), v[0], wr, v[2], v[0] > 0 ? v[2] / v[0] : 0);
                    });
            out.println();

            out.println("  By Trade Type:");
            byType.forEach((type, v) ->
                    out.printf("    %-6s  P&L=$%10.2f  trades=%.0f%n", type, v[0], v[1]));
            out.println();
        }

        // --- Event log pattern analysis ---
        out.println("--- EVENT LOG PATTERN ANALYSIS ---");
        List<String> log = result.getEventLog();
        out.printf("Total log lines: %d%n%n", log.size());

        Map<String, Integer> patternCounts = new TreeMap<>();
        String[] patterns = {
            "downtrend", "SPY below", "overbought", "cooldown", "re-entry cooldown",
            "MULTILEG_REENTRY", "daily loss limit", "HALTED", "stop loss", "STOP",
            "profit target", "earnings", "VIX", "capacity", "concurrent positions",
            "CALL skip", "PUT skip", "BUY skipped", "SELL skipped"
        };
        for (String p : patterns) patternCounts.put(p, 0);

        List<String> lossLines = new ArrayList<>();
        List<String> haltLines = new ArrayList<>();

        for (String line : log) {
            String lower = line.toLowerCase();
            for (String p : patterns) {
                if (lower.contains(p.toLowerCase())) {
                    patternCounts.merge(p, 1, Integer::sum);
                }
            }
            if (lower.contains("stop loss") || lower.contains("stop-loss") || lower.contains("closed") && lower.contains("loss")) {
                lossLines.add(line);
            }
            if (lower.contains("halted") || lower.contains("daily loss limit")) {
                haltLines.add(line);
            }
        }

        out.println("Skip/Block pattern frequencies:");
        patternCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.printf("  %-30s : %d%n", e.getKey(), e.getValue()));
        out.println();

        if (!haltLines.isEmpty()) {
            out.println("Daily loss limit triggers (" + haltLines.size() + "):");
            haltLines.forEach(l -> out.println("  " + l));
            out.println();
        }

        if (!lossLines.isEmpty()) {
            out.println("Stop-loss / closed-at-loss events (first 50):");
            lossLines.stream().limit(50).forEach(l -> out.println("  " + l));
            out.println();
        }

        // --- Full event log ---
        out.println("--- FULL EVENT LOG ---");
        log.forEach(out::println);
    }
}
