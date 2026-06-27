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
import com.tradingapp.engine.OptionsEvaluator;
import com.tradingapp.engine.SignalResult;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;
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
 * Compares two configs over the full cached bar history:
 *   A — Current: intraday strategies (from app.properties) + PCS + CCS
 *   B — Premium-only: PCS + CCS with no intraday strategies competing for capital
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.PremiumCapitalComparisonRunner
 */
public class PremiumCapitalComparisonRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);

        // Use the full cached history rather than a fixed window
        LocalDate startDate = earliestCachedDate("SPY");
        if (startDate == null) {
            System.err.println("ERROR: no cached data found for SPY under ~/.tradingapp/bar-cache/1min/SPY/");
            System.exit(1);
        }

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "premium-capital-comparison.txt");
        Files.createDirectories(reportPath.getParent());

        System.out.println("=== Premium Capital Comparison ===");
        System.out.printf("Period : %s → %s%n", startDate, endDate);
        System.out.println("Report : " + reportPath);
        System.out.println();

        List<String> watchlist = new ArrayList<>(
                cfg.getOptionsWatchlist().isEmpty() ? DayTraderWatchList.SYMBOLS : cfg.getOptionsWatchlist());
        Set<String> optAllowlist = cfg.getOptionsSymbolAllowlist().isEmpty()
                ? new java.util.HashSet<>(watchlist)
                : cfg.getOptionsSymbolAllowlist();

        // Load bars once — shared across both configs
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

        IntradayBacktestEngine engine = new IntradayBacktestEngine(
                new IndicatorEngine(), new FeeCalculator()).setCollectEventLog(true);

        // ── Config A: Intraday + PCS + CCS ───────────────────────────────────
        System.out.println("Running Config A: Intraday + Premium Seller (PCS + CCS)...");
        List<String> premLogA = new ArrayList<>();
        OptionsEvaluator evalA = buildCompositeEvaluator(cfg, vixCache, optAllowlist, maxExposure, premLogA);

        long t0 = System.currentTimeMillis();
        IntradayBacktestResult resultA = engine.run(watchlist, barsBySymbol, 100_000.0, evalA,
                msg -> {}, Set.of(),
                loop -> {
                    loop.setStockTradingEnabled(false);
                    loop.setMaxConcurrentStockPositions(10);
                    loop.setAvoidOvernightHolds(false);
                    loop.setDailyLossLimitPct(lossLimitPct / 100.0);
                    loop.setAccurateOptionsValuation(true);
                    loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                });
        System.out.printf("  Config A done in %ds%n%n", (System.currentTimeMillis() - t0) / 1000);

        // ── Config B: PCS + CCS only ──────────────────────────────────────────
        System.out.println("Running Config B: Premium Seller only (PCS + CCS)...");
        List<String> premLogB = new ArrayList<>();
        OptionsEvaluator evalB = buildPremiumOnlyEvaluator(cfg, vixCache, premLogB);

        t0 = System.currentTimeMillis();
        IntradayBacktestResult resultB = engine.run(watchlist, barsBySymbol, 100_000.0, evalB,
                msg -> {}, Set.of(),
                loop -> {
                    loop.setStockTradingEnabled(false);
                    loop.setMaxConcurrentStockPositions(10);
                    loop.setAvoidOvernightHolds(false);
                    loop.setDailyLossLimitPct(lossLimitPct / 100.0);
                    loop.setAccurateOptionsValuation(true);
                    loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                });
        System.out.printf("  Config B done in %ds%n%n", (System.currentTimeMillis() - t0) / 1000);

        // ── Print summary ─────────────────────────────────────────────────────
        printSummary(resultA, "A — Intraday + PCS + CCS (current)");
        printSummary(resultB, "B — Premium-only (PCS + CCS)");
        System.out.println();

        writeReport(reportPath, resultA, premLogA, resultB, premLogB, startDate, endDate, cfg);
        System.out.println("Report written to: " + reportPath);
    }

    // ── Evaluator builders ────────────────────────────────────────────────────

    private static OptionsEvaluator buildCompositeEvaluator(AppConfig cfg, VixCache vixCache,
                                                             Set<String> optAllowlist,
                                                             double maxExposure,
                                                             List<String> premLog) {
        BlackScholesEngine bs = new BlackScholesEngine();
        bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter intraday = new OptionsSignalRouter(
                bs, optExec, new Account(), new PriceHistory(), msg -> {}, null);
        intraday.setMaxPortfolioExposure(maxExposure);
        intraday.setEnabledStrategies(cfg.getEnabledStrategies());
        if (cfg.getOptionsEntryStartTime() != null) intraday.setEntryStartTime(cfg.getOptionsEntryStartTime());
        if (cfg.getOptionsForceCloseTime() != null) intraday.setForceCloseTime(cfg.getOptionsForceCloseTime());
        intraday.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
        intraday.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
        intraday.setStopLossFrac(cfg.getOptionsStopLossFrac());
        intraday.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
        if (cfg.getOptionsEntryCutoff() != null) intraday.setEntryCutoff(cfg.getOptionsEntryCutoff());
        intraday.setOptionsAllowlist(optAllowlist);
        intraday.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
        intraday.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
        intraday.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
        intraday.setReversalMinSignals(5);
        intraday.setReversalMinConsecutive(2);
        intraday.setProfitTarget(cfg.getProfitTarget());
        intraday.setEntryConfirmationTicks(Math.max(1, cfg.getEntryConfirmationTicks() / 12));
        intraday.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());

        BlackScholesEngine bsPrem = new BlackScholesEngine();
        bsPrem.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor premExec = new OptionsOrderExecutor(new Account(), null);
        PremiumSellerRouter premium = new PremiumSellerRouter(
                bsPrem, premExec, new Account(), new PriceHistory(), premLog::add);
        premium.setEnabledStrategies(Set.of(
                PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD,
                PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));

        return new CompositeEvaluator(intraday, premium);
    }

    private static OptionsEvaluator buildPremiumOnlyEvaluator(AppConfig cfg, VixCache vixCache,
                                                               List<String> premLog) {
        BlackScholesEngine bsPrem = new BlackScholesEngine();
        bsPrem.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor premExec = new OptionsOrderExecutor(new Account(), null);
        PremiumSellerRouter premium = new PremiumSellerRouter(
                bsPrem, premExec, new Account(), new PriceHistory(), premLog::add);
        premium.setEnabledStrategies(Set.of(
                PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD,
                PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));
        return premium;
    }

    // ── Console summary ───────────────────────────────────────────────────────

    private static void printSummary(IntradayBacktestResult r, String label) {
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        System.out.printf("%-42s  Return:%+.2f%%  MaxDD:%.2f%%  Trades:%d  WR:%.1f%%%n",
                label, r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getTotalTrades(), wr);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    private static void writeReport(Path path,
                                    IntradayBacktestResult rA, List<String> premLogA,
                                    IntradayBacktestResult rB, List<String> premLogB,
                                    LocalDate startDate, LocalDate endDate,
                                    AppConfig cfg) throws Exception {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {

            out.println("=== Premium Capital Comparison Report ===");
            out.printf("Period    : %s → %s%n", startDate, endDate);
            out.printf("Generated : %s%n%n", ZonedDateTime.now(ET)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

            out.println("─── App Settings ─────────────────────────────────────────────────────────────");
            out.println("  Intraday strategies (Config A only):");
            for (String s : cfg.getEnabledStrategies()) out.println("    • " + s);
            out.println("  Premium selling strategies (both configs):");
            out.println("    • PUT_CREDIT_SPREAD (PCS)");
            out.println("    • CALL_CREDIT_SPREAD (CCS)");
            out.printf("  Market regime filter : %s%n", cfg.isMarketRegimeFilterEnabled() ? "ON" : "OFF");
            out.printf("  Max portfolio exposure: %.0f%%%n", cfg.getMaxPortfolioExposurePct());
            out.printf("  Daily loss limit     : %.1f%%%n", cfg.getDailyLossLimitPct());
            out.printf("  Stop loss fraction   : %.0f%%%n", cfg.getOptionsStopLossFrac() * 100);
            out.printf("  Profit target        : %.0f%%%n", cfg.getProfitTarget() * 100);
            out.printf("  Position budget      : %.0f%% of capital per trade%n", cfg.getPositionBudgetFrac() * 100);
            out.println();

            out.println("─── Head-to-Head Summary ─────────────────────────────────────────────────────");
            printResultBlock(out, "Config A — Intraday + PCS + CCS (current)", rA, premLogA);
            out.println();
            printResultBlock(out, "Config B — Premium-only (PCS + CCS)", rB, premLogB);
            out.println();

            // ── Daily P&L side by side ────────────────────────────────────────
            out.println("─── Daily P&L Comparison ─────────────────────────────────────────────────────");
            out.printf("  %-12s  %12s  %9s  %9s  │  %12s  %9s  %9s%n",
                    "Date",
                    "A Balance", "A Daily%", "A Return%",
                    "B Balance", "B Daily%", "B Return%");
            out.println("  " + "-".repeat(80));

            List<BacktestDataPoint> curveA = rA.getEquityCurve();
            List<BacktestDataPoint> curveB = rB.getEquityCurve();
            Map<LocalDate, BacktestDataPoint> mapB = new TreeMap<>();
            for (BacktestDataPoint dp : curveB) mapB.put(LocalDate.parse(dp.getDate().toString()), dp);

            double prevA = 100_000.0, prevB = 100_000.0;
            for (BacktestDataPoint dpA : curveA) {
                LocalDate date = LocalDate.parse(dpA.getDate().toString());
                double balA = dpA.getPortfolioValue();
                double dailyPctA = prevA > 0 ? (balA - prevA) / prevA * 100.0 : 0.0;
                double retA = (balA - 100_000.0) / 100_000.0 * 100.0;

                BacktestDataPoint dpB = mapB.get(date);
                if (dpB != null) {
                    double balB = dpB.getPortfolioValue();
                    double dailyPctB = prevB > 0 ? (balB - prevB) / prevB * 100.0 : 0.0;
                    double retB = (balB - 100_000.0) / 100_000.0 * 100.0;
                    out.printf("  %-12s  %12.2f  %+8.2f%%  %+8.2f%%  │  %12.2f  %+8.2f%%  %+8.2f%%%n",
                            date, balA, dailyPctA, retA, balB, dailyPctB, retB);
                    prevB = balB;
                } else {
                    out.printf("  %-12s  %12.2f  %+8.2f%%  %+8.2f%%  │  %12s  %9s  %9s%n",
                            date, balA, dailyPctA, retA, "—", "—", "—");
                }
                prevA = balA;
            }
            out.println();

            // ── Per-symbol premium attribution for each config ─────────────────
            writePremiumAttribution(out, "Config A", premLogA);
            out.println();
            writePremiumAttribution(out, "Config B", premLogB);
        }
    }

    private static void printResultBlock(PrintWriter out, String label,
                                         IntradayBacktestResult r, List<String> premLog) {
        int[] counts = countPremiumOpens(premLog);
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        out.println("  " + label);
        out.printf("    Return       : %+.2f%%%n", r.getTotalReturnPct());
        out.printf("    Max Drawdown : %.2f%%%n",  r.getMaxDrawdownPct());
        out.printf("    Final Balance: $%.2f%n",   r.getFinalBalance());
        out.printf("    Total Trades : %d  (W:%d  L:%d  WR:%.1f%%)%n",
                r.getTotalTrades(), r.getWins(), r.getLosses(), wr);
        out.printf("    PCS opens    : %d%n", counts[0]);
        out.printf("    CCS opens    : %d%n", counts[1]);
    }

    private static void writePremiumAttribution(PrintWriter out, String configLabel,
                                                 List<String> premLog) {
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

        out.printf("─── Per-Symbol Premium Attribution (%s) ───────────────────────────────────%n", configLabel);
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

    // ── Composite evaluator ───────────────────────────────────────────────────

    static class CompositeEvaluator implements OptionsEvaluator {
        private final OptionsEvaluator primary;
        private final OptionsEvaluator secondary;

        CompositeEvaluator(OptionsEvaluator primary, OptionsEvaluator secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void onBacktestInit(TransactionLog sharedLog, Account sharedAccount,
                                   PriceHistory sharedHistory, Supplier<ZonedDateTime> clock) {
            primary.onBacktestInit(sharedLog, sharedAccount, sharedHistory, clock);
            secondary.onBacktestInit(sharedLog, sharedAccount, sharedHistory, clock);
        }

        @Override public void resetForDay(LocalDate date) {
            primary.resetForDay(date); secondary.resetForDay(date);
        }

        @Override public void markPositionsToMarket(String symbol, double price) {
            primary.markPositionsToMarket(symbol, price);
            secondary.markPositionsToMarket(symbol, price);
        }

        @Override
        public void evaluateWithSignals(String symbol, double price, int buys, int sells,
                                        String signalStr, String featureCsv,
                                        List<SignalResult> rawSignals) {
            primary.evaluateWithSignals(symbol, price, buys, sells, signalStr, featureCsv, rawSignals);
            secondary.evaluateWithSignals(symbol, price, buys, sells, signalStr, featureCsv, rawSignals);
        }
    }
}
