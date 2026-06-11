package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.BacktestDataPoint;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.engine.TradingLoop;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Return improvement research backtest. Tests three independent levers:
 *
 *   Section A — Watchlist re-screening:
 *     The original watchlist was screened under a broken backtest (uptrendSupplier
 *     never wired). With the corrected backtest, 8 symbols are net-negative.
 *     Tests removing them to isolate the delta.
 *
 *   Section B — Concurrent positions cap:
 *     26,460 valid signals were blocked by the 5-position cap. Tests cap=5/8/10.
 *
 *   Section C — Options vs stock allocation:
 *     Options avg $90/trade (PUT) and $38/trade (CALL). Stock avg $1.16/trade.
 *     Tests stock-disabled (options-only) to isolate options P&L ceiling.
 *
 *   Section D — Combined best hypothesis:
 *     Re-screened watchlist + cap=10 + stock enabled/disabled.
 *
 * All runs use LONG_PUT(min=3) to match current live config.
 * All runs wire uptrendSupplier correctly.
 *
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.ReturnResearchRunner
 */
public class ReturnResearchRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ET);

    // Net-losing symbols under the corrected backtest — originally screened under broken uptrend wiring
    private static final Set<String> LOSERS = Set.of("CVX", "SBUX", "REGN", "ADBE", "LOW", "AVGO", "NOW", "HD");

    record RunResult(String label, IntradayBacktestResult result) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "return-research-report.txt");
        Files.createDirectories(reportPath.getParent());
        System.out.println("=== Return Improvement Research Backtest ===");
        System.out.println("Report: " + reportPath);

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(140);

        System.out.println("Fetching bars " + startDate + " -> " + endDate + " for "
                + DayTraderWatchList.SYMBOLS.size() + " symbols...");
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = DayTraderWatchList.SYMBOLS.size(), idx = 0;
        for (String sym : DayTraderWatchList.SYMBOLS) {
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
        if (barsBySymbol.isEmpty()) { System.err.println("ERROR: no bar data"); System.exit(1); }
        System.out.println("Fetched " + barsBySymbol.size() + " symbols.");

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        List<RunResult> results = new ArrayList<>();

        // Watchlist / options-allowlist variants
        List<String> fullWl      = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        List<String> screenedWl  = DayTraderWatchList.SYMBOLS.stream()
                .filter(s -> !LOSERS.contains(s)).collect(Collectors.toList());

        Set<String> BASE_OPTS     = Set.of("SPY","AAPL","MSFT","NVDA","META","AMZN","PLTR",
                                            "LLY","HD","ORCL","RTX","GS","TSM","TGT",
                                            "MA","CVX","UNH","NOW","GILD","SBUX","ADBE",
                                            "AXP","LOW","REGN","MRNA","COP","XOM","AVGO");
        Set<String> SCREENED_OPTS = BASE_OPTS.stream()
                .filter(s -> !LOSERS.contains(s)).collect(Collectors.toSet());
        Set<String> CALLS_DISABLED = Set.of("MSFT");
        Set<String> PUTS_DISABLED  = Set.of("NVDA");

        // ── Baseline (reference for all sections) ─────────────────────────────
        System.out.println("\n=== BASELINE: 30 symbols, cap=5, stock+options ===");
        results.add(new RunResult("Baseline: 30sym cap=5 stock+opts",
                runSim(engine, fullWl, barsBySymbol, 100_000.0, maxExposure,
                        BASE_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, 5, true)));

        // ── Section A: Watchlist re-screening ─────────────────────────────────
        System.out.println("\n=== SECTION A: Watchlist Re-Screening ===");
        System.out.println("Removing 8 net-losers: " + LOSERS);
        System.out.println("\n--- A1: 22 symbols (losers removed), cap=5 ---");
        results.add(new RunResult("A1: 22sym (losers removed) cap=5",
                runSim(engine, screenedWl, barsBySymbol, 100_000.0, maxExposure,
                        SCREENED_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, 5, true)));

        // ── Section B: Concurrent positions cap ───────────────────────────────
        System.out.println("\n=== SECTION B: Concurrent Positions Cap ===");
        for (int cap : new int[]{8, 10}) {
            String label = "B: 30sym cap=" + cap;
            System.out.println("\n--- " + label + " ---");
            results.add(new RunResult(label,
                    runSim(engine, fullWl, barsBySymbol, 100_000.0, maxExposure,
                            BASE_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, cap, true)));
        }

        // ── Section C: Options-only (stock trading disabled) ──────────────────
        System.out.println("\n=== SECTION C: Options-Only (stock disabled) ===");
        System.out.println("\n--- C1: 30sym, stock disabled ---");
        results.add(new RunResult("C1: 30sym stock disabled",
                runSim(engine, fullWl, barsBySymbol, 100_000.0, maxExposure,
                        BASE_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, 5, false)));

        // ── Section D: Combined best hypothesis ───────────────────────────────
        System.out.println("\n=== SECTION D: Combined Best Hypothesis ===");
        System.out.println("\n--- D1: 22sym + cap=10 + stock enabled ---");
        results.add(new RunResult("D1: 22sym cap=10 stock+opts",
                runSim(engine, screenedWl, barsBySymbol, 100_000.0, maxExposure,
                        SCREENED_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, 10, true)));

        System.out.println("\n--- D2: 22sym + cap=10 + stock disabled ---");
        results.add(new RunResult("D2: 22sym cap=10 stock disabled",
                runSim(engine, screenedWl, barsBySymbol, 100_000.0, maxExposure,
                        SCREENED_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, 10, false)));

        System.out.println("\n--- D3: 30sym + cap=10 + stock disabled ---");
        results.add(new RunResult("D3: 30sym cap=10 stock disabled",
                runSim(engine, fullWl, barsBySymbol, 100_000.0, maxExposure,
                        BASE_OPTS, CALLS_DISABLED, PUTS_DISABLED, 3, 10, false)));

        // ── Comparison table ──────────────────────────────────────────────────
        printComparison(results);

        RunResult best = results.stream()
                .max(Comparator.comparingDouble(rr -> rr.result().getTotalReturnPct()))
                .orElseThrow();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            writeReport(out, results, best, startDate, endDate);
        }
        System.out.println("\nReport written for best run (" + best.label() + "): " + reportPath);
    }

    private static IntradayBacktestResult runSim(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            double startBalance,
            double maxExposure,
            Set<String> optsAllowlist,
            Set<String> callsDisabled,
            Set<String> putsDisabled,
            int downtrendPutMin,
            int concurrentCap,
            boolean stockEnabled) throws Exception {

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_PUT"));
        router.setOptionsAllowlist(optsAllowlist);
        router.setCallsDisabledSymbols(callsDisabled);
        router.setPutsDisabledSymbols(putsDisabled);
        router.setDowntrendPutMinSignals(downtrendPutMin);

        Consumer<TradingLoop> loopConfig = loop -> {
            router.setUptrendSupplier(loop::isUptrend);
            loop.setMaxConcurrentStockPositions(concurrentCap);
            loop.setStockTradingEnabled(stockEnabled);
        };

        long t0 = System.currentTimeMillis();
        IntradayBacktestResult r = engine.run(watchlist, barsBySymbol, startBalance, router,
                msg -> {}, Set.of(), loopConfig);
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d  WR:%.1f%%)%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                r.getTotalTrades(), r.getWins(), r.getLosses(), wr);
        return r;
    }

    private static void printComparison(List<RunResult> results) {
        System.out.println("\n=== COMPARISON ===");
        System.out.printf("%-38s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(78));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("%-38s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
        }
    }

    private static void writeReport(PrintWriter out, List<RunResult> results, RunResult best,
                                    LocalDate startDate, LocalDate endDate) {
        out.println("=== RETURN IMPROVEMENT RESEARCH REPORT ===");
        out.println("Period   : " + startDate + " to " + endDate);
        out.println("Generated: " + ZonedDateTime.now(ET)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        out.println();
        out.println("All runs use LONG_PUT(min=3) + corrected uptrendSupplier wiring.");
        out.println();
        out.println("Section A: Remove 8 net-losers (CVX SBUX REGN ADBE LOW AVGO NOW HD)");
        out.println("Section B: Raise concurrent stock cap from 5 → 8/10 (26,460 blocked signals)");
        out.println("Section C: Disable stock trades — isolate options-only P&L ceiling");
        out.println("Section D: Combined best-hypothesis configurations");
        out.println();

        out.println("=== COMPARISON TABLE ===");
        out.printf("%-38s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        out.println("-".repeat(78));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            out.printf("%-38s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
        }
        out.println();

        out.println("=== BEST RESULT: " + best.label() + " ===");
        IntradayBacktestResult br = best.result();
        out.println("--- SUMMARY ---");
        out.printf("Final Balance  : $%,.2f%n", br.getFinalBalance());
        out.printf("Total Return   : %.2f%%%n", br.getTotalReturnPct());
        out.printf("Max Drawdown   : %.2f%%%n", br.getMaxDrawdownPct());
        out.printf("Total Trades   : %d (wins=%d losses=%d)%n",
                br.getTotalTrades(), br.getWins(), br.getLosses());
        if (br.getTotalTrades() > 0)
            out.printf("Win Rate       : %.1f%%%n", 100.0 * br.getWins() / br.getTotalTrades());
        out.println();

        out.println("--- DAILY EQUITY CURVE ---");
        List<BacktestDataPoint> curve = br.getEquityCurve();
        double prev = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            double pct = prev > 0 ? (pt.getPortfolioValue() - prev) / prev * 100.0 : 0;
            out.printf("%s  $%,10.2f  %+.2f%%%n", pt.getDate(), pt.getPortfolioValue(), pct);
            prev = pt.getPortfolioValue();
        }
        out.println();

        // Round-trip P&L by symbol and type for the best run
        List<TransactionRecord> trades = br.getTrades();
        if (!trades.isEmpty()) {
            Map<String, double[]> bySymbol = new java.util.TreeMap<>();
            Map<String, double[]> byType   = new java.util.TreeMap<>();
            Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
            List<TransactionRecord> sorted = new ArrayList<>(trades);
            sorted.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));

            for (TransactionRecord r : sorted) {
                String sym = r.getSymbol();
                String action = r.getAction().name();
                boolean isBuy  = action.equals("BUY") || action.equals("CALL_BUY") || action.equals("PUT_BUY");
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
                        bySymbol.computeIfAbsent(sym, k -> new double[2]);
                        bySymbol.get(sym)[0] += pnl; bySymbol.get(sym)[1]++;
                        String type = action.contains("CALL") ? "CALL" : action.contains("PUT") ? "PUT" : "STOCK";
                        byType.computeIfAbsent(type, k -> new double[2]);
                        byType.get(type)[0] += pnl; byType.get(type)[1]++;
                    }
                }
            }

            out.println("--- P&L BY SYMBOL ---");
            bySymbol.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue()[0]))
                    .forEach(e -> out.printf("  %-8s  P&L=$%10.2f  trades=%.0f%n",
                            e.getKey(), e.getValue()[0], e.getValue()[1]));
            out.println();
            out.println("--- P&L BY TRADE TYPE ---");
            byType.forEach((type, v) ->
                    out.printf("  %-6s  P&L=$%10.2f  trades=%.0f  avg=$%.2f%n",
                            type, v[0], v[1], v[1] > 0 ? v[0] / v[1] : 0));
            out.println();
        }

        out.println("--- FULL EVENT LOG ---");
        br.getEventLog().forEach(out::println);
    }
}
