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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Backtests whether force-closing all options positions at 15:45 ET improves or
 * hurts returns under the current production config (options-only, 28-sym allowlist,
 * LONG_CALL+LONG_PUT, SL=30%, putMin=3, no symbol filters).
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.OvernightHoldsRunner
 */
public class OvernightHoldsRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final Set<String> PROD_ALLOWLIST = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR","LLY","ORCL","RTX",
            "GS","TSM","TGT","MA","UNH","GILD","AXP","MRNA","COP","XOM",
            "ADBE","LOW","NET","CRWD","PG","AMD","WMT","QCOM");

    private static final Set<String> STRATEGIES = Set.of(
            "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT");

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

        System.out.println("\n=== Overnight Holds Backtest: Force-close at 15:45 ET ===");
        System.out.println("Config: options-only, 28-sym allowlist, LONG_CALL+LONG_PUT, SL=30%, putMin=3\n");

        System.out.println("--- Force-close ON (production) ---");
        IntradayBacktestResult withClose = runSim(engine, watchlist, barsBySymbol, 100_000.0, maxExposure, true);
        printRow(withClose);

        System.out.println("\n--- Force-close OFF (hold until expiry / stop-loss only) ---");
        IntradayBacktestResult withoutClose = runSim(engine, watchlist, barsBySymbol, 100_000.0, maxExposure, false);
        printRow(withoutClose);

        System.out.println("\n=== COMPARISON ===");
        System.out.printf("%-40s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(78));
        printSummaryRow("Force-close ON  (production)",  withClose);
        printSummaryRow("Force-close OFF (hold to expiry/SL)", withoutClose);

        double delta = withoutClose.getTotalReturnPct() - withClose.getTotalReturnPct();
        System.out.printf("%nLifting force-close: %+.2fpp return, %+.2fpp MaxDD%n",
                delta,
                withoutClose.getMaxDrawdownPct() - withClose.getMaxDrawdownPct());
        System.out.println(delta > 0.5 ? "▲ Better WITHOUT force-close"
                         : delta < -0.5 ? "▼ Better WITH force-close"
                         : "→ No meaningful difference");
    }

    private static IntradayBacktestResult runSim(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startBalance,
            double maxExposure,
            boolean avoidOvernight) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(STRATEGIES);
        router.setOptionsAllowlist(PROD_ALLOWLIST);
        router.setDowntrendPutMinSignals(3);
        router.setStopLossFrac(0.30);
        router.setAvoidOvernightHolds(avoidOvernight);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setMaxConcurrentStockPositions(10);
            loop.setStockTradingEnabled(false);
            loop.setAvoidOvernightHolds(false); // equity side already disabled; options handled by router
        };

        return engine.run(watchlist, barsBySymbol, startBalance, router,
                msg -> {}, Set.of(), loopConfig);
    }

    private static void printRow(IntradayBacktestResult r) {
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
        System.out.printf("Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (WR:%.1f%%)%n",
                r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
    }

    private static void printSummaryRow(String label, IntradayBacktestResult r) {
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
        System.out.printf("%-40s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                label, r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
    }
}
