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
 * Compares two variants over the full cached history:
 *   A) With daily loss limit  — current app.properties setting (default 5%)
 *   B) No daily loss limit    — limit disabled (0%), everything else identical
 *
 * Both runs use intraday options + PCS + CCS, matching PremiumSellerComparisonRunner.
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.DailyLossLimitComparisonRunner
 *
 * Reports written to:
 *   ~/.tradingapp/backtest-report-with-limit.txt
 *   ~/.tradingapp/backtest-report-no-limit.txt
 */
public class DailyLossLimitComparisonRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    record VariantResult(IntradayBacktestResult result, List<String> premLog) {}

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

        // Use 5% as the "with limit" baseline regardless of what app.properties says,
        // so this comparison is always meaningful even if the live setting is already 0.
        double withLimitPct = 5.0;

        Path reportDir = Path.of(System.getProperty("user.home"), ".tradingapp");
        Files.createDirectories(reportDir);

        System.out.println("=== Backtest Comparison: Daily Loss Limit vs No Limit ===");
        System.out.printf("Period          : %s → %s%n", startDate, endDate);
        System.out.printf("Configured limit: %.1f%% (live app.properties = %.1f%%)%n",
                withLimitPct, cfg.getDailyLossLimitPct());
        System.out.println();

        List<String> watchlist = new ArrayList<>(
                cfg.getOptionsWatchlist().isEmpty() ? DayTraderWatchList.SYMBOLS : cfg.getOptionsWatchlist());
        Set<String> optAllowlist = cfg.getOptionsSymbolAllowlist().isEmpty()
                ? new java.util.HashSet<>(watchlist)
                : cfg.getOptionsSymbolAllowlist();

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

        IntradayBacktestEngine engine = new IntradayBacktestEngine(
                new IndicatorEngine(), new FeeCalculator()).setCollectEventLog(true);

        System.out.printf("Running A: With daily loss limit (%.1f%%)...%n", withLimitPct);
        long t0 = System.currentTimeMillis();
        VariantResult withLimit = runVariant(engine, watchlist, barsBySymbol, vixCache, cfg, optAllowlist,
                withLimitPct);
        System.out.printf("  done in %ds%n", (System.currentTimeMillis() - t0) / 1000);

        System.out.println("Running B: No daily loss limit (disabled)...");
        t0 = System.currentTimeMillis();
        VariantResult noLimit = runVariant(engine, watchlist, barsBySymbol, vixCache, cfg, optAllowlist,
                0.0);
        System.out.printf("  done in %ds%n%n", (System.currentTimeMillis() - t0) / 1000);

        // ── Comparison table ──────────────────────────────────────────────────
        System.out.println("=== RESULTS ===");
        System.out.printf("%-46s  %8s  %8s  %7s  %6s  %5s  %5s%n",
                "Variant", "Return", "MaxDD", "Trades", "WinRate", "PCS", "CCS");
        System.out.println("-".repeat(95));
        printRow(String.format("A) With loss limit (%.1f%%)", withLimitPct), withLimit);
        printRow("B) No loss limit (disabled)", noLimit);

        double diff = noLimit.result().getTotalReturnPct() - withLimit.result().getTotalReturnPct();
        System.out.printf("%nReturn difference (B − A): %+.2f pp%n", diff);
        System.out.printf("Max drawdown difference (B − A): %+.2f pp%n",
                noLimit.result().getMaxDrawdownPct() - withLimit.result().getMaxDrawdownPct());
        System.out.println();

        // ── Write detailed reports ────────────────────────────────────────────
        Path limitReport   = reportDir.resolve("backtest-report-with-limit.txt");
        Path noLimitReport = reportDir.resolve("backtest-report-no-limit.txt");
        writeReport(limitReport,   withLimit, startDate, endDate, cfg,
                String.format("A) With loss limit (%.1f%%)", withLimitPct), withLimitPct);
        writeReport(noLimitReport, noLimit,   startDate, endDate, cfg,
                "B) No loss limit (disabled)", 0.0);

        System.out.println("Reports written:");
        System.out.println("  With limit : " + limitReport);
        System.out.println("  No limit   : " + noLimitReport);
    }

    // ── Simulation ────────────────────────────────────────────────────────────

    private static VariantResult runVariant(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            VixCache vixCache,
            AppConfig cfg,
            Set<String> optAllowlist,
            double lossLimitPct) throws Exception {

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;

        BlackScholesEngine bs = new BlackScholesEngine();
        bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter intradayRouter = new OptionsSignalRouter(
                bs, optExec, new Account(), new PriceHistory(), msg -> {}, null);
        intradayRouter.setMaxPortfolioExposure(maxExposure);
        intradayRouter.setEnabledStrategies(cfg.getEnabledStrategies());
        if (cfg.getOptionsEntryStartTime() != null) intradayRouter.setEntryStartTime(cfg.getOptionsEntryStartTime());
        if (cfg.getOptionsForceCloseTime()  != null) intradayRouter.setForceCloseTime(cfg.getOptionsForceCloseTime());
        intradayRouter.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
        intradayRouter.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
        intradayRouter.setStopLossFrac(cfg.getOptionsStopLossFrac());
        intradayRouter.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
        intradayRouter.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());
        if (cfg.getOptionsEntryCutoff() != null) intradayRouter.setEntryCutoff(cfg.getOptionsEntryCutoff());
        intradayRouter.setOptionsAllowlist(optAllowlist);
        intradayRouter.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
        intradayRouter.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
        intradayRouter.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
        intradayRouter.setReversalMinSignals(5);
        intradayRouter.setReversalMinConsecutive(2);
        intradayRouter.setProfitTarget(cfg.getProfitTarget());
        intradayRouter.setEntryConfirmationTicks(Math.max(1, cfg.getEntryConfirmationTicks() / 12));

        List<String> premLog = new ArrayList<>();
        BlackScholesEngine bsPrem = new BlackScholesEngine();
        bsPrem.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor premExec = new OptionsOrderExecutor(new Account(), null);
        PremiumSellerRouter premRouter = new PremiumSellerRouter(
                bsPrem, premExec, new Account(), new PriceHistory(), premLog::add);
        premRouter.setEnabledStrategies(Set.of(
                PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD,
                PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));
        premRouter.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);

        OptionsEvaluator optEval = new CompositeEvaluator(intradayRouter, premRouter);

        IntradayBacktestResult result = engine.run(watchlist, barsBySymbol, 100_000.0, optEval,
                msg -> {}, Set.of(),
                loop -> {
                    intradayRouter.setUptrendSupplier(loop::isUptrend);
                    premRouter.setUptrendSupplier(loop::isUptrend);
                    loop.setStockTradingEnabled(false);
                    loop.setMaxConcurrentStockPositions(10);
                    loop.setAvoidOvernightHolds(false);
                    loop.setDailyLossLimitPct(lossLimitPct / 100.0);
                    loop.setAccurateOptionsValuation(true);
                    loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                    intradayRouter.setClosePositionsOnHalt(true);
                });

        return new VariantResult(result, premLog);
    }

    // ── Output ────────────────────────────────────────────────────────────────

    private static void printRow(String label, VariantResult vr) {
        IntradayBacktestResult r = vr.result();
        double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
        int[] counts = parsePremCounts(vr.premLog());
        System.out.printf("%-46s  %7.2f%%  %7.2f%%  %7d  %5.1f%%  %5d  %5d%n",
                label, r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                r.getTotalTrades(), wr, counts[0], counts[1]);
    }

    private static int[] parsePremCounts(List<String> premLog) {
        int pcs = 0, ccs = 0;
        for (String line : premLog) {
            if (line.contains("PUT CREDIT SPREAD")  && !line.contains("closed:")) pcs++;
            if (line.contains("CALL CREDIT SPREAD") && !line.contains("closed:")) ccs++;
        }
        return new int[]{pcs, ccs};
    }

    private static void writeReport(Path path, VariantResult vr,
                                    LocalDate startDate, LocalDate endDate,
                                    AppConfig cfg, String variantLabel,
                                    double lossLimitPct) throws Exception {

        IntradayBacktestResult r = vr.result();
        List<String> premLog = vr.premLog();

        Map<String, double[]> symPnl = new TreeMap<>();
        int pcsOpens = 0, ccsOpens = 0;
        for (String line : premLog) {
            boolean isPcs      = line.contains("PUT CREDIT SPREAD")  && !line.contains("closed:");
            boolean isCcs      = line.contains("CALL CREDIT SPREAD") && !line.contains("closed:");
            boolean isPcsClose = line.contains("PUT CREDIT SPREAD closed:");
            boolean isCcsClose = line.contains("CALL CREDIT SPREAD closed:");
            if (isPcs) pcsOpens++;
            if (isCcs) ccsOpens++;
            if (isPcsClose || isCcsClose) {
                String sym = line.split(" ")[0];
                double pnl = 0;
                int pi = line.indexOf("P&L $");
                if (pi >= 0) {
                    try { pnl = Double.parseDouble(line.substring(pi + 5).trim()); } catch (NumberFormatException ignored) {}
                }
                double[] a = symPnl.computeIfAbsent(sym, k -> new double[4]);
                if (isPcsClose) { a[0] += pnl; a[2]++; } else { a[1] += pnl; a[3]++; }
            }
        }

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {
            out.println("=== Backtest Report: " + variantLabel + " ===");
            out.printf("Period    : %s → %s%n", startDate, endDate);
            out.printf("Generated : %s%n%n", ZonedDateTime.now(ET).format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

            out.println("─── Settings ─────────────────────────────────────────────────────────────────");
            out.printf ("  Variant              : %s%n", variantLabel);
            out.printf ("  Daily loss limit     : %s%n",
                    lossLimitPct > 0 ? String.format("%.1f%%", lossLimitPct) : "DISABLED");
            out.println("  Intraday strategies:");
            for (String s : cfg.getEnabledStrategies())
                out.println("    • " + s);
            out.println("  Premium selling:");
            out.println("    • PUT_CREDIT_SPREAD (PCS)");
            out.println("    • CALL_CREDIT_SPREAD (CCS)");
            out.printf ("  Market regime filter : %s%n", cfg.isMarketRegimeFilterEnabled() ? "ON" : "OFF");
            out.printf ("  Max portfolio exposure: %.0f%%%n", cfg.getMaxPortfolioExposurePct());
            out.printf ("  Stop loss fraction   : %.0f%%%n", cfg.getOptionsStopLossFrac() * 100);
            out.printf ("  Profit target        : %.0f%%%n", cfg.getProfitTarget() * 100);
            out.printf ("  Position budget      : %.0f%% of capital per trade%n", cfg.getPositionBudgetFrac() * 100);
            if (cfg.getOptionsEntryCutoff() != null)
                out.printf("  Entry cutoff         : %s%n", cfg.getOptionsEntryCutoff());
            out.println();

            out.println("─── Overall Results ──────────────────────────────────────────────────────────");
            out.printf("  Return       : %+.2f%%%n", r.getTotalReturnPct());
            out.printf("  Max Drawdown : %.2f%%%n",  r.getMaxDrawdownPct());
            out.printf("  Final Balance: $%.2f%n",   r.getFinalBalance());
            out.printf("  Total Trades : %d  (W:%d  L:%d  WR:%.1f%%)%n",
                    r.getTotalTrades(), r.getWins(), r.getLosses(),
                    r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0);
            out.printf("  PCS opens    : %d%n", pcsOpens);
            out.printf("  CCS opens    : %d%n%n", ccsOpens);

            out.println("─── Daily P&L ────────────────────────────────────────────────────────────────");
            out.printf("  %-12s  %14s  %12s  %9s  %9s%n", "Date", "Balance", "Daily P&L", "Daily %", "Return%");
            out.println("  " + "-".repeat(64));
            double prev = 100_000.0;
            for (BacktestDataPoint dp : r.getEquityCurve()) {
                double bal      = dp.getPortfolioValue();
                double delta    = bal - prev;
                double dailyPct = prev > 0 ? delta / prev * 100.0 : 0.0;
                double ret      = (bal - 100_000.0) / 100_000.0 * 100.0;
                out.printf("  %-12s  %14.2f  %+12.2f  %+8.2f%%  %+8.2f%%%n",
                        dp.getDate(), bal, delta, dailyPct, ret);
                prev = bal;
            }
            out.println();

            out.println("─── Per-Symbol Premium P&L Attribution ──────────────────────────────────────");
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
            out.println();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LocalDate earliestCachedDate(String symbol) throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(
                System.getProperty("user.home"), ".tradingapp", "bar-cache", "1min", symbol);
        if (!java.nio.file.Files.isDirectory(dir)) return null;
        return java.nio.file.Files.list(dir)
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".json"))
                .map(n -> n.replace(".json", ""))
                .map(n -> { try { return LocalDate.parse(n); } catch (Exception e) { return null; } })
                .filter(d -> d != null)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    static class CompositeEvaluator implements OptionsEvaluator {
        private final OptionsEvaluator primary;
        private final OptionsEvaluator secondary;

        CompositeEvaluator(OptionsEvaluator primary, OptionsEvaluator secondary) {
            this.primary = primary; this.secondary = secondary;
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
                                        String signalStr, String featureCsv, List<SignalResult> rawSignals) {
            primary.evaluateWithSignals(symbol, price, buys, sells, signalStr, featureCsv, rawSignals);
            secondary.evaluateWithSignals(symbol, price, buys, sells, signalStr, featureCsv, rawSignals);
        }
    }
}
