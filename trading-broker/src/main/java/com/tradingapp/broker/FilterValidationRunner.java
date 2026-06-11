package com.tradingapp.broker;

import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.engine.TradingLoop;
import com.tradingapp.account.Account;
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
 * Validates whether the MSFT calls-disabled and NVDA puts-disabled filters still improve
 * returns under the current production config (options-only, 28-symbol allowlist, LONG_CALL,
 * stop-loss=30%, downtrend-put-min=3).
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.FilterValidationRunner
 */
public class FilterValidationRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static final Set<String> PROD_ALLOWLIST = Set.of(
            "SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR","LLY","ORCL","RTX",
            "GS","TSM","TGT","MA","UNH","GILD","AXP","MRNA","COP","XOM",
            "ADBE","LOW","NET","CRWD","PG","AMD","WMT","QCOM");

    private static final Set<String> STRATEGIES = Set.of(
            "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT");

    record Run(String label, Set<String> callsDisabled, Set<String> putsDisabled) {}

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
                new Run("Production (MSFT calls off, NVDA puts off)", Set.of("MSFT"), Set.of("NVDA")),
                new Run("MSFT calls ON  (NVDA puts still off)",       Set.of(),        Set.of("NVDA")),
                new Run("NVDA puts ON   (MSFT calls still off)",      Set.of("MSFT"), Set.of()),
                new Run("No restrictions (both filters lifted)",       Set.of(),        Set.of())
        );

        System.out.println("\n=== Filter Validation: MSFT calls-disabled / NVDA puts-disabled ===");
        System.out.println("Config: options-only, 28-sym allowlist, LONG_CALL+LONG_PUT, SL=30%, putMin=3\n");

        record Result(String label, IntradayBacktestResult r) {}
        List<Result> results = new ArrayList<>();

        for (Run run : runs) {
            System.out.println("--- " + run.label() + " ---");
            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = runSim(engine, watchlist, barsBySymbol, 100_000.0,
                    maxExposure, run.callsDisabled(), run.putsDisabled());
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0;
            System.out.printf("Done %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (WR:%.1f%%)%n%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
            results.add(new Result(run.label(), r));
        }

        System.out.println("=== COMPARISON ===");
        System.out.printf("%-46s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(85));
        double prodReturn = results.get(0).r().getTotalReturnPct();
        for (Result res : results) {
            double wr = res.r().getTotalTrades() > 0 ? 100.0 * res.r().getWins() / res.r().getTotalTrades() : 0;
            double delta = res.r().getTotalReturnPct() - prodReturn;
            String marker = delta > 0.5 ? "  ▲ +" + String.format("%.2f", delta) + "pp"
                          : delta < -0.5 ? "  ▼ " + String.format("%.2f", delta) + "pp"
                          : "";
            System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%s%n",
                    res.label(), res.r().getTotalReturnPct(), res.r().getMaxDrawdownPct(),
                    res.r().getTotalTrades(), wr, marker);
        }
    }

    private static IntradayBacktestResult runSim(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startBalance,
            double maxExposure,
            Set<String> callsDisabled,
            Set<String> putsDisabled) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(STRATEGIES);
        router.setOptionsAllowlist(PROD_ALLOWLIST);
        router.setCallsDisabledSymbols(callsDisabled);
        router.setPutsDisabledSymbols(putsDisabled);
        router.setDowntrendPutMinSignals(3);
        router.setStopLossFrac(0.30);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setMaxConcurrentStockPositions(10);
            loop.setStockTradingEnabled(false);
        };

        return engine.run(watchlist, barsBySymbol, startBalance, router,
                msg -> {}, Set.of(), loopConfig);
    }
}
