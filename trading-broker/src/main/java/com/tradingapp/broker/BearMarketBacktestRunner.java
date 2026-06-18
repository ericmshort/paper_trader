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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bear-market strategy backtest. Tests strategies that profit during SPY downtrends.
 *
 *   Section A — Inverse ETF equity strategy (SQQQ, SDS, SPXU):
 *     Buys inverse ETFs only when SPY is below its 5-day MA. No options.
 *     Runs each ETF solo, then all three combined.
 *
 *   Section B — Relaxed put threshold in confirmed downtrend:
 *     Full 30-symbol watchlist with HIGH_DELTA_SCALP + MOMENTUM_NEAR_TERM + LONG_PUT.
 *     Tests downtrendMinSignals = 4 (current live), 3, and 2.
 *     uptrendSupplier is correctly wired to TradingLoop so downtrend detection is live.
 *
 *   Section C — Bear call spreads: NOT YET IMPLEMENTED.
 *     Requires wiring openCreditSpread() into OptionsSignalRouter.
 *
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.BearMarketBacktestRunner
 */
public class BearMarketBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ET);

    record RunResult(String label, IntradayBacktestResult result) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "bear-market-backtest-report.txt");
        Files.createDirectories(reportPath.getParent());
        System.out.println("=== Bear Market Strategy Backtest ===");
        System.out.println("Report will be written to: " + reportPath);

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            endDate = endDate.minusDays(1);
        }
        LocalDate startDate = endDate.minusDays(140);

        // Full 30-symbol watchlist already includes SPY; add inverse ETFs on top
        List<String> inverseEtfs = List.of("SQQQ", "SDS", "SPXU");
        List<String> allSymbols = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        for (String etf : inverseEtfs) {
            if (!allSymbols.contains(etf)) allSymbols.add(etf);
        }

        System.out.println("Fetching bars " + startDate + " -> " + endDate + " for " + allSymbols.size() + " symbols...");
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = allSymbols.size(), fetched = 0;
        for (String sym : allSymbols) {
            fetched++;
            final int cur = fetched;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }
        if (barsBySymbol.isEmpty()) { System.err.println("ERROR: no bar data fetched"); System.exit(1); }
        System.out.println("Fetched data for " + barsBySymbol.size() + " symbols.");

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        List<RunResult> results = new ArrayList<>();

        // ── Section A: Inverse ETF equity runs (no options) ───────────────────
        System.out.println("\n=== SECTION A: Inverse ETF Equity Strategy ===");

        for (String etf : inverseEtfs) {
            if (!barsBySymbol.containsKey(etf)) { System.out.println("SKIP " + etf + ": no data"); continue; }
            List<String> wl = List.of("SPY", etf);
            String label = "A: " + etf + " only";
            System.out.println("\n--- " + label + " ---");
            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(wl, barsBySymbol, 100_000.0, null, msg -> {}, Set.of(etf));
            printSummary(r, t0);
            results.add(new RunResult(label, r));
        }

        List<String> availableEtfs = new ArrayList<>();
        Set<String> allInvSet = new HashSet<>();
        for (String etf : inverseEtfs) {
            if (barsBySymbol.containsKey(etf)) { availableEtfs.add(etf); allInvSet.add(etf); }
        }
        if (!availableEtfs.isEmpty()) {
            List<String> wl = new ArrayList<>(List.of("SPY"));
            wl.addAll(availableEtfs);
            String label = "A: " + String.join("+", availableEtfs) + " combined";
            System.out.println("\n--- " + label + " ---");
            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(wl, barsBySymbol, 100_000.0, null, msg -> {}, allInvSet);
            printSummary(r, t0);
            results.add(new RunResult(label, r));
        }

        // ── Section B: Relaxed put threshold in confirmed downtrend ───────────
        System.out.println("\n=== SECTION B: Relaxed Put Threshold in Downtrend ===");
        System.out.println("(uptrendSupplier wired to TradingLoop — downtrend detection is live)");

        Set<String> BASE_OPTS      = Set.copyOf(DayTraderWatchList.SYMBOLS);
        Set<String> CALLS_DISABLED = Set.of("MSFT");
        List<String> fullWatchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);

        for (int putMin : new int[]{4, 3, 2}) {
            OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
            OptionsSignalRouter router = new OptionsSignalRouter(
                    new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
            router.setMaxPortfolioExposure(maxExposure);
            router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_PUT"));
            router.setOptionsAllowlist(BASE_OPTS);
            router.setCallsDisabledSymbols(CALLS_DISABLED);
            router.setPutsDisabledSymbols(Set.of("NVDA"));
            router.setDowntrendPutMinSignals(putMin);

            String label = String.format("B: LONG_PUT+scalp, downtrendMin=%d", putMin);
            System.out.println("\n--- " + label + " ---");
            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(fullWatchlist, barsBySymbol, 100_000.0, router, msg -> {},
                    Set.of(), loop -> router.setUptrendSupplier(loop::isUptrend));
            printSummary(r, t0);
            results.add(new RunResult(label, r));
        }

        // ── Comparison table ──────────────────────────────────────────────────
        System.out.println("\n=== COMPARISON ===");
        System.out.printf("%-42s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(82));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("%-42s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
        }

        RunResult best = results.stream()
                .max(Comparator.comparingDouble(rr -> rr.result().getTotalReturnPct()))
                .orElseThrow();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            writeReport(out, results, best, startDate, endDate);
        }
        System.out.println("\nReport written for best run (" + best.label() + "): " + reportPath);
    }

    private static void printSummary(IntradayBacktestResult r, long t0) {
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d  WR:%.1f%%)%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                r.getTotalTrades(), r.getWins(), r.getLosses(), wr);
    }

    private static void writeReport(PrintWriter out, List<RunResult> results, RunResult best,
                                    LocalDate startDate, LocalDate endDate) {
        out.println("=== BEAR MARKET STRATEGY BACKTEST REPORT ===");
        out.println("Period   : " + startDate + " to " + endDate);
        out.println("Generated: " + ZonedDateTime.now(ET)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        out.println();
        out.println("Section A — Inverse ETF equity strategy (SQQQ, SDS, SPXU)");
        out.println("  Buys inverse ETFs only when SPY is below its 5-day MA. No options.");
        out.println();
        out.println("Section B — Relaxed put threshold in confirmed downtrend");
        out.println("  Full 30-symbol watchlist. HIGH_DELTA_SCALP + MOMENTUM_NEAR_TERM + LONG_PUT.");
        out.println("  downtrendMin=4 is the current live threshold; 3 and 2 are progressively looser.");
        out.println("  uptrendSupplier wired to TradingLoop — downtrend detection reflects live behavior.");
        out.println();
        out.println("Section C — Bear call spreads: NOT YET IMPLEMENTED");
        out.println("  Requires wiring openCreditSpread() into OptionsSignalRouter.");
        out.println();

        out.println("=== COMPARISON TABLE ===");
        out.printf("%-42s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        out.println("-".repeat(82));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            out.printf("%-42s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
        }
        out.println();

        out.println("=== BEST RESULT: " + best.label() + " ===");
        out.println("--- SUMMARY ---");
        IntradayBacktestResult br = best.result();
        out.printf("Final Balance  : $%,.2f%n", br.getFinalBalance());
        out.printf("Total Return   : %.2f%%%n", br.getTotalReturnPct());
        out.printf("Max Drawdown   : %.2f%%%n", br.getMaxDrawdownPct());
        out.printf("Total Trades   : %d (wins=%d losses=%d)%n", br.getTotalTrades(), br.getWins(), br.getLosses());
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

        List<TransactionRecord> trades = br.getTrades();
        if (!trades.isEmpty()) {
            out.println("--- ROUND-TRIP TRADE LOG ---");
            Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
            List<TransactionRecord> sorted = new ArrayList<>(trades);
            sorted.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));
            for (TransactionRecord r : sorted) {
                String sym = r.getSymbol();
                String action = r.getAction().name();
                String ts = DT_FMT.format(Instant.ofEpochMilli(r.getTimestamp()));
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
                        out.printf("  %s  %-8s %-5s  entry=$%.3f exit=$%.3f qty=%d  P&L=$%.2f  %s%n",
                                ts, sym, action.replace("_SELL", ""),
                                buy.getPricePerUnit(), r.getPricePerUnit(), r.getQuantity(), pnl,
                                r.getReason() != null ? r.getReason() : "");
                    }
                }
            }
            out.println();
        }

        out.println("--- FULL EVENT LOG ---");
        br.getEventLog().forEach(out::println);
    }
}
