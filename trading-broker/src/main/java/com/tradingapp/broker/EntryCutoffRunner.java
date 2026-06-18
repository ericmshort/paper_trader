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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Tests different last-entry cutoff times to find the optimal window.
 * Force-close is always at 15:45 ET; this controls how late a NEW position
 * can be opened. null = no cutoff (current production behaviour).
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.EntryCutoffRunner
 */
public class EntryCutoffRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final Set<String> PROD_ALLOWLIST = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR","LLY","ORCL","RTX",
            "GS","TSM","TGT","MA","UNH","GILD","AXP","MRNA","COP","XOM",
            "ADBE","LOW","NET","CRWD","PG","AMD","WMT","QCOM");

    private static final Set<String> STRATEGIES = Set.of(
            "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT");

    record Run(String label, LocalTime cutoff) {}

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
        LocalDate startDate = endDate.minusDays(140);

        List<String> watchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = watchlist.size(), idx = 0;
        System.out.println("Fetching bars " + startDate + " → " + endDate + " for " + total + " symbols...");
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

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;

        List<Run> runs = List.of(
                new Run("No cutoff (current production)",  null),
                new Run("Last entry 15:30 ET",             LocalTime.of(15, 30)),
                new Run("Last entry 15:15 ET",             LocalTime.of(15, 15)),
                new Run("Last entry 15:00 ET",             LocalTime.of(15,  0)),
                new Run("Last entry 14:45 ET",             LocalTime.of(14, 45)),
                new Run("Last entry 14:30 ET",             LocalTime.of(14, 30)),
                new Run("Last entry 14:00 ET",             LocalTime.of(14,  0))
        );

        System.out.println("\n=== Entry Cutoff Backtest (force-close always 15:45 ET) ===");
        System.out.println("Config: options-only, 28-sym allowlist, LONG_CALL+LONG_PUT, SL=30%, putMin=3\n");

        record Result(String label, IntradayBacktestResult r) {}
        List<Result> results = new ArrayList<>();

        for (Run run : runs) {
            System.out.println("--- " + run.label() + " ---");
            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = runSim(engine, watchlist, barsBySymbol, 100_000.0, maxExposure, run.cutoff());
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            System.out.printf("Done %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (WR:%.1f%%)%n%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
            results.add(new Result(run.label(), r));
        }

        double baseReturn = results.get(0).r().getTotalReturnPct();
        System.out.println("=== COMPARISON ===");
        System.out.printf("%-36s  %8s  %8s  %7s  %7s  %6s%n",
                "Config", "Return", "MaxDD", "Trades", "WinRate", "Delta");
        System.out.println("-".repeat(84));
        for (Result res : results) {
            double wr = res.r().getTotalTrades() > 0 ? 100.0 * res.r().getWins() / res.r().getTotalTrades() : 0;
            double delta = res.r().getTotalReturnPct() - baseReturn;
            String deltaStr = delta == 0 ? "—" : String.format("%+.2fpp", delta);
            System.out.printf("%-36s  %7.2f%%  %7.2f%%  %7d  %6.1f%%  %s%n",
                    res.label(), res.r().getTotalReturnPct(), res.r().getMaxDrawdownPct(),
                    res.r().getTotalTrades(), wr, deltaStr);
        }
    }

    private static IntradayBacktestResult runSim(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startBalance,
            double maxExposure,
            LocalTime entryCutoff) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(STRATEGIES);
        router.setOptionsAllowlist(PROD_ALLOWLIST);
        router.setDowntrendPutMinSignals(3);
        router.setStopLossFrac(0.30);
        router.setAvoidOvernightHolds(true);
        if (entryCutoff != null) router.setEntryCutoff(entryCutoff);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setMaxConcurrentStockPositions(10);
            loop.setStockTradingEnabled(false);
            loop.setAvoidOvernightHolds(false);
        };

        return engine.run(watchlist, barsBySymbol, startBalance, router,
                msg -> {}, Set.of(), loopConfig);
    }
}
