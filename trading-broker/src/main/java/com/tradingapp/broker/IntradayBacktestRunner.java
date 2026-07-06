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
import com.tradingapp.options.PremiumSellerRouter;

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

        // today-compare and iv-surge-compare extend endDate to today so today's bars are fetched and simulated
        LocalDate endDate = LocalDate.now(ET);
        if (!"today-compare".equals(mode) && !"iv-surge-compare".equals(mode)) {
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
        // PCS_OPTS: use a dedicated premium allowlist if configured, otherwise fall back to BASE_OPTS.
        Set<String> PCS_OPTS = cfg.getPremiumSymbolAllowlist().isEmpty() ? BASE_OPTS : cfg.getPremiumSymbolAllowlist();

        // Candidates for symbol-scan mode: MasterUniverse symbols not in current allowlist, with cached bars
        List<String> scanCandidates = "symbol-scan".equals(mode)
                ? MasterUniverse.SYMBOLS.stream()
                        .filter(s -> !BASE_OPTS.contains(s) && !SCAN_EXCLUDED.contains(s) && barsBySymbol.containsKey(s))
                        .collect(Collectors.toList())
                : List.of();

        double defaultLossLimitPct = cfg.getDailyLossLimitPct();
        record RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                      double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive, double profitTarget,
                      double overnightMinPremiumFrac, Boolean avoidOvernightHolds, LocalTime entryStartTime,
                      LocalTime forceCloseTime, double positionBudgetFrac, int maxContractsPerTrade,
                      int lossLimitRecoveryBars, int entryConfirmationTicks, double ivSurgeThreshold) {
            // reversal-compare: explicit reversal settings, default profit target, inherit floor/overnight from cfg
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                   double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, reversalMinSignals, reversalMinConsecutive, 2.5, -1, null, null, null, -1, 0, 0, 0, 0);
            }
            // most modes: optimal reversal settings + default profit target, inherit floor/overnight from cfg
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies, double dailyLossLimitPct) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, 5, 2, 2.5, -1, null, null, null, -1, 0, 0, 0, 0);
            }
            // profit-target-compare: explicit profit target, inherit floor/overnight
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                   double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive, double profitTarget) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, reversalMinSignals, reversalMinConsecutive, profitTarget, -1, null, null, null, -1, 0, 0, 0, 0);
            }
            // overnight-floor-compare: explicit floor, inherit overnight
            RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies,
                   double dailyLossLimitPct, int reversalMinSignals, int reversalMinConsecutive, double profitTarget,
                   double overnightMinPremiumFrac) {
                this(label, watchlist, optAllowlist, strategies, dailyLossLimitPct, reversalMinSignals, reversalMinConsecutive, profitTarget, overnightMinPremiumFrac, null, null, null, -1, 0, 0, 0, 0);
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
        } else if ("add-strategy-compare".equals(mode)) {
            // 2-run compare: current config (baseline) vs current config + one additional strategy.
            // Specify strategy via -Dbacktest.addStrategy=RELATIVE_STRENGTH_DIVERGENCE
            String addStrategy = System.getProperty("backtest.addStrategy", "RELATIVE_STRENGTH_DIVERGENCE");
            Set<String> withExtra = new java.util.LinkedHashSet<>(cfg.getEnabledStrategies());
            withExtra.add(addStrategy);
            runs.add(new RunCfg(String.format("%-44s", "Baseline: " + cfg.getEnabledStrategies()), baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
            runs.add(new RunCfg(String.format("%-44s", "Baseline + " + addStrategy), baseWatchlist, BASE_OPTS, Set.copyOf(withExtra), defaultLossLimitPct));
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
                    5, 2, cfg.getProfitTarget(), cfg.getOvernightMinPremiumFrac(), false, null, null, -1, 0, 0, 0, 0.0));
            runs.add(new RunCfg(String.format("%-44s", "avoidOvernightHolds=true  (EOD force-close)"),
                    baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct,
                    5, 2, cfg.getProfitTarget(), cfg.getOvernightMinPremiumFrac(), true, null, null, -1, 0, 0, 0, 0.0));
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
        } else if ("entry-start-compare".equals(mode)) {
            // Vary the earliest time we allow new options entries (9:30 = market open, no delay).
            // Each run uses the current live strategies/symbols; only the entry window start changes.
            String currentStart = cfg.getOptionsEntryStartTime() != null
                    ? cfg.getOptionsEntryStartTime().toString() : "market open";
            LocalTime[] startTimes = {
                null,                    // market open (no delay)
                LocalTime.of(9, 45),
                LocalTime.of(10,  0),
                LocalTime.of(10, 15),
                LocalTime.of(10, 30),    // current live setting
                LocalTime.of(10, 45),
                LocalTime.of(11,  0),
                LocalTime.of(11, 30),
            };
            for (LocalTime t : startTimes) {
                String tag = t == null ? "market open (9:30)" : t.toString();
                if (t != null && t.equals(cfg.getOptionsEntryStartTime())) tag += " (current)";
                String label = String.format("%-38s", "entry start " + tag);
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, cfg.getProfitTarget(),
                        cfg.getOvernightMinPremiumFrac(), null, t, null, -1, 0, 0, 0, 0.0));
            }
        } else if ("exit-cutoff-compare".equals(mode)) {
            // Vary the EOD force-close time (normally 15:45). Earlier = fewer overnight-ish risks;
            // later = more time for stop-loss/profit-target to fire on late-day moves.
            LocalTime currentForceCt = LocalTime.of(15, 45);
            LocalTime[] forceCloseTimes = {
                LocalTime.of(14, 30),
                LocalTime.of(14, 45),
                LocalTime.of(15,  0),
                LocalTime.of(15, 15),
                LocalTime.of(15, 30),
                LocalTime.of(15, 45),   // current default
                LocalTime.of(15, 55),
            };
            for (LocalTime fct : forceCloseTimes) {
                String tag = fct.equals(currentForceCt) ? " (current)" : "";
                String label = String.format("%-38s", "force-close at " + fct + tag);
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, cfg.getProfitTarget(),
                        cfg.getOvernightMinPremiumFrac(), null, null, fct, -1, 0, 0, 0, 0.0));
            }
        } else if ("entry-confirmation-compare".equals(mode)) {
            // Each bar = 1 minute in the backtest. In live 5-second tick mode, multiply by 12
            // to get the equivalent live setting (e.g. 1 bar here → 12 ticks live).
            for (int ticks : new int[]{1, 2, 3, 4, 5}) {
                String tag = ticks == 1 ? " (current live equiv: 12 ticks)" : " (live equiv: " + (ticks * 12) + " ticks)";
                String label = String.format("%-46s", ticks + " bar" + (ticks > 1 ? "s" : "") + " confirmation" + tag);
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, cfg.getProfitTarget(),
                        cfg.getOvernightMinPremiumFrac(), null, null, null, -1, 0, 0, ticks, 0.0));
            }
        } else if ("loss-limit-recovery-compare".equals(mode)) {
            // Test how many 1-min bars to wait before firing the daily loss halt.
            // 0 = current behavior (immediate). 1 = give 1 minute to recover before closing.
            // In live trading bars = 5-second ticks, so 12 ticks ≈ 1 minute.
            for (int recoveryBars : new int[]{0, 1, 2, 3}) {
                String tag = recoveryBars == 0 ? " (current — immediate)" : " bar" + (recoveryBars > 1 ? "s" : "") + " recovery window";
                String label = String.format("%-44s", "loss-limit recovery " + recoveryBars + tag);
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, cfg.getProfitTarget(),
                        cfg.getOvernightMinPremiumFrac(), null, null, null, -1, 0, recoveryBars, 0, 0.0));
            }
        } else if ("position-size-compare".equals(mode)) {
            // Vary per-trade position size (budget fraction + matching contract cap).
            // Standard tier (CALL/PUT/STOCH/MACD/ORB) uses these values directly;
            // high-conviction tier (HIGH_DELTA, NEARTERM_MOM) uses 1.6× both.
            record SizeCfg(double budgetFrac, int maxContracts) {}
            List<SizeCfg> sizes = List.of(
                new SizeCfg(0.02,  2),
                new SizeCfg(0.03,  3),
                new SizeCfg(0.05,  5),   // current default
                new SizeCfg(0.07,  7),
                new SizeCfg(0.10, 10),
                new SizeCfg(0.15, 15)
            );
            for (SizeCfg sc : sizes) {
                String tag = sc.budgetFrac() == 0.05 ? " (current)" : "";
                String label = String.format("%-42s", String.format("%.0f%% budget / %d contracts%s",
                        sc.budgetFrac() * 100, sc.maxContracts(), tag));
                runs.add(new RunCfg(label, baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(),
                        defaultLossLimitPct, 5, 2, cfg.getProfitTarget(),
                        cfg.getOvernightMinPremiumFrac(), null, null, null, sc.budgetFrac(), sc.maxContracts(), 0, 0, 0.0));
            }
        } else if ("today-compare".equals(mode)) {
            runs.add(new RunCfg("TODAY " + endDate + ": current config", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct));
        } else if ("iv-surge-compare".equals(mode)) {
            // 3-run compare scoped to today: baseline guard vs relaxed vs disabled.
            // ivSurgeThreshold=0 means "use default (1.2)"; positive values override.
            runs.add(new RunCfg(String.format("%-44s", "Baseline (IV surge guard 1.2x)"), baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct, 5, 2, 2.5, -1, null, null, null, -1, 0, 0, 0, 0.0));
            runs.add(new RunCfg(String.format("%-44s", "IV surge guard 1.5x (relaxed)"),  baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct, 5, 2, 2.5, -1, null, null, null, -1, 0, 0, 0, 1.5));
            runs.add(new RunCfg(String.format("%-44s", "IV surge guard OFF (disabled)"),   baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies(), defaultLossLimitPct, 5, 2, 2.5, -1, null, null, null, -1, 0, 0, 0, 99.0));
        } else if ("regime-ma-compare".equals(mode)) {
            // 2-pass comparison: 5-day MA (current live setting) vs regime filter OFF.
            // Runs inline and returns so the filter flag doesn't need to be threaded through RunCfg.
            record RegimeCase(String label, boolean filterOn, int maDays) {}
            List<RegimeCase> regimeCases = List.of(
                    new RegimeCase("5-day MA (current live)", true, 5),
                    new RegimeCase("regime filter OFF",       false, 5)
            );
            RunSummary[] maResults = new RunSummary[regimeCases.size()];
            for (int i = 0; i < regimeCases.size(); i++) {
                RegimeCase rc = regimeCases.get(i);
                String maLabel = String.format("%-44s", rc.label());
                System.out.println("\n=== " + rc.label() + " ===");
                BlackScholesEngine bs = new BlackScholesEngine();
                bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                OptionsSignalRouter maRouter = new OptionsSignalRouter(
                        bs, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {}, null);
                maRouter.setMaxPortfolioExposure(maxExposure);
                maRouter.setEnabledStrategies(cfg.getEnabledStrategies());
                if (backtestEntryStartTime != null) maRouter.setEntryStartTime(backtestEntryStartTime);
                if (cfg.getOptionsForceCloseTime() != null) maRouter.setForceCloseTime(cfg.getOptionsForceCloseTime());
                maRouter.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
                maRouter.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
                maRouter.setStopLossFrac(cfg.getOptionsStopLossFrac());
                maRouter.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
                if (cfg.getOptionsEntryCutoff() != null) maRouter.setEntryCutoff(cfg.getOptionsEntryCutoff());
                maRouter.setOptionsAllowlist(BASE_OPTS);
                maRouter.setCallsDisabledSymbols(CALLS_DISABLED);
                maRouter.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
                maRouter.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
                maRouter.setReversalMinSignals(5);
                maRouter.setReversalMinConsecutive(2);
                maRouter.setProfitTarget(cfg.getProfitTarget());
                maRouter.setEntryConfirmationTicks(Math.max(1, cfg.getEntryConfirmationTicks() / 12));
                maRouter.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());
                long t0 = System.currentTimeMillis();
                IntradayBacktestResult r = engine.run(baseWatchlist, barsBySymbol, 100_000.0, maRouter, msg -> {},
                        Set.of(), loop -> {
                            maRouter.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setMaxConcurrentStockPositions(10);
                            loop.setAvoidOvernightHolds(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            loop.setRegimeMaDays(rc.maDays());
                            loop.setMarketRegimeFilterEnabled(rc.filterOn());
                            maRouter.setClosePositionsOnHalt(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0) / 1000.0,
                        r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                        r.getTotalTrades(), r.getWins(), r.getLosses());
                maResults[i] = new RunSummary(maLabel, cfg.getEnabledStrategies(),
                        r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                        r.getTotalTrades(), r.getWins(), r.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Regime Filter Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : maResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, List.of(maResults[0], maResults[1]), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("stock-compare".equals(mode)) {
            // 2-run compare: stock trading ON (historical baseline) vs OFF (matches live config).
            // Runs inline so the stockEnabled flag doesn't need to be threaded through RunCfg.
            RunSummary[] stockResults = new RunSummary[2];
            boolean[] stockEnabled = {true, false};
            for (int i = 0; i < stockEnabled.length; i++) {
                boolean stockOn = stockEnabled[i];
                String stockLabel = String.format("%-44s", stockOn
                        ? "stock trading ON  (historical baseline)"
                        : "stock trading OFF (matches live config) ");
                System.out.println("\n=== " + stockLabel.trim() + " ===");
                BlackScholesEngine bsSt = new BlackScholesEngine();
                bsSt.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                OptionsSignalRouter stRouter = new OptionsSignalRouter(
                        bsSt, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {}, null);
                stRouter.setMaxPortfolioExposure(maxExposure);
                Set<String> stStrategies = disabledStrategies.isEmpty() ? cfg.getEnabledStrategies()
                        : cfg.getEnabledStrategies().stream().filter(s -> !disabledStrategies.contains(s))
                                .collect(Collectors.toSet());
                stRouter.setEnabledStrategies(stStrategies);
                if (backtestEntryStartTime != null) stRouter.setEntryStartTime(backtestEntryStartTime);
                if (cfg.getOptionsForceCloseTime() != null) stRouter.setForceCloseTime(cfg.getOptionsForceCloseTime());
                stRouter.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
                stRouter.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
                stRouter.setStopLossFrac(cfg.getOptionsStopLossFrac());
                stRouter.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
                if (cfg.getOptionsEntryCutoff() != null) stRouter.setEntryCutoff(cfg.getOptionsEntryCutoff());
                stRouter.setOptionsAllowlist(BASE_OPTS);
                stRouter.setCallsDisabledSymbols(CALLS_DISABLED);
                stRouter.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
                stRouter.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
                stRouter.setReversalMinSignals(5);
                stRouter.setReversalMinConsecutive(2);
                stRouter.setProfitTarget(cfg.getProfitTarget());
                stRouter.setEntryConfirmationTicks(Math.max(1, cfg.getEntryConfirmationTicks() / 12));
                stRouter.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());
                final boolean finalStockOn = stockOn;
                long t0St = System.currentTimeMillis();
                IntradayBacktestResult stResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, stRouter, msg -> {},
                        Set.of(), loop -> {
                            stRouter.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(finalStockOn);
                            loop.setMaxConcurrentStockPositions(10);
                            loop.setAvoidOvernightHolds(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            stRouter.setClosePositionsOnHalt(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0St) / 1000.0,
                        stResult.getTotalReturnPct(), stResult.getMaxDrawdownPct(),
                        stResult.getTotalTrades(), stResult.getWins(), stResult.getLosses());
                stockResults[i] = new RunSummary(stockLabel, cfg.getEnabledStrategies(),
                        stResult.getTotalReturnPct(), stResult.getMaxDrawdownPct(),
                        stResult.getTotalTrades(), stResult.getWins(), stResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Stock Trading Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : stockResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, List.of(stockResults[0], stockResults[1]), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("signal-compare".equals(mode)) {
            // Full live config: compare return/drawdown/winrate at varying minimum buy-signal thresholds.
            // 0 = current behavior (strategy-specific minimums), 2/3/4/5 = global bullish entry floor.
            int[] sigThresholds = {0, 2, 3, 4, 5};
            RunSummary[] sigResults = new RunSummary[sigThresholds.length];
            Set<String> sigStrategies = disabledStrategies.isEmpty() ? cfg.getEnabledStrategies()
                    : cfg.getEnabledStrategies().stream().filter(s -> !disabledStrategies.contains(s))
                            .collect(Collectors.toSet());
            for (int i = 0; i < sigThresholds.length; i++) {
                int minBuys = sigThresholds[i];
                String sigLabel = String.format("%-44s",
                        minBuys == 0 ? "min buy signals: 0 (current)"
                                     : "min buy signals: " + minBuys);
                System.out.println("\n=== " + sigLabel.trim() + " ===");
                BlackScholesEngine bsSig = new BlackScholesEngine();
                bsSig.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                OptionsSignalRouter sigRouter = new OptionsSignalRouter(
                        bsSig, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {}, null);
                sigRouter.setMaxPortfolioExposure(maxExposure);
                sigRouter.setEnabledStrategies(sigStrategies);
                if (backtestEntryStartTime != null) sigRouter.setEntryStartTime(backtestEntryStartTime);
                if (cfg.getOptionsForceCloseTime() != null) sigRouter.setForceCloseTime(cfg.getOptionsForceCloseTime());
                sigRouter.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
                sigRouter.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
                sigRouter.setStopLossFrac(cfg.getOptionsStopLossFrac());
                sigRouter.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
                if (cfg.getOptionsEntryCutoff() != null) sigRouter.setEntryCutoff(cfg.getOptionsEntryCutoff());
                sigRouter.setOptionsAllowlist(BASE_OPTS);
                sigRouter.setCallsDisabledSymbols(CALLS_DISABLED);
                sigRouter.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
                sigRouter.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
                sigRouter.setReversalMinSignals(5);
                sigRouter.setReversalMinConsecutive(2);
                sigRouter.setProfitTarget(cfg.getProfitTarget());
                sigRouter.setEntryConfirmationTicks(Math.max(1, cfg.getEntryConfirmationTicks() / 12));
                sigRouter.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());
                sigRouter.setMinBuySignalsForEntry(minBuys);
                long t0Sig = System.currentTimeMillis();
                IntradayBacktestResult sigResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, sigRouter, msg -> {},
                        Set.of(), loop -> {
                            sigRouter.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setMaxConcurrentStockPositions(10);
                            loop.setAvoidOvernightHolds(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            sigRouter.setClosePositionsOnHalt(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0Sig) / 1000.0,
                        sigResult.getTotalReturnPct(), sigResult.getMaxDrawdownPct(),
                        sigResult.getTotalTrades(), sigResult.getWins(), sigResult.getLosses());
                sigResults[i] = new RunSummary(sigLabel, sigStrategies,
                        sigResult.getTotalReturnPct(), sigResult.getMaxDrawdownPct(),
                        sigResult.getTotalTrades(), sigResult.getWins(), sigResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Min Buy Signals (full config)", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : sigResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, Arrays.asList(sigResults), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("pcs-spread-compare".equals(mode)) {
            // Compare PCS spread widths: $5, $10 (current), $15, $20.
            // Wider spread = more credit but higher max loss. Short strike is unchanged.
            // Uses the low-vol PCS symbol list if configured.
            double[] widths = {5.0, 10.0, 15.0, 20.0};
            RunSummary[] widthResults = new RunSummary[widths.length];
            for (int i = 0; i < widths.length; i++) {
                double w = widths[i];
                String wLabel = String.format("%-44s", String.format("spread width $%.0f%s", w, w == 10.0 ? " (current)" : ""));
                System.out.println("\n=== " + wLabel.trim() + " ===");
                BlackScholesEngine bsW = new BlackScholesEngine();
                bsW.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                PremiumSellerRouter wPsr = new PremiumSellerRouter(
                        bsW, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {});
                wPsr.setEnabledStrategies(Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD));
                wPsr.setAllowlist(PCS_OPTS);
                wPsr.setMaxPortfolioExposure(maxExposure);
                wPsr.setPcsSpreadWidth(w);
                if (backtestEntryStartTime != null) wPsr.setMinEntryTime(
                        backtestEntryStartTime.getHour(), backtestEntryStartTime.getMinute());
                long t0W = System.currentTimeMillis();
                IntradayBacktestResult wResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, wPsr, msg -> {},
                        Set.of(), loop -> {
                            wPsr.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            loop.setMarketRegimeFilterEnabled(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0W) / 1000.0,
                        wResult.getTotalReturnPct(), wResult.getMaxDrawdownPct(),
                        wResult.getTotalTrades(), wResult.getWins(), wResult.getLosses());
                widthResults[i] = new RunSummary(wLabel,
                        Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD),
                        wResult.getTotalReturnPct(), wResult.getMaxDrawdownPct(),
                        wResult.getTotalTrades(), wResult.getWins(), wResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Spread Width", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : widthResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, Arrays.asList(widthResults), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("pcs-symbol-compare".equals(mode)) {
            // Compare PCS on all current symbols vs a curated lower-vol subset.
            // High-beta tech (AMD, CRWD, MRVL, SNOW, etc.) tend to produce too many stop-outs.
            // Lower-vol names (staples, energy, industrials, defense, large-cap finance) give
            // the short put strike more room before a breach.
            Set<String> lowVolPcs = Set.of(
                "AMGN", "CAT", "COP", "DE", "EOG", "GS", "HD", "LLY",
                "MA", "NOC", "PG", "SPY", "TGT", "UNH", "WMT", "XOM"
            );
            record SymCase(String label, Set<String> allowlist) {}
            List<SymCase> symCases = List.of(
                new SymCase("all symbols (current)",                 BASE_OPTS),
                new SymCase("low-vol subset (staples/energy/etc.)",  lowVolPcs)
            );
            RunSummary[] symResults = new RunSummary[symCases.size()];
            for (int i = 0; i < symCases.size(); i++) {
                SymCase syc = symCases.get(i);
                String symLabel = String.format("%-44s", syc.label());
                System.out.println("\n=== " + syc.label() + " ===");
                BlackScholesEngine bsSym = new BlackScholesEngine();
                bsSym.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                PremiumSellerRouter symPsr = new PremiumSellerRouter(
                        bsSym, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {});
                symPsr.setEnabledStrategies(Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD));
                symPsr.setAllowlist(syc.allowlist());
                symPsr.setMaxPortfolioExposure(maxExposure);
                if (backtestEntryStartTime != null) symPsr.setMinEntryTime(
                        backtestEntryStartTime.getHour(), backtestEntryStartTime.getMinute());
                long t0Sym = System.currentTimeMillis();
                IntradayBacktestResult symResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, symPsr, msg -> {},
                        Set.of(), loop -> {
                            symPsr.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            loop.setMarketRegimeFilterEnabled(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0Sym) / 1000.0,
                        symResult.getTotalReturnPct(), symResult.getMaxDrawdownPct(),
                        symResult.getTotalTrades(), symResult.getWins(), symResult.getLosses());
                symResults[i] = new RunSummary(symLabel,
                        Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD),
                        symResult.getTotalReturnPct(), symResult.getMaxDrawdownPct(),
                        symResult.getTotalTrades(), symResult.getWins(), symResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "PCS Symbol Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : symResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, Arrays.asList(symResults), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("pcs-stop-compare".equals(mode)) {
            // Compare per-tick price stops (current) vs end-of-day stops.
            // EOD stop = only evaluate price breach after 15:45 ET; intraday dips that recover
            // don't trigger an exit. Also sweeps profit target under EOD stop to find best combo.
            record StopCase(String label, boolean eodStop, double profit) {}
            List<StopCase> stopCases = List.of(
                new StopCase("per-tick stop (baseline)", false, 0.50),
                new StopCase("EOD stop (after 15:45)",  true,  0.50)
            );
            RunSummary[] stopResults = new RunSummary[stopCases.size()];
            for (int i = 0; i < stopCases.size(); i++) {
                StopCase sc = stopCases.get(i);
                String stopLabel = String.format("%-44s", sc.label());
                System.out.println("\n=== " + sc.label() + " ===");
                BlackScholesEngine bsSc = new BlackScholesEngine();
                bsSc.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                PremiumSellerRouter scPsr = new PremiumSellerRouter(
                        bsSc, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {});
                scPsr.setEnabledStrategies(Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD));
                scPsr.setAllowlist(PCS_OPTS);
                scPsr.setMaxPortfolioExposure(maxExposure);
                scPsr.setPcsEodStopOnly(sc.eodStop());
                scPsr.setPcsProfitTarget(sc.profit());
                if (backtestEntryStartTime != null) scPsr.setMinEntryTime(
                        backtestEntryStartTime.getHour(), backtestEntryStartTime.getMinute());
                long t0Sc = System.currentTimeMillis();
                IntradayBacktestResult stopResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, scPsr, msg -> {},
                        Set.of(), loop -> {
                            scPsr.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            loop.setMarketRegimeFilterEnabled(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0Sc) / 1000.0,
                        stopResult.getTotalReturnPct(), stopResult.getMaxDrawdownPct(),
                        stopResult.getTotalTrades(), stopResult.getWins(), stopResult.getLosses());
                stopResults[i] = new RunSummary(stopLabel,
                        Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD),
                        stopResult.getTotalReturnPct(), stopResult.getMaxDrawdownPct(),
                        stopResult.getTotalTrades(), stopResult.getWins(), stopResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Stop Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : stopResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, Arrays.asList(stopResults), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("pcs-param-compare".equals(mode)) {
            // Sweep three entry/exit parameters independently and combined to find the best
            // PCS configuration. Baseline = current defaults (delta=0.20, profit=50%, no SPY day filter).
            record PcsCase(String label, double delta, double profit, boolean spyDayUp) {}
            List<PcsCase> pcsCases = List.of(
                new PcsCase("baseline (delta=0.20, profit=50%)",     0.20, 0.50, false),
                new PcsCase("SPY up-on-day filter",                  0.20, 0.50, true),
                new PcsCase("profit target 40%",                     0.20, 0.40, false),
                new PcsCase("delta 0.15 (further OTM)",              0.15, 0.50, false),
                new PcsCase("combined (SPY+40%+delta0.15)",          0.15, 0.40, true)
            );
            RunSummary[] prmResults = new RunSummary[pcsCases.size()];
            for (int i = 0; i < pcsCases.size(); i++) {
                PcsCase pc = pcsCases.get(i);
                String prmLabel = String.format("%-44s", pc.label());
                System.out.println("\n=== " + pc.label() + " ===");
                BlackScholesEngine bsPrm = new BlackScholesEngine();
                bsPrm.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                PremiumSellerRouter prmPsr = new PremiumSellerRouter(
                        bsPrm, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {});
                prmPsr.setEnabledStrategies(Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD));
                prmPsr.setAllowlist(PCS_OPTS);
                prmPsr.setMaxPortfolioExposure(maxExposure);
                prmPsr.setPcsDeltaTarget(pc.delta());
                prmPsr.setPcsProfitTarget(pc.profit());
                prmPsr.setPcsRequireSpyDayUp(pc.spyDayUp());
                if (backtestEntryStartTime != null) prmPsr.setMinEntryTime(
                        backtestEntryStartTime.getHour(), backtestEntryStartTime.getMinute());
                long t0Prm = System.currentTimeMillis();
                IntradayBacktestResult prmResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, prmPsr, msg -> {},
                        Set.of(), loop -> {
                            prmPsr.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            loop.setMarketRegimeFilterEnabled(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0Prm) / 1000.0,
                        prmResult.getTotalReturnPct(), prmResult.getMaxDrawdownPct(),
                        prmResult.getTotalTrades(), prmResult.getWins(), prmResult.getLosses());
                prmResults[i] = new RunSummary(prmLabel,
                        Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD),
                        prmResult.getTotalReturnPct(), prmResult.getMaxDrawdownPct(),
                        prmResult.getTotalTrades(), prmResult.getWins(), prmResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "PCS Parameter Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : prmResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, Arrays.asList(prmResults), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        } else if ("pcs-signal-compare".equals(mode)) {
            // Compare PCS performance at varying minimum buy-signal thresholds:
            // 0 = any tick where buys >= sells qualifies (current behavior)
            // 2, 3, 4, 5 = progressively stricter entry bar
            int[] thresholds = {0, 2, 3, 4, 5};
            RunSummary[] pcsResults = new RunSummary[thresholds.length];
            for (int i = 0; i < thresholds.length; i++) {
                int minBuys = thresholds[i];
                String pcsLabel = String.format("%-44s",
                        minBuys == 0 ? "min buy signals: 0 (current)"
                                     : "min buy signals: " + minBuys);
                System.out.println("\n=== " + pcsLabel.trim() + " ===");
                BlackScholesEngine bsPcs = new BlackScholesEngine();
                bsPcs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
                PremiumSellerRouter psr = new PremiumSellerRouter(
                        bsPcs, new OptionsOrderExecutor(new Account(), null),
                        new Account(), new PriceHistory(), msg -> {});
                psr.setEnabledStrategies(Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD));
                psr.setAllowlist(PCS_OPTS);
                psr.setMaxPortfolioExposure(maxExposure);
                psr.setPcsMinBuySignals(minBuys);
                if (backtestEntryStartTime != null) psr.setMinEntryTime(
                        backtestEntryStartTime.getHour(), backtestEntryStartTime.getMinute());
                long t0Pcs = System.currentTimeMillis();
                IntradayBacktestResult pcsResult = engine.run(baseWatchlist, barsBySymbol, 100_000.0, psr, msg -> {},
                        Set.of(), loop -> {
                            psr.setUptrendSupplier(loop::isUptrend);
                            loop.setStockTradingEnabled(false);
                            loop.setDailyLossLimitPct(defaultLossLimitPct / 100.0);
                            loop.setAccurateOptionsValuation(true);
                            loop.setMarketRegimeFilterEnabled(true);
                        });
                System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                        (System.currentTimeMillis() - t0Pcs) / 1000.0,
                        pcsResult.getTotalReturnPct(), pcsResult.getMaxDrawdownPct(),
                        pcsResult.getTotalTrades(), pcsResult.getWins(), pcsResult.getLosses());
                pcsResults[i] = new RunSummary(pcsLabel,
                        Set.of(PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD),
                        pcsResult.getTotalReturnPct(), pcsResult.getMaxDrawdownPct(),
                        pcsResult.getTotalTrades(), pcsResult.getWins(), pcsResult.getLosses());
            }
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "PCS Min Buy Signals", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunSummary s : pcsResults) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, Arrays.asList(pcsResults), startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
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
            LocalTime effectiveStartTime = cfg2.entryStartTime() != null ? cfg2.entryStartTime() : backtestEntryStartTime;
            if (effectiveStartTime != null) router.setEntryStartTime(effectiveStartTime);
            LocalTime effectiveForceClose = cfg2.forceCloseTime() != null ? cfg2.forceCloseTime() : cfg.getOptionsForceCloseTime();
            if (effectiveForceClose != null) router.setForceCloseTime(effectiveForceClose);
            double effectiveBudgetFrac = cfg2.positionBudgetFrac() > 0 ? cfg2.positionBudgetFrac() : cfg.getPositionBudgetFrac();
            int effectiveMaxContracts  = cfg2.maxContractsPerTrade() > 0 ? cfg2.maxContractsPerTrade() : cfg.getMaxContractsPerTrade();
            router.setPositionBudgetFrac(effectiveBudgetFrac);
            router.setMaxContractsPerTrade(effectiveMaxContracts);
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
            // cfg2.entryConfirmationTicks() is in 1-min bar units (backtest native).
            // cfg.getEntryConfirmationTicks() is in live 5-second tick units — divide by 12 to convert to bars.
            int effectiveConfirmBars = cfg2.entryConfirmationTicks() > 0
                    ? cfg2.entryConfirmationTicks()
                    : Math.max(1, cfg.getEntryConfirmationTicks() / 12);
            router.setEntryConfirmationTicks(effectiveConfirmBars);
            double floorFrac = cfg2.overnightMinPremiumFrac() >= 0 ? cfg2.overnightMinPremiumFrac() : cfg.getOvernightMinPremiumFrac();
            router.setOvernightMinPremiumFrac(floorFrac);
            if (cfg2.ivSurgeThreshold() > 0) router.setIvSurgeThreshold(cfg2.ivSurgeThreshold());

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(cfg2.watchlist(), barsBySymbol, 100_000.0, router, msg -> {},
                    Set.of(), loop -> {
                        router.setUptrendSupplier(loop::isUptrend);
                        loop.setStockTradingEnabled(cfg.isStockTradingEnabled());
                        loop.setMaxConcurrentStockPositions(10);
                        loop.setAvoidOvernightHolds(false);
                        loop.setDailyLossLimitPct(cfg2.dailyLossLimitPct() / 100.0);
                        loop.setLossLimitRecoveryBars(cfg2.lossLimitRecoveryBars());
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
                    || "add-strategy-compare".equals(mode) || "today-compare".equals(mode)
                    || "iv-surge-compare".equals(mode);
            if (!keepFull || "strategy-compare".equals(mode) || "reversal-compare".equals(mode)
                    || "overnight-floor-compare".equals(mode) || "avoid-overnight-compare".equals(mode)
                    || "orb-optimize".equals(mode) || "entry-start-compare".equals(mode)
                    || "exit-cutoff-compare".equals(mode) || "position-size-compare".equals(mode)
                    || "loss-limit-recovery-compare".equals(mode) || "entry-confirmation-compare".equals(mode)) {
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

        if ("entry-start-compare".equals(mode)) {
            summaries.sort(Comparator.comparingDouble(RunSummary::returnPct).reversed());
            System.out.printf("%n%-40s  %8s  %8s  %7s  %7s%n", "Entry Start Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(83));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-40s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("entry-confirmation-compare".equals(mode)) {
            System.out.printf("%n%-48s  %8s  %8s  %7s  %7s%n", "Entry Confirmation Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(91));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-48s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("loss-limit-recovery-compare".equals(mode)) {
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "Loss Limit Recovery Config", "Return", "MaxDD", "Trades", "WinRate");
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

        if ("position-size-compare".equals(mode)) {
            summaries.sort(Comparator.comparingDouble(RunSummary::returnPct).reversed());
            System.out.printf("%n%-44s  %8s  %8s  %7s  %7s%n", "Position Size Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(87));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-44s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        if ("exit-cutoff-compare".equals(mode)) {
            summaries.sort(Comparator.comparingDouble(RunSummary::returnPct).reversed());
            System.out.printf("%n%-40s  %8s  %8s  %7s  %7s%n", "Exit Cutoff Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(83));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-40s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
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

        if ("iv-surge-compare".equals(mode) && !results.isEmpty()) {
            System.out.printf("%n%-46s  %8s  %8s  %7s  %7s%n", "IV Surge Config", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(89));
            for (RunResult rr : results) {
                IntradayBacktestResult r = rr.result();
                double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
                System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
            }
            printIvSurgeTodayComparison(results, endDate);
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

    private static void printIvSurgeTodayComparison(List<RunResult> runs, LocalDate today) {
        System.out.println("\n=== TODAY (" + today + ") IV SURGE COMPARISON ===");
        long dayStart = today.atStartOfDay(ET).toInstant().toEpochMilli();
        long dayEnd   = today.plusDays(1).atStartOfDay(ET).toInstant().toEpochMilli();

        for (RunResult rr : runs) {
            IntradayBacktestResult r = rr.result();
            String label = rr.label().trim();

            // Today's day P&L from equity curve
            List<BacktestDataPoint> curve = r.getEquityCurve();
            double simDayPct = Double.NaN;
            for (int i = 0; i < curve.size(); i++) {
                if (curve.get(i).getDate().equals(today)) {
                    double prev = i > 0 ? curve.get(i - 1).getPortfolioValue() : 100_000.0;
                    simDayPct = (curve.get(i).getPortfolioValue() - prev) / prev * 100.0;
                    break;
                }
            }

            // Today's round-trips
            Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
            int roundTrips = 0, wins = 0;
            List<String> txLines = new ArrayList<>();
            List<TransactionRecord> sorted = new ArrayList<>(r.getTrades());
            sorted.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));
            for (TransactionRecord tx : sorted) {
                if (tx.getTimestamp() < dayStart || tx.getTimestamp() >= dayEnd) continue;
                String sym = tx.getSymbol();
                String action = tx.getAction().name();
                boolean isBuy  = action.equals("BUY")  || action.equals("CALL_BUY")  || action.equals("PUT_BUY");
                boolean isSell = action.equals("SELL") || action.equals("CALL_SELL") || action.equals("PUT_SELL");
                if (isBuy) {
                    buyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(tx);
                } else if (isSell) {
                    List<TransactionRecord> buys = buyStack.get(sym);
                    if (buys != null && !buys.isEmpty()) {
                        TransactionRecord buy = buys.remove(0);
                        boolean isOpts = action.startsWith("CALL_") || action.startsWith("PUT_");
                        double pnl = (tx.getPricePerUnit() - buy.getPricePerUnit())
                                * tx.getQuantity() * (isOpts ? 100.0 : 1.0)
                                - buy.getFeeCharged() - tx.getFeeCharged();
                        roundTrips++;
                        if (pnl >= 0) wins++;
                        String type = action.contains("CALL") ? "CALL" : action.contains("PUT") ? "PUT" : "STK";
                        txLines.add(String.format("      %-6s %-4s  entry=$%7.2f exit=$%7.2f x%d  P&L=$%8.2f  %s",
                                sym, type, buy.getPricePerUnit(), tx.getPricePerUnit(), tx.getQuantity(), pnl,
                                tx.getReason() != null ? tx.getReason() : ""));
                    }
                }
            }
            int openAtEnd = buyStack.values().stream().mapToInt(List::size).sum();
            int totalOpened = roundTrips + openAtEnd;

            System.out.printf("%n  [%s]%n", label);
            System.out.printf("    Today P&L   : %s%n", Double.isNaN(simDayPct) ? "n/a" : String.format("%+.2f%%", simDayPct));
            System.out.printf("    Opened today: %d  |  Closed: %d  |  Open at EOD: %d%n",
                    totalOpened, roundTrips, openAtEnd);
            if (roundTrips > 0) {
                System.out.printf("    Win rate    : %.0f%% (%d/%d)%n", 100.0 * wins / roundTrips, wins, roundTrips);
            }
            if (!txLines.isEmpty()) {
                System.out.println("    Trades:");
                txLines.forEach(System.out::println);
            } else {
                System.out.println("    (no closed round-trips today)");
            }
        }
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
