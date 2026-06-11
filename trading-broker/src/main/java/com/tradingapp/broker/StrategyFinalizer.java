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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Focused follow-up to StrategyTuningRunner.
 *
 * LONG_CALL adds +12.5pp (66.33% -> 78.81%) but MaxDD jumps 3.18% -> 15.87%.
 * Tests whether tighter risk controls can preserve the gain with lower MaxDD:
 *
 *   Run 1: Baseline (no LONG_CALL)
 *   Run 2: +LONG_CALL, sl=40%
 *   Run 3: +LONG_CALL, sl=30%
 *   Run 4: +LONG_CALL, core mega-caps only for calls (9 symbols)
 *   Run 5: +LONG_CALL, core-only + sl=40% + pt=3x
 *   Run 6: +LONG_CALL, full allowlist + sl=40% + pt=3x
 *
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.StrategyFinalizer
 */
public class StrategyFinalizer {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final Set<String> FULL_ALLOWLIST = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR","LLY","ORCL","RTX","GS","TSM",
            "TGT","MA","UNH","GILD","AXP","MRNA","COP","XOM","ADBE","LOW","NET","CRWD","PG",
            "AMD","WMT","QCOM");

    // Only let LONG_CALL fire on deepest-liquid, highest-conviction symbols
    private static final Set<String> CORE_CALLS = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR","TSLA","QQQ");

    private static final Set<String> BASE      = Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT");
    private static final Set<String> PLUS_CALL = Set.of("HIGH_DELTA_SCALP","MOMENTUM_NEAR_TERM","LONG_PUT","LONG_CALL");

    record RR(String label, IntradayBacktestResult r) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank()) { System.err.println("ERROR: no API key"); System.exit(1); }

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate end = LocalDate.now(ET).minusDays(1);
        while (end.getDayOfWeek() == DayOfWeek.SATURDAY || end.getDayOfWeek() == DayOfWeek.SUNDAY)
            end = end.minusDays(1);
        LocalDate start = end.minusDays(140);

        List<String> wl = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        System.out.println("=== LONG_CALL Risk-Control Finalizer ===");
        System.out.println("Fetching bars " + start + " -> " + end + "...");

        Map<String, List<IntradayBar>> bars = new LinkedHashMap<>();
        int tot = wl.size(), n = 0;
        for (String sym : wl) {
            n++;
            final int cur = n;
            try {
                List<IntradayBar> b = client.fetchMinuteBars(sym, start, end,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, tot, msg));
                if (!b.isEmpty()) bars.put(sym, b);
            } catch (Exception e) { System.out.printf("SKIP %s: %s%n", sym, e.getMessage()); }
        }
        System.out.println("Loaded " + bars.size() + " symbols.\n");

        double maxExp = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        List<RR> results = new ArrayList<>();

        System.out.println("Run 1: Baseline");
        results.add(new RR("Baseline — no LONG_CALL",
                run(engine, wl, bars, maxExp, BASE, FULL_ALLOWLIST, 0.50, 2.0)));

        System.out.println("Run 2: +LONG_CALL, sl=50% (same as tuning run)");
        results.add(new RR("+LONG_CALL  sl=50%  full allowlist",
                run(engine, wl, bars, maxExp, PLUS_CALL, FULL_ALLOWLIST, 0.50, 2.0)));

        System.out.println("Run 3: +LONG_CALL, sl=40%");
        results.add(new RR("+LONG_CALL  sl=40%  full allowlist",
                run(engine, wl, bars, maxExp, PLUS_CALL, FULL_ALLOWLIST, 0.40, 2.0)));

        System.out.println("Run 4: +LONG_CALL, sl=30%");
        results.add(new RR("+LONG_CALL  sl=30%  full allowlist",
                run(engine, wl, bars, maxExp, PLUS_CALL, FULL_ALLOWLIST, 0.30, 2.0)));

        System.out.println("Run 5: +LONG_CALL, core mega-caps only");
        results.add(new RR("+LONG_CALL  sl=50%  core-only (9 syms)",
                run(engine, wl, bars, maxExp, PLUS_CALL, CORE_CALLS, 0.50, 2.0)));

        System.out.println("Run 6: +LONG_CALL, core-only + sl=40% + pt=3x");
        results.add(new RR("+LONG_CALL  sl=40%  pt=3x  core-only",
                run(engine, wl, bars, maxExp, PLUS_CALL, CORE_CALLS, 0.40, 3.0)));

        System.out.println("Run 7: +LONG_CALL, full allowlist + sl=40% + pt=3x");
        results.add(new RR("+LONG_CALL  sl=40%  pt=3x  full allowlist",
                run(engine, wl, bars, maxExp, PLUS_CALL, FULL_ALLOWLIST, 0.40, 3.0)));

        System.out.println("\n=== COMPARISON ===");
        System.out.printf("%-42s  %8s  %7s  %6s  %7s%n",
                "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(74));
        for (RR rr : results) {
            double wr = rr.r().getTotalTrades() > 0 ? 100.0 * rr.r().getWins() / rr.r().getTotalTrades() : 0.0;
            System.out.printf("%-42s  %7.2f%%  %6.2f%%  %6d  %6.1f%%%n",
                    rr.label(), rr.r().getTotalReturnPct(), rr.r().getMaxDrawdownPct(),
                    rr.r().getTotalTrades(), wr);
        }

        results.stream().filter(rr -> rr.r().getMaxDrawdownPct() <= 6.0)
                .max(Comparator.comparingDouble(rr -> rr.r().getTotalReturnPct()))
                .ifPresent(rr -> System.out.printf("%nBest risk-adjusted (MaxDD <= 6%%): %s  %.2f%%%n",
                        rr.label(), rr.r().getTotalReturnPct()));
    }

    private static IntradayBacktestResult run(
            IntradayBacktestEngine engine, List<String> wl,
            Map<String, List<IntradayBar>> bars, double maxExp,
            Set<String> strategies, Set<String> allowlist,
            double sl, double pt) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExp);
        router.setEnabledStrategies(strategies);
        router.setOptionsAllowlist(allowlist);
        router.setCallsDisabledSymbols(Set.of());
        router.setPutsDisabledSymbols(Set.of());
        router.setDowntrendPutMinSignals(3);
        router.setStopLossFrac(sl);
        router.setProfitTarget(pt);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setStockTradingEnabled(false);
        };

        long t0 = System.currentTimeMillis();
        IntradayBacktestResult r = engine.run(wl, bars, 100_000.0, router, msg -> {}, Set.of(), loopConfig);
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        System.out.printf("  Return=%+.2f%%  MaxDD=%.2f%%  Trades=%d  WR=%.1f%%  (%.1fs)%n",
                r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr,
                (System.currentTimeMillis() - t0) / 1000.0);
        return r;
    }
}
