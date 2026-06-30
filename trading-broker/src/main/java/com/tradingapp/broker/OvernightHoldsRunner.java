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

    // Floor thresholds to sweep: fraction of entry premium that must remain to hold overnight.
    // 0.0 = hold everything, 0.3 = close if worth < 30% of entry, etc.
    private static final double[] FLOOR_THRESHOLDS = { 0.3, 0.5, 0.7, 0.8 };

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

        System.out.println("\n=== Overnight Holds Backtest ===");
        System.out.println("Period : " + startDate + " → " + endDate);
        System.out.println("Config : options-only, 28-sym allowlist, LONG_CALL+LONG_PUT, SL=30%, putMin=3\n");

        System.out.printf("%-42s  %8s  %8s  %7s  %6s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(80));

        // Baseline: force-close all at 15:45
        IntradayBacktestResult forceClose = runSim(engine, watchlist, barsBySymbol, 100_000.0, maxExposure, true, 0.0);
        printSummaryRow("Force-close ON  (production)", forceClose);

        // Floor sweep: avoidOvernight=false, cut only losers below threshold
        for (double floor : FLOOR_THRESHOLDS) {
            IntradayBacktestResult r = runSim(engine, watchlist, barsBySymbol, 100_000.0, maxExposure, false, floor);
            printSummaryRow(String.format("Floor %.0f%% (cut < %.0f%% of entry)", floor * 100, floor * 100), r);
        }

        // Far end: hold everything overnight, no floor
        IntradayBacktestResult holdAll = runSim(engine, watchlist, barsBySymbol, 100_000.0, maxExposure, false, 0.0);
        printSummaryRow("Hold all overnight (no floor)", holdAll);
    }

    private static IntradayBacktestResult runSim(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startBalance,
            double maxExposure,
            boolean avoidOvernight,
            double overnightFloor) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(STRATEGIES);
        router.setOptionsAllowlist(PROD_ALLOWLIST);
        router.setDowntrendPutMinSignals(3);
        router.setStopLossFrac(0.30);
        router.setAvoidOvernightHolds(avoidOvernight);
        router.setOvernightMinPremiumFrac(overnightFloor);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setMaxConcurrentStockPositions(10);
            loop.setStockTradingEnabled(false);
            loop.setAvoidOvernightHolds(false); // equity side already disabled; options handled by router
        };

        return engine.run(watchlist, barsBySymbol, startBalance, router,
                msg -> {}, Set.of(), loopConfig);
    }

    private static void printSummaryRow(String label, IntradayBacktestResult r) {
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
        System.out.printf("%-42s  %7.2f%%  %7.2f%%  %7d  %5.1f%%%n",
                label, r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
    }
}
