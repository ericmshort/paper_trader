package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.account.TransactionRecord.TransactionAction;
import com.tradingapp.data.MasterUniverse;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Ranks all ~98 MasterUniverse symbols by P&L in a single combined backtest
 * (intraday strategies + PCS + CCS), then recommends the top 30.
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.SymbolRankingRunner
 */
public class SymbolRankingRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Symbols currently in the live watchlist
    private static final Set<String> CURRENT_WATCHLIST = Set.of(
            "SPY", "NOC", "NVDA", "MSFT", "COST", "VRTX", "AMGN", "CRWD", "GS", "PLTR",
            "LRCX", "DE", "ORCL", "LLY", "BLK", "NOW", "MA", "REGN", "META", "AMAT",
            "KLAC", "CAT", "AVGO", "UNH", "LMT", "JPM", "MU", "HD", "MCD");

    // Symbols explicitly excluded from options trading
    private static final Set<String> OPTIONS_EXCLUDED = Set.of("QQQ", "TSLA", "NFLX");

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not configured.");
            System.exit(1);
        }

        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY)
            endDate = endDate.minusDays(1);
        LocalDate startDate = endDate.minusDays(800);

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "symbol-ranking.txt");
        Files.createDirectories(reportPath.getParent());

        List<String> allSymbols = new ArrayList<>(MasterUniverse.SYMBOLS);

        System.out.println("=== Symbol Ranking Runner ===");
        System.out.printf("Period  : %s → %s%n", startDate, endDate);
        System.out.printf("Symbols : %d from MasterUniverse%n", allSymbols.size());
        System.out.println("Report  : " + reportPath);
        System.out.println();

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = allSymbols.size(), idx = 0;
        System.out.println("Fetching bars...");
        for (String sym : allSymbols) {
            final int cur = ++idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }
        System.out.printf("Loaded %d/%d symbols. Running sim...%n%n", barsBySymbol.size(), total);

        VixCache vixCache = new VixCache();
        vixCache.load(startDate, endDate);

        double maxExposure  = cfg.getMaxPortfolioExposurePct() / 100.0;
        double lossLimitPct = cfg.getDailyLossLimitPct();

        // All symbols eligible for options (exclude explicitly banned)
        Set<String> optAllowlist = new HashSet<>(barsBySymbol.keySet());
        optAllowlist.removeAll(OPTIONS_EXCLUDED);

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

        // Premium seller router: PCS + CCS
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
                new IndicatorEngine(), new FeeCalculator()).setCollectEventLog(false);

        long t0 = System.currentTimeMillis();
        int[] dayCounter = {0};
        IntradayBacktestResult result = engine.run(allSymbols, barsBySymbol, 100_000.0, optEval,
                msg -> {
                    if (msg.startsWith("Day ") && ++dayCounter[0] % 20 == 0)
                        System.out.println(msg);
                }, Set.of(),
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

        writeReport(reportPath, result, premLog, startDate, endDate, cfg);
        System.out.println("Report written to: " + reportPath);
    }

    // ── Report ────────────────────────────────────────────────────────────────

    private static void writeReport(Path path, IntradayBacktestResult result,
                                    List<String> premLog,
                                    LocalDate startDate, LocalDate endDate,
                                    AppConfig cfg) throws Exception {

        // ── Per-symbol P&L from transaction log (intraday + premium combined) ──
        // Net cash flow: SELL = +price*100*qty - fee, BUY = -price*100*qty - fee
        Map<String, Double> txnPnl   = new TreeMap<>();
        Map<String, Integer> txnCount = new TreeMap<>();
        for (TransactionRecord t : result.getTrades()) {
            String sym = t.getSymbol();
            double flow = 0;
            switch (t.getAction()) {
                case CALL_SELL, PUT_SELL ->
                        flow = t.getPricePerUnit() * t.getQuantity() * 100.0 - t.getFeeCharged();
                case CALL_BUY, PUT_BUY ->
                        flow = -(t.getPricePerUnit() * t.getQuantity() * 100.0 + t.getFeeCharged());
                default -> {}
            }
            if (flow != 0) {
                txnPnl.merge(sym, flow, Double::sum);
                txnCount.merge(sym, 1, Integer::sum);
            }
        }

        // ── Per-symbol premium P&L from premLog ──
        // prem[sym] = [pcs_pnl, ccs_pnl, pcs_closes, ccs_closes]
        Map<String, double[]> premPnl = new TreeMap<>();
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
                    try { pnl = Double.parseDouble(line.substring(pi + 5).trim()); }
                    catch (NumberFormatException ignored) {}
                }
                double[] a = premPnl.computeIfAbsent(sym, k -> new double[4]);
                if (isPcsClose) { a[0] += pnl; a[2]++; } else { a[1] += pnl; a[3]++; }
            }
        }

        // ── Build combined ranking ──
        Set<String> allSyms = new HashSet<>(txnPnl.keySet());
        allSyms.addAll(premPnl.keySet());

        record SymRank(String symbol, double totalPnl, double intradayPnl,
                       double pcsPnl, double ccsPnl, int txns, boolean inCurrent) {}

        List<SymRank> ranked = new ArrayList<>();
        for (String sym : allSyms) {
            double total   = txnPnl.getOrDefault(sym, 0.0);
            double[] prem  = premPnl.getOrDefault(sym, new double[4]);
            double premSum = prem[0] + prem[1];
            double intraday = total - premSum;
            ranked.add(new SymRank(sym, total, intraday, prem[0], prem[1],
                    txnCount.getOrDefault(sym, 0), CURRENT_WATCHLIST.contains(sym)));
        }
        ranked.sort(Comparator.comparingDouble(SymRank::totalPnl).reversed());

        // ── Write report ──
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {

            out.println("=== Symbol Ranking Report ===");
            out.printf("Period    : %s → %s%n", startDate, endDate);
            out.printf("Generated : %s%n%n", ZonedDateTime.now(ET).format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

            out.println("─── App Settings ─────────────────────────────────────────────────────────────");
            out.println("  Intraday strategies:");
            for (String s : cfg.getEnabledStrategies()) out.println("    • " + s);
            out.println("  Premium selling strategies:");
            out.println("    • PUT_CREDIT_SPREAD (PCS)");
            out.println("    • CALL_CREDIT_SPREAD (CCS)");
            out.printf("  Market regime filter : %s%n", cfg.isMarketRegimeFilterEnabled() ? "ON" : "OFF");
            out.printf("  Max portfolio exposure: %.0f%%%n", cfg.getMaxPortfolioExposurePct());
            out.printf("  Daily loss limit     : %.1f%%%n", cfg.getDailyLossLimitPct());
            out.printf("  Stop loss fraction   : %.0f%%%n", cfg.getOptionsStopLossFrac() * 100);
            out.printf("  Profit target        : %.0f%%%n", cfg.getProfitTarget() * 100);
            out.printf("  Position budget      : %.0f%% of capital per trade%n", cfg.getPositionBudgetFrac() * 100);
            if (cfg.getOptionsEntryCutoff() != null)
                out.printf("  Entry cutoff         : %s%n", cfg.getOptionsEntryCutoff());
            out.println();

            out.println("─── Overall Backtest Results ─────────────────────────────────────────────────");
            out.printf("  Return       : %+.2f%%%n", result.getTotalReturnPct());
            out.printf("  Max Drawdown : %.2f%%%n",  result.getMaxDrawdownPct());
            out.printf("  Final Balance: $%.2f%n",   result.getFinalBalance());
            out.printf("  Total Trades : %d  (W:%d  L:%d  WR:%.1f%%)%n",
                    result.getTotalTrades(), result.getWins(), result.getLosses(),
                    result.getTotalTrades() > 0 ? 100.0 * result.getWins() / result.getTotalTrades() : 0.0);
            out.printf("  PCS opens    : %d%n", pcsOpens);
            out.printf("  CCS opens    : %d%n%n", ccsOpens);

            out.println("─── Symbol Rankings (by Total P&L, all strategies combined) ─────────────────");
            out.println("  * = currently in live watchlist");
            out.println();
            out.printf("  %-5s  %-6s  %11s  %11s  %10s  %10s  %6s  %s%n",
                    "Rank", "Symbol", "Total P&L", "Intraday", "PCS P&L", "CCS P&L", "Txns", "");
            out.println("  " + "-".repeat(76));

            int rank = 0;
            for (SymRank sr : ranked) {
                rank++;
                String marker = sr.inCurrent() ? "*" : "";
                String top30  = rank <= 30 ? (rank <= 10 ? " ◄ TOP 10" : " ◄ TOP 30") : "";
                out.printf("  %-5d  %-6s  %+11.0f  %+11.0f  %+10.0f  %+10.0f  %6d  %s%s%n",
                        rank, sr.symbol() + marker,
                        sr.totalPnl(), sr.intradayPnl(), sr.pcsPnl(), sr.ccsPnl(),
                        sr.txns(), marker.isEmpty() ? "" : "", top30);
            }
            out.println();

            out.println("─── Recommended Top 30 ──────────────────────────────────────────────────────");
            out.println("  (ranked by combined intraday + premium P&L across 800-day backtest)");
            out.println();
            int i = 0;
            for (SymRank sr : ranked) {
                if (++i > 30) break;
                String status = sr.inCurrent() ? "KEEP   " : "ADD ◄◄";
                out.printf("  %2d. %-6s  %+11.0f  %s%n", i, sr.symbol(), sr.totalPnl(), status);
            }
            out.println();

            out.println("─── Symbols to Drop (currently in watchlist, ranked outside top 30) ─────────");
            boolean anyDrop = false;
            int dropRank = 0;
            for (SymRank sr : ranked) {
                dropRank++;
                if (dropRank <= 30) continue;
                if (sr.inCurrent()) {
                    out.printf("  %-6s  ranked #%d  total P&L: %+.0f%n",
                            sr.symbol(), dropRank, sr.totalPnl());
                    anyDrop = true;
                }
            }
            if (!anyDrop) out.println("  None — all current watchlist symbols rank in top 30.");
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
