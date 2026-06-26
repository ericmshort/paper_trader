package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.PriceHistory;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * 800-day backtest: intraday baseline + Put Credit Spread + Call Credit Spread combined.
 * Writes a full analysis report to ~/.tradingapp/backtest-report.txt including
 * capital competition between PCS and CCS.
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.PremiumSellerComparisonRunner
 */
public class PremiumSellerComparisonRunner {

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
        LocalDate startDate = endDate.minusDays(800);

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-report.txt");
        Files.createDirectories(reportPath.getParent());

        System.out.println("=== Premium Seller: Baseline + PCS + CCS ===");
        System.out.printf("Period : %s → %s%n", startDate, endDate);
        System.out.println("Report : " + reportPath);
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
        System.out.printf("Loaded %d/%d symbols. Running sim...%n%n", barsBySymbol.size(), total);

        VixCache vixCache = new VixCache();
        vixCache.load(startDate, endDate);

        double maxExposure  = cfg.getMaxPortfolioExposurePct() / 100.0;
        double lossLimitPct = cfg.getDailyLossLimitPct();

        // Intraday router
        BlackScholesEngine bs = new BlackScholesEngine();
        bs.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter intradayRouter = new OptionsSignalRouter(
                bs, optExec, new Account(), new PriceHistory(), msg -> {}, null);
        intradayRouter.setMaxPortfolioExposure(maxExposure);
        intradayRouter.setEnabledStrategies(cfg.getEnabledStrategies());
        if (cfg.getOptionsEntryStartTime() != null) intradayRouter.setEntryStartTime(cfg.getOptionsEntryStartTime());
        if (cfg.getOptionsForceCloseTime() != null) intradayRouter.setForceCloseTime(cfg.getOptionsForceCloseTime());
        intradayRouter.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
        intradayRouter.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
        intradayRouter.setStopLossFrac(cfg.getOptionsStopLossFrac());
        intradayRouter.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
        if (cfg.getOptionsEntryCutoff() != null) intradayRouter.setEntryCutoff(cfg.getOptionsEntryCutoff());
        intradayRouter.setOptionsAllowlist(optAllowlist);
        intradayRouter.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
        intradayRouter.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
        intradayRouter.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
        intradayRouter.setReversalMinSignals(5);
        intradayRouter.setReversalMinConsecutive(2);
        intradayRouter.setProfitTarget(cfg.getProfitTarget());
        intradayRouter.setEntryConfirmationTicks(Math.max(1, cfg.getEntryConfirmationTicks() / 12));
        intradayRouter.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());

        // Premium seller router: PCS + CCS only — collect its log for report analysis
        List<String> premLog = new ArrayList<>();
        BlackScholesEngine bsPrem = new BlackScholesEngine();
        bsPrem.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor premExec = new OptionsOrderExecutor(new Account(), null);
        PremiumSellerRouter premRouter = new PremiumSellerRouter(
                bsPrem, premExec, new Account(), new PriceHistory(), premLog::add);
        premRouter.setEnabledStrategies(Set.of(
                PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD,
                PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));

        OptionsEvaluator optEval = new CompositeEvaluator(intradayRouter, premRouter);

        IntradayBacktestEngine engine = new IntradayBacktestEngine(
                new IndicatorEngine(), new FeeCalculator()).setCollectEventLog(true);

        long t0 = System.currentTimeMillis();
        IntradayBacktestResult result = engine.run(watchlist, barsBySymbol, 100_000.0, optEval,
                msg -> {}, Set.of(),
                loop -> {
                    intradayRouter.setUptrendSupplier(loop::isUptrend);
                    loop.setStockTradingEnabled(false);
                    loop.setMaxConcurrentStockPositions(10);
                    loop.setAvoidOvernightHolds(false);
                    loop.setDailyLossLimitPct(lossLimitPct / 100.0);
                    loop.setAccurateOptionsValuation(true);
                    loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                    intradayRouter.setClosePositionsOnHalt(true);
                });
        long elapsed = System.currentTimeMillis() - t0;

        double wr = result.getTotalTrades() > 0 ? 100.0 * result.getWins() / result.getTotalTrades() : 0.0;
        System.out.printf("Return: %+.2f%%  MaxDD: %.2f%%  Trades: %d  WinRate: %.1f%%  (%ds)%n%n",
                result.getTotalReturnPct(), result.getMaxDrawdownPct(),
                result.getTotalTrades(), wr, elapsed / 1000);

        writeReport(reportPath, result, premLog, startDate, endDate);
        System.out.println("Report written to: " + reportPath);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    /**
     * Log line formats from PremiumSellerRouter:
     *   Opens  : "{SYM} PUT CREDIT SPREAD K=.../... exp=... x{n} credit=$..."
     *            "{SYM} CALL CREDIT SPREAD K=.../... exp=... x{n} credit=$..."
     *   Closes : "{SYM} PUT CREDIT SPREAD closed: {reason} | P&L ${pnl}"
     *            "{SYM} CALL CREDIT SPREAD closed: {reason} | P&L ${pnl}"
     */
    private static void writeReport(Path path, IntradayBacktestResult r,
                                    List<String> premLog,
                                    LocalDate startDate, LocalDate endDate) throws Exception {

        // ── Parse premLog ─────────────────────────────────────────────────────
        // opens: symbol → date → [PCS, CCS, ...] for competition analysis
        // closes: symbol → [pcs_pnl, ccs_pnl, pcs_closes, ccs_closes]
        Map<String, Map<String, List<String>>> opensBySymDate = new TreeMap<>(); // sym → date-str → types
        Map<String, double[]> symPnl = new TreeMap<>();                          // [pcs_pnl, ccs_pnl, pcs_n, ccs_n]

        int pcsOpens = 0, ccsOpens = 0;

        for (String line : premLog) {
            // e.g. "SPY PUT CREDIT SPREAD K=490/480 exp=2024-05-17 x3 credit=$312"
            boolean isPcs = line.contains("PUT CREDIT SPREAD") && !line.contains("closed:");
            boolean isCcs = line.contains("CALL CREDIT SPREAD") && !line.contains("closed:");
            boolean isPcsClose = line.contains("PUT CREDIT SPREAD closed:");
            boolean isCcsClose = line.contains("CALL CREDIT SPREAD closed:");

            if (isPcs || isCcs) {
                String sym = line.split(" ")[0];
                // Extract date from "exp=yyyy-MM-dd"
                String date = "";
                int ei = line.indexOf("exp=");
                if (ei >= 0) date = line.substring(ei + 4, Math.min(ei + 14, line.length()));
                opensBySymDate.computeIfAbsent(sym, k -> new TreeMap<>())
                        .computeIfAbsent(date, k -> new ArrayList<>())
                        .add(isPcs ? "PCS" : "CCS");
                if (isPcs) pcsOpens++; else ccsOpens++;
            }

            if (isPcsClose || isCcsClose) {
                String sym = line.split(" ")[0];
                // Extract P&L from "| P&L $NNN" or "| P&L $-NNN"
                double pnl = 0;
                int pi = line.indexOf("P&L $");
                if (pi >= 0) {
                    try { pnl = Double.parseDouble(line.substring(pi + 5).trim()); } catch (NumberFormatException ignored) {}
                }
                double[] a = symPnl.computeIfAbsent(sym, k -> new double[4]);
                if (isPcsClose) { a[0] += pnl; a[2]++; } else { a[1] += pnl; a[3]++; }
            }
        }

        // Competition stats
        int competeDays = 0, pcsAlone = 0, ccsAlone = 0;
        Map<String, Integer> competeBySymbol = new TreeMap<>();
        for (Map.Entry<String, Map<String, List<String>>> se : opensBySymDate.entrySet()) {
            for (Map.Entry<String, List<String>> de : se.getValue().entrySet()) {
                boolean hasPcs = de.getValue().contains("PCS");
                boolean hasCcs = de.getValue().contains("CCS");
                if (hasPcs && hasCcs) { competeDays++; competeBySymbol.merge(se.getKey(), 1, Integer::sum); }
                else if (hasPcs) pcsAlone++;
                else             ccsAlone++;
            }
        }
        int activeDays = competeDays + pcsAlone + ccsAlone;

        // ── Write file ────────────────────────────────────────────────────────
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {

            out.println("=== Premium Seller Backtest Report: Baseline + PCS + CCS ===");
            out.printf("Period   : %s → %s%n", startDate, endDate);
            out.printf("Generated: %s%n%n", ZonedDateTime.now(ET).format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

            // ── 1. Overall results ────────────────────────────────────────────
            out.println("─── Overall Results ──────────────────────────────────────────────────────────");
            out.printf("  Return       : %+.2f%%%n", r.getTotalReturnPct());
            out.printf("  Max Drawdown : %.2f%%%n",  r.getMaxDrawdownPct());
            out.printf("  Final Balance: $%.2f%n",   r.getFinalBalance());
            out.printf("  Total Trades : %d  (W:%d  L:%d  WR:%.1f%%)%n%n",
                    r.getTotalTrades(), r.getWins(), r.getLosses(),
                    r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0);

            // ── 2. Trade breakdown ────────────────────────────────────────────
            out.println("─── Trade Breakdown: Intraday vs Premium ─────────────────────────────────────");
            int premTotal = pcsOpens + ccsOpens;
            // Total intraday opens = total trades minus both legs of each premium open/close pair
            // Easier: infer from trade count
            out.printf("  PCS opens      : %d%n", pcsOpens);
            out.printf("  CCS opens      : %d%n", ccsOpens);
            out.printf("  Premium total  : %d opens%n%n", premTotal);

            // ── 3. Capital competition ────────────────────────────────────────
            out.println("─── Capital Competition: PCS vs CCS ─────────────────────────────────────────");
            out.println("  Days where both strategies opened on the same symbol (expiry as proxy for day).");
            out.println("  When both fire, the second entry uses a shrunken account balance.");
            out.println();
            out.printf("  %-8s  %s%n", "Symbol", "Both-open days");
            out.println("  " + "-".repeat(28));
            competeBySymbol.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> out.printf("  %-8s  %d%n", e.getKey(), e.getValue()));
            out.println();
            out.printf("  PCS only  : %4d entries  (%4.1f%% of active days)%n",
                    pcsAlone,  activeDays > 0 ? 100.0 * pcsAlone  / activeDays : 0);
            out.printf("  CCS only  : %4d entries  (%4.1f%% of active days)%n",
                    ccsAlone,  activeDays > 0 ? 100.0 * ccsAlone  / activeDays : 0);
            out.printf("  Both open : %4d entries  (%4.1f%% of active days)  ← capital shared%n",
                    competeDays, activeDays > 0 ? 100.0 * competeDays / activeDays : 0);
            out.println();

            // ── 4. Per-symbol P&L attribution ─────────────────────────────────
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

            // ── 5. Equity curve (bi-weekly) ───────────────────────────────────
            out.println("─── Equity Curve (bi-weekly snapshots) ──────────────────────────────────────");
            out.printf("  %-12s  %12s  %8s%n", "Date", "Balance", "Return%");
            out.println("  " + "-".repeat(38));
            var curve = r.getEquityCurve();
            if (!curve.isEmpty()) {
                int step = Math.max(1, curve.size() / 55);
                for (int i = 0; i < curve.size(); i += step) {
                    var dp = curve.get(i);
                    out.printf("  %-12s  %12.2f  %+7.2f%%%n",
                            dp.getDate(), dp.getPortfolioValue(),
                            (dp.getPortfolioValue() - 100_000.0) / 100_000.0 * 100.0);
                }
                var last = curve.get(curve.size() - 1);
                out.printf("  %-12s  %12.2f  %+7.2f%%  ← final%n",
                        last.getDate(), last.getPortfolioValue(),
                        (last.getPortfolioValue() - 100_000.0) / 100_000.0 * 100.0);
            }
            out.println();
        }
    }

    // ── Composite evaluator ───────────────────────────────────────────────────

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
