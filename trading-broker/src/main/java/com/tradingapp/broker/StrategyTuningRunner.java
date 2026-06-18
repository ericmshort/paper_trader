package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.data.DayTraderWatchList;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * Deep-dive strategy tuning research. Tests seven independent levers against the
 * current D2 production config (38.58% baseline, 30-symbol options-only, LONG_PUT min=3).
 *
 *   Section A — Enable LONG_CALL (3-signal uptrend entries currently fire nothing)
 *   Section B — Enable ZERO_DTE straddles (mixed signals on Fridays currently fire nothing)
 *   Section C — Stop-loss tuning: 30% / 40% / 60% / 70% (current: 50%)
 *   Section D — Profit-target tuning: 1.5x / 3x (current: 2x)
 *   Section E — Entry timing: skip first 30 min and/or cut off at 15:00
 *   Section F — Cooldown tuning: 5 min / 10 min (current: 15 min)
 *   Section G — Combined best-of-each-section hypothesis
 *
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.StrategyTuningRunner
 */
public class StrategyTuningRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Production options allowlist (28 symbols — full new watchlist minus QQQ/TSLA)
    private static final Set<String> OPTS_ALLOWLIST = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR","LLY","ORCL","RTX","GS","TSM",
            "TGT","MA","UNH","GILD","AXP","MRNA","COP","XOM","ADBE","LOW","NET","CRWD","PG",
            "AMD","WMT","QCOM");

    record RunResult(String label, IntradayBacktestResult result) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in app.properties");
            System.exit(1);
        }

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(140);

        List<String> watchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        System.out.println("=== Strategy Tuning Deep Dive ===");
        System.out.println("Fetching bars " + startDate + " -> " + endDate
                + " for " + watchlist.size() + " symbols...");

        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = watchlist.size(), idx = 0;
        for (String sym : watchlist) {
            idx++;
            final int cur = idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.printf("SKIP %s: %s%n", sym, e.getMessage());
            }
        }
        System.out.println("Fetched " + barsBySymbol.size() + " symbols.");

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        List<RunResult> results = new ArrayList<>();

        // ── Baseline (current production config) ─────────────────────────────
        System.out.println("\n=== BASELINE (production config) ===");
        results.add(new RunResult("Baseline (current production)",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                        0.50, 2.0, 15L, null, null)));

        // ── Section A: Enable LONG_CALL ───────────────────────────────────────
        System.out.println("\n=== SECTION A: LONG_CALL enabled ===");
        results.add(new RunResult("A: +LONG_CALL",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","LONG_CALL"),
                        0.50, 2.0, 15L, null, null)));

        // ── Section B: Enable ZERO_DTE ────────────────────────────────────────
        System.out.println("\n=== SECTION B: ZERO_DTE enabled ===");
        results.add(new RunResult("B1: +ZERO_DTE",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","ZERO_DTE"),
                        0.50, 2.0, 15L, null, null)));
        results.add(new RunResult("B2: +LONG_CALL +ZERO_DTE",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","LONG_CALL","ZERO_DTE"),
                        0.50, 2.0, 15L, null, null)));

        // ── Section C: Stop-loss tuning ───────────────────────────────────────
        System.out.println("\n=== SECTION C: Stop-loss tuning ===");
        for (double sl : new double[]{0.30, 0.40, 0.60, 0.70}) {
            String label = String.format("C: stopLoss=%.0f%%", sl * 100);
            results.add(new RunResult(label,
                    run(engine, watchlist, barsBySymbol, maxExposure,
                            Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                            sl, 2.0, 15L, null, null)));
        }

        // ── Section D: Profit-target tuning ──────────────────────────────────
        System.out.println("\n=== SECTION D: Profit-target tuning ===");
        for (double pt : new double[]{1.5, 3.0}) {
            String label = String.format("D: profitTarget=%.1fx", pt);
            results.add(new RunResult(label,
                    run(engine, watchlist, barsBySymbol, maxExposure,
                            Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                            0.50, pt, 15L, null, null)));
        }

        // ── Section E: Entry timing ───────────────────────────────────────────
        System.out.println("\n=== SECTION E: Entry timing ===");
        results.add(new RunResult("E1: no trades before 10:00",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                        0.50, 2.0, 15L, LocalTime.of(10, 0), null)));
        results.add(new RunResult("E2: no new entries after 15:00",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                        0.50, 2.0, 15L, null, LocalTime.of(15, 0))));
        results.add(new RunResult("E3: after 10:00 + before 15:00",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                        0.50, 2.0, 15L, LocalTime.of(10, 0), LocalTime.of(15, 0))));

        // ── Section F: Cooldown tuning ────────────────────────────────────────
        System.out.println("\n=== SECTION F: Cooldown tuning ===");
        for (long cd : new long[]{5L, 10L}) {
            String label = "F: cooldown=" + cd + "min";
            results.add(new RunResult(label,
                    run(engine, watchlist, barsBySymbol, maxExposure,
                            Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT"),
                            0.50, 2.0, cd, null, null)));
        }

        // ── Section G: Combined best hypothesis ──────────────────────────────
        // Pick the single best from each section based on return and build combos.
        System.out.println("\n=== SECTION G: Combined best hypothesis ===");

        // G1: +LONG_CALL + best stop-loss + best profit target + best timing + best cooldown
        // We'll test the two most promising combos once we know individual results,
        // but pre-build two plausible combinations here.
        results.add(new RunResult("G1: +LONG_CALL +sl=40% +pt=3x +10:00 start +cd=10m",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","LONG_CALL"),
                        0.40, 3.0, 10L, LocalTime.of(10, 0), null)));
        results.add(new RunResult("G2: +LONG_CALL +ZERO_DTE +sl=40% +pt=3x +10:00 start",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","LONG_CALL","ZERO_DTE"),
                        0.40, 3.0, 15L, LocalTime.of(10, 0), null)));
        results.add(new RunResult("G3: +LONG_CALL +sl=30% +pt=3x +10:00 start +cd=5m",
                run(engine, watchlist, barsBySymbol, maxExposure,
                        Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","LONG_CALL"),
                        0.30, 3.0, 5L, LocalTime.of(10, 0), null)));

        // ── Comparison table ──────────────────────────────────────────────────
        System.out.println("\n=== COMPARISON ===");
        System.out.printf("%-50s  %8s  %7s  %6s  %7s%n",
                "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(82));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("%-50s  %7.2f%%  %6.2f%%  %6d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), wr);
        }

        RunResult best = results.stream()
                .max(Comparator.comparingDouble(rr -> rr.result().getTotalReturnPct()))
                .orElseThrow();
        System.out.println("\nBest: " + best.label()
                + " → " + String.format("%.2f%%", best.result().getTotalReturnPct()));
    }

    private static IntradayBacktestResult run(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double maxExposure,
            Set<String> strategies,
            double stopLoss,
            double profitTarget,
            long cooldownMin,
            LocalTime entryStart,
            LocalTime entryCutoff) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(strategies);
        router.setOptionsAllowlist(OPTS_ALLOWLIST);
        router.setCallsDisabledSymbols(Set.of());
        router.setPutsDisabledSymbols(Set.of());
        router.setDowntrendPutMinSignals(3);
        router.setStopLossFrac(stopLoss);
        router.setProfitTarget(profitTarget);
        router.setCooldownMinutes(cooldownMin);
        if (entryStart  != null) router.setEntryStartTime(entryStart);
        if (entryCutoff != null) router.setEntryCutoff(entryCutoff);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setStockTradingEnabled(false);
        };

        long t0 = System.currentTimeMillis();
        IntradayBacktestResult r = engine.run(watchlist, barsBySymbol, 100_000.0, router,
                msg -> {}, Set.of(), loopConfig);
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        System.out.printf("  Done %.1fs  Return=%+.2f%%  MaxDD=%.2f%%  Trades=%d (W:%d L:%d  WR:%.1f%%)%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                r.getTotalTrades(), r.getWins(), r.getLosses(), wr);
        return r;
    }
}
