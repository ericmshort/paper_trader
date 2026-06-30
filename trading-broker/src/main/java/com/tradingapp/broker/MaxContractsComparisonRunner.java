package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
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
import com.tradingapp.options.PremiumSellerRouter;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Sweeps MAX_CONTRACTS (1, 2, 3, 5, 7, 10, 15, 20) for the premium-only config
 * (PCS + CCS, no intraday options) over the full cached bar history.
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.MaxContractsComparisonRunner
 */
public class MaxContractsComparisonRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final int[]  SWEEP = {20, 25};

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);

        LocalDate startDate = earliestCachedDate("SPY");
        if (startDate == null) {
            System.err.println("ERROR: no cached data found for SPY under ~/.tradingapp/bar-cache/1min/SPY/");
            System.exit(1);
        }

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "max-contracts-comparison.txt");
        Files.createDirectories(reportPath.getParent());

        System.out.println("=== Max Contracts Sweep (Premium-only: PCS + CCS) ===");
        System.out.printf("Period : %s → %s%n", startDate, endDate);
        System.out.println("Report : " + reportPath);
        System.out.println();

        List<String> watchlist = new ArrayList<>(
                cfg.getOptionsWatchlist().isEmpty() ? DayTraderWatchList.SYMBOLS : cfg.getOptionsWatchlist());

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = watchlist.size(), idx = 0;
        System.out.println("Fetching bars for " + total + " symbols...");
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
        if (barsBySymbol.isEmpty()) {
            System.err.println("ERROR: no bar data fetched");
            System.exit(1);
        }
        System.out.printf("Loaded %d/%d symbols.%n%n", barsBySymbol.size(), total);

        VixCache vixCache = new VixCache();
        vixCache.load(startDate, endDate);

        double maxExposure  = cfg.getMaxPortfolioExposurePct() / 100.0;
        double lossLimitPct = cfg.getDailyLossLimitPct();

        // Results indexed by maxContracts value
        Map<Integer, IntradayBacktestResult> results = new LinkedHashMap<>();
        Map<Integer, List<String>> premLogs = new LinkedHashMap<>();

        for (int mc : SWEEP) {
            System.out.printf("Running maxContracts=%d...%n", mc);
            List<String> premLog = new ArrayList<>();
            PremiumSellerRouter router = buildPremiumRouter(vixCache, premLog, maxExposure, mc);
            IntradayBacktestEngine engine = new IntradayBacktestEngine(
                    new IndicatorEngine(), new FeeCalculator());

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult result = engine.run(watchlist, barsBySymbol, 100_000.0, router,
                    msg -> {}, Set.of(),
                    loop -> {
                        router.setUptrendSupplier(loop::isUptrend);
                        loop.setStockTradingEnabled(false);
                        loop.setMaxConcurrentStockPositions(10);
                        loop.setAvoidOvernightHolds(false);
                        loop.setDailyLossLimitPct(lossLimitPct / 100.0);
                        loop.setAccurateOptionsValuation(true);
                        loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                    });
            System.out.printf("  done in %ds  Return:%+.2f%%  MaxDD:%.2f%%  Trades:%d%n%n",
                    (System.currentTimeMillis() - t0) / 1000,
                    result.getTotalReturnPct(), result.getMaxDrawdownPct(), result.getTotalTrades());

            results.put(mc, result);
            premLogs.put(mc, premLog);
            System.gc();
        }

        printSummaryTable(results);
        writeReport(reportPath, results, premLogs, startDate, endDate, cfg);
        System.out.println("Report written to: " + reportPath);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    private static PremiumSellerRouter buildPremiumRouter(VixCache vixCache, List<String> premLog,
                                                           double maxPortfolioExposure,
                                                           int maxContracts) {
        BlackScholesEngine bs = new BlackScholesEngine();
        bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(new Account(), null);
        PremiumSellerRouter router = new PremiumSellerRouter(
                bs, exec, new Account(), new PriceHistory(), premLog::add);
        router.setEnabledStrategies(Set.of(
                PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD,
                PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));
        router.setMaxPortfolioExposure(maxPortfolioExposure);
        router.setMaxContracts(maxContracts);
        return router;
    }

    // ── Console summary ───────────────────────────────────────────────────────

    private static void printSummaryTable(Map<Integer, IntradayBacktestResult> results) {
        System.out.println();
        System.out.printf("%-14s  %10s  %8s  %8s  %8s%n",
                "MaxContracts", "Return%", "MaxDD%", "Trades", "WinRate%");
        System.out.println("-".repeat(58));
        for (Map.Entry<Integer, IntradayBacktestResult> e : results.entrySet()) {
            IntradayBacktestResult r = e.getValue();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("%-14d  %+9.2f%%  %7.2f%%  %8d  %7.1f%%%n",
                    e.getKey(), r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
        }
        System.out.println();
    }

    // ── Report ────────────────────────────────────────────────────────────────

    private static void writeReport(Path path,
                                    Map<Integer, IntradayBacktestResult> results,
                                    Map<Integer, List<String>> premLogs,
                                    LocalDate startDate, LocalDate endDate,
                                    AppConfig cfg) throws Exception {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {

            out.println("=== Max Contracts Sweep — Premium-only (PCS + CCS) ===");
            out.printf("Period    : %s → %s%n", startDate, endDate);
            out.printf("Generated : %s%n%n", ZonedDateTime.now(ET)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

            out.println("─── App Settings ──────────────────────────────────────────────────────────");
            out.println("  Strategies           : PUT_CREDIT_SPREAD, CALL_CREDIT_SPREAD");
            out.printf("  Market regime filter : %s%n", cfg.isMarketRegimeFilterEnabled() ? "ON" : "OFF");
            out.printf("  Max portfolio exposure: %.0f%%%n", cfg.getMaxPortfolioExposurePct());
            out.printf("  Daily loss limit     : %.1f%%%n", cfg.getDailyLossLimitPct());
            out.printf("  Profit target        : %.0f%%%n", cfg.getProfitTarget() * 100);
            out.println();

            out.println("─── Head-to-Head Summary ──────────────────────────────────────────────────");
            out.printf("  %-14s  %10s  %8s  %8s  %8s  %8s  %12s%n",
                    "MaxContracts", "Return%", "MaxDD%", "Trades", "WinRate%", "PCS_N", "CCS_N");
            out.println("  " + "-".repeat(80));
            for (Map.Entry<Integer, IntradayBacktestResult> e : results.entrySet()) {
                int mc = e.getKey();
                IntradayBacktestResult r = e.getValue();
                double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
                int[] counts = countPremiumOpens(premLogs.get(mc));
                out.printf("  %-14d  %+9.2f%%  %7.2f%%  %8d  %7.1f%%  %8d  %12d%n",
                        mc, r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(),
                        wr, counts[0], counts[1]);
            }
            out.println();

            // ── Daily P&L for each maxContracts value ─────────────────────────
            out.println("─── Daily Equity Curves ────────────────────────────────────────────────────");
            // Header
            out.print(String.format("  %-12s", "Date"));
            for (int mc : SWEEP) out.print(String.format("  %12s", "mc=" + mc));
            out.println();
            out.print("  " + "-".repeat(12));
            for (int i = 0; i < SWEEP.length; i++) out.print("  " + "-".repeat(12));
            out.println();

            // Collect all dates
            java.util.TreeSet<LocalDate> allDates = new java.util.TreeSet<>();
            for (IntradayBacktestResult r : results.values())
                for (BacktestDataPoint dp : r.getEquityCurve())
                    allDates.add(LocalDate.parse(dp.getDate().toString()));

            // Build date → balance maps per scenario
            Map<Integer, Map<LocalDate, Double>> balanceMaps = new LinkedHashMap<>();
            for (Map.Entry<Integer, IntradayBacktestResult> e : results.entrySet()) {
                Map<LocalDate, Double> m = new TreeMap<>();
                for (BacktestDataPoint dp : e.getValue().getEquityCurve())
                    m.put(LocalDate.parse(dp.getDate().toString()), dp.getPortfolioValue());
                balanceMaps.put(e.getKey(), m);
            }

            for (LocalDate date : allDates) {
                out.print(String.format("  %-12s", date));
                for (int mc : SWEEP) {
                    Double bal = balanceMaps.get(mc).get(date);
                    if (bal != null) out.print(String.format("  %12.2f", bal));
                    else             out.print(String.format("  %12s", "—"));
                }
                out.println();
            }
            out.println();

            // ── Per-scenario premium attribution ──────────────────────────────
            for (int mc : SWEEP) {
                writePremiumAttribution(out, "maxContracts=" + mc, premLogs.get(mc));
                out.println();
            }
        }
    }

    private static void writePremiumAttribution(PrintWriter out, String label, List<String> premLog) {
        Map<String, double[]> symPnl = new TreeMap<>();
        for (String line : premLog) {
            boolean isPcsClose = line.contains("PUT CREDIT SPREAD closed:");
            boolean isCcsClose = line.contains("CALL CREDIT SPREAD closed:");
            if (!isPcsClose && !isCcsClose) continue;
            String sym = line.split(" ")[0];
            double pnl = 0;
            int pi = line.indexOf("P&L $");
            if (pi >= 0) {
                try { pnl = Double.parseDouble(line.substring(pi + 5).trim()); }
                catch (NumberFormatException ignored) {}
            }
            double[] a = symPnl.computeIfAbsent(sym, k -> new double[4]);
            if (isPcsClose) { a[0] += pnl; a[2]++; } else { a[1] += pnl; a[3]++; }
        }

        out.printf("─── Per-Symbol Attribution (%s) ────────────────────────────────────────────%n", label);
        out.printf("  %-8s  %6s  %6s  %8s  %8s  %9s%n",
                "Symbol", "PCS_N", "CCS_N", "PCS_P&L", "CCS_P&L", "Total_P&L");
        out.println("  " + "-".repeat(58));
        double totPcsPnl = 0, totCcsPnl = 0;
        int totPcsN = 0, totCcsN = 0;
        for (Map.Entry<String, double[]> e : symPnl.entrySet()) {
            double[] v = e.getValue();
            out.printf("  %-8s  %6.0f  %6.0f  %+8.0f  %+8.0f  %+9.0f%n",
                    e.getKey(), v[2], v[3], v[0], v[1], v[0] + v[1]);
            totPcsPnl += v[0]; totCcsPnl += v[1]; totPcsN += (int) v[2]; totCcsN += (int) v[3];
        }
        out.println("  " + "-".repeat(58));
        out.printf("  %-8s  %6d  %6d  %+8.0f  %+8.0f  %+9.0f%n",
                "TOTAL", totPcsN, totCcsN, totPcsPnl, totCcsPnl, totPcsPnl + totCcsPnl);
    }

    private static int[] countPremiumOpens(List<String> premLog) {
        int pcs = 0, ccs = 0;
        for (String line : premLog) {
            if (line.contains("PUT CREDIT SPREAD")  && !line.contains("closed:")) pcs++;
            if (line.contains("CALL CREDIT SPREAD") && !line.contains("closed:")) ccs++;
        }
        return new int[]{pcs, ccs};
    }

    // ── Cache scan ────────────────────────────────────────────────────────────

    private static LocalDate earliestCachedDate(String symbol) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".tradingapp", "bar-cache", "1min", symbol);
        if (!Files.isDirectory(dir)) return null;
        return Files.list(dir)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".json"))
                .map(n -> n.replace(".json", ""))
                .map(n -> { try { return LocalDate.parse(n); } catch (Exception e) { return null; } })
                .filter(d -> d != null)
                .min(LocalDate::compareTo)
                .orElse(null);
    }
}
