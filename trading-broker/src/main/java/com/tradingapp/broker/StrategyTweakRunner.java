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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares four strategy variants against the 2-year backtest window:
 *
 *   A  Baseline          — current production config
 *   B  HolidayGuard      — force-close at 12:30 / entry cutoff 11:30 on half-days
 *   C  SymbolPrune       — remove AXP, GILD, MRNA from options allowlist
 *   D  Both              — HolidayGuard + SymbolPrune
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.StrategyTweakRunner
 */
public class StrategyTweakRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Current production allowlist (28 symbols: watchlist minus QQQ and TSLA)
    private static final Set<String> ALLOWLIST_PROD = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR",
            "LLY","ORCL","RTX","GS","TSM","TGT",
            "MA","UNH","GILD","ADBE","AXP","LOW",
            "MRNA","COP","XOM","NET","CRWD","PG","AMD","WMT","QCOM");

    // Pruned allowlist — remove AXP (-$13.4K), GILD (-$9.7K), MRNA (-$5.8K) over 2yr backtest
    private static final Set<String> ALLOWLIST_PRUNED = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR",
            "LLY","ORCL","RTX","GS","TSM","TGT",
            "MA","UNH","ADBE","LOW",
            "COP","XOM","NET","CRWD","PG","AMD","WMT","QCOM");

    private static final Set<String> STRATEGIES = Set.of(
            "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT");

    record RunCfg(String label, Set<String> allowlist, boolean holidayGuard) {}
    record RunResult(String label, IntradayBacktestResult result) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not configured.");
            System.exit(1);
        }

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(800);

        List<String> watchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();

        System.out.println("=== Strategy Tweak Runner ===");
        System.out.println("Period: " + startDate + " → " + endDate);
        System.out.println("Fetching bars for " + watchlist.size() + " symbols...");

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
        System.out.println("Loaded " + barsBySymbol.size() + " symbols. Running variants...\n");

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator())
                .setCollectEventLog(false);
        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;

        List<RunCfg> configs = List.of(
                new RunCfg("A: Baseline (production)",          ALLOWLIST_PROD,   false),
                new RunCfg("B: HolidayGuard",                   ALLOWLIST_PROD,   true),
                new RunCfg("C: SymbolPrune (-AXP,-GILD,-MRNA)", ALLOWLIST_PRUNED, false),
                new RunCfg("D: Both (HolidayGuard + Prune)",    ALLOWLIST_PRUNED, true)
        );

        List<RunResult> results = new ArrayList<>();

        for (RunCfg rc : configs) {
            System.out.println("=== " + rc.label() + " ===");
            OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
            OptionsSignalRouter router = new OptionsSignalRouter(
                    new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
            router.setMaxPortfolioExposure(maxExposure);
            router.setEnabledStrategies(STRATEGIES);
            router.setStopLossFrac(cfg.getOptionsStopLossFrac());
            router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
            if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
            router.setOptionsAllowlist(rc.allowlist());
            router.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
            router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
            router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());

            // HolidayGuard is built into MarketCalendar + OptionsSignalRouter.resetForDay()
            // when avoidOvernightHolds=true (already set above from cfg).
            // For the non-guard variants, we override avoidOvernightHolds to false so resetForDay
            // doesn't apply the half-day cutoff — but we still want normal overnight avoidance.
            // Strategy: toggle via a flag rather than removing avoidOvernightHolds entirely.
            if (!rc.holidayGuard()) {
                router.setHolidayGuardEnabled(false);
            }

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(watchlist, barsBySymbol, 100_000.0, router, msg -> {},
                    Set.of(), loop -> {
                        router.setUptrendSupplier(loop::isUptrend);
                        loop.setStockTradingEnabled(false);
                        loop.setMaxConcurrentStockPositions(10);
                        loop.setAvoidOvernightHolds(false);
                    });
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            System.out.printf("Done in %.1fs  Return=%.2f%%  MaxDD=%.2f%%  Trades=%d  WR=%.1f%%%n%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
            results.add(new RunResult(rc.label(), r));
        }

        System.out.println("=== COMPARISON ===");
        System.out.printf("%-42s  %8s  %8s  %7s  %7s  %10s%n",
                "Config", "Return", "MaxDD", "Trades", "WinRate", "Balance");
        System.out.println("-".repeat(92));

        RunResult baseline = results.get(0);
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            double delta = r.getTotalReturnPct() - baseline.result().getTotalReturnPct();
            String deltaStr = results.indexOf(rr) == 0 ? "         " : String.format("%+7.2fpp", delta);
            System.out.printf("%-42s  %7.2f%%  %7.2f%%  %7d  %6.1f%%  $%,10.2f  %s%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), wr, r.getFinalBalance(), deltaStr);
        }
    }
}
