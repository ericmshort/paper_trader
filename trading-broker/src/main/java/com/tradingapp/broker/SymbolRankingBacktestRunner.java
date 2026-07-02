package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Runs the improved strategy (all 6 filters) for each symbol in isolation,
 * then ranks symbols by total return so losers can be removed from the allowlist.
 *
 * Run via:
 *   mvn install && mvn -pl trading-broker exec:java \
 *       -Dexec.mainClass=com.tradingapp.broker.SymbolRankingBacktestRunner
 *
 * Report written to:
 *   ~/.tradingapp/backtest-symbol-ranking.txt
 */
public class SymbolRankingBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    record SymbolResult(
            String symbol,
            double returnPct,
            double maxDrawdownPct,
            double finalBalance,
            int trades,
            int wins,
            int pcsN,
            int ccsN,
            double pcsPnl,
            double ccsPnl
    ) {
        double totalPremPnl() { return pcsPnl + ccsPnl; }
        double winRate()      { return trades > 0 ? 100.0 * wins / trades : 0.0; }
    }

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
            System.err.println("ERROR: no cached data found for SPY");
            System.exit(1);
        }

        List<String> watchlist = new ArrayList<>(MasterUniverse.SYMBOLS);

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = watchlist.size(), idx = 0;
        System.out.println("=== Symbol Ranking Backtest (Improved Settings) ===");
        System.out.printf("Period : %s → %s%n%n", startDate, endDate);
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
        System.out.printf("Loaded %d/%d symbols.%n%n", barsBySymbol.size(), total);

        VixCache vixCache = new VixCache();
        vixCache.load(startDate, endDate);

        IntradayBacktestEngine engine = new IntradayBacktestEngine(
                new IndicatorEngine(), new FeeCalculator()).setCollectEventLog(true);

        // SPY bars are needed for the regime filter even in single-symbol runs
        List<IntradayBar> spyBars = barsBySymbol.get("SPY");

        List<SymbolResult> results = new ArrayList<>();
        int done = 0;
        for (String sym : new ArrayList<>(barsBySymbol.keySet())) {
            System.out.printf("[%d/%d] Running %s...%n", ++done, barsBySymbol.size(), sym);
            long t0 = System.currentTimeMillis();

            // Single-symbol watchlist; keep SPY for regime filter unless sym IS SPY
            List<String> singleList = new ArrayList<>();
            singleList.add(sym);
            if (!sym.equals("SPY") && spyBars != null) singleList.add("SPY");

            Map<String, List<IntradayBar>> singleBars = new LinkedHashMap<>();
            singleBars.put(sym, barsBySymbol.get(sym));
            if (!sym.equals("SPY") && spyBars != null) singleBars.put("SPY", spyBars);

            Set<String> allowlist = Set.of(sym);  // only the target symbol trades

            List<String> premLog = new ArrayList<>();
            try {
                IntradayBacktestResult r = runSingle(engine, singleList, singleBars,
                        vixCache, cfg, allowlist, premLog);
                SymbolResult sr = parseResult(sym, r, premLog);
                results.add(sr);
                System.out.printf("  done in %ds  →  return %.1f%%  trades %d  prem P&L %+.0f%n",
                        (System.currentTimeMillis() - t0) / 1000,
                        sr.returnPct(), sr.trades(), sr.totalPremPnl());
            } catch (Exception e) {
                System.out.println("  ERROR: " + e.getMessage());
            }
        }

        // Sort by return descending
        results.sort(Comparator.comparingDouble(SymbolResult::returnPct).reversed());

        // ── Console summary ───────────────────────────────────────────────────
        System.out.println();
        System.out.println("=== RESULTS (ranked by return, improved settings) ===");
        System.out.printf("%-6s  %8s  %7s  %6s  %6s  %5s  %5s  %9s  %9s  %10s%n",
                "Symbol", "Return%", "MaxDD%", "Trades", "WinRate", "PCS_N", "CCS_N",
                "PCS_P&L", "CCS_P&L", "Prem_Total");
        System.out.println("-".repeat(90));
        for (SymbolResult sr : results) {
            System.out.printf("%-6s  %7.1f%%  %6.1f%%  %6d  %5.1f%%  %5d  %5d  %+8.0f  %+8.0f  %+9.0f%n",
                    sr.symbol(), sr.returnPct(), sr.maxDrawdownPct(),
                    sr.trades(), sr.winRate(), sr.pcsN(), sr.ccsN(),
                    sr.pcsPnl(), sr.ccsPnl(), sr.totalPremPnl());
        }

        // ── Write detailed report ─────────────────────────────────────────────
        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp",
                "backtest-symbol-ranking.txt");
        writeReport(reportPath, results, startDate, endDate, cfg);
        System.out.println("\nReport written: " + reportPath);
    }

    private static IntradayBacktestResult runSingle(
            IntradayBacktestEngine engine,
            List<String> watchlist,
            Map<String, List<IntradayBar>> barsBySymbol,
            VixCache vixCache,
            AppConfig cfg,
            Set<String> optAllowlist,
            List<String> premLog) throws Exception {

        double maxExposure  = cfg.getMaxPortfolioExposurePct() / 100.0;
        double lossLimitPct = cfg.getDailyLossLimitPct();

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

        BlackScholesEngine bsPrem = new BlackScholesEngine();
        bsPrem.setVixProvider(vixCache::getVix, vixCache.baselineVix());
        OptionsOrderExecutor premExec = new OptionsOrderExecutor(new Account(), null);
        PremiumSellerRouter premRouter = new PremiumSellerRouter(
                bsPrem, premExec, new Account(), new PriceHistory(), premLog::add);
        premRouter.setEnabledStrategies(Set.of(
                PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD,
                PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));
        premRouter.setMaxPortfolioExposure(maxExposure);
        premRouter.setAllowlist(optAllowlist);
        // Apply all improved filters
        premRouter.setMinEntryTime(9, 45);
        premRouter.setMaxConcurrentSpreads(6);
        premRouter.setSectorConcentrationLimit(2);
        premRouter.setPcsRequireNonNegativeMacd(true);
        premRouter.setCcsRequireSellSignal(true);
        premRouter.setUseShortExpiry(true);

        OptionsEvaluator optEval = new ImprovementsBacktestRunner.CompositeEvaluator(
                intradayRouter, premRouter);

        return engine.run(watchlist, barsBySymbol, 100_000.0, optEval,
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
    }

    private static SymbolResult parseResult(String sym, IntradayBacktestResult r,
                                            List<String> premLog) {
        double pcsPnl = 0, ccsPnl = 0;
        int pcsN = 0, ccsN = 0;
        for (String line : premLog) {
            boolean isPcsClose = line.contains("PUT CREDIT SPREAD closed:");
            boolean isCcsClose = line.contains("CALL CREDIT SPREAD closed:");
            boolean isPcsOpen  = line.contains("PUT CREDIT SPREAD")  && !line.contains("closed:");
            boolean isCcsOpen  = line.contains("CALL CREDIT SPREAD") && !line.contains("closed:");
            if (isPcsOpen) pcsN++;
            if (isCcsOpen) ccsN++;
            if (isPcsClose || isCcsClose) {
                int pi = line.indexOf("P&L $");
                if (pi >= 0) {
                    try {
                        double pnl = Double.parseDouble(line.substring(pi + 5).trim());
                        if (isPcsClose) pcsPnl += pnl; else ccsPnl += pnl;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return new SymbolResult(sym,
                r.getTotalReturnPct(), r.getMaxDrawdownPct(), r.getFinalBalance(),
                r.getTotalTrades(), r.getWins(), pcsN, ccsN, pcsPnl, ccsPnl);
    }

    private static void writeReport(Path path, List<SymbolResult> results,
                                    LocalDate startDate, LocalDate endDate,
                                    AppConfig cfg) throws Exception {
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(path))) {
            out.println("=== Symbol Ranking Backtest — Improved Settings ===");
            out.printf("Period    : %s → %s%n", startDate, endDate);
            out.printf("Generated : %s%n%n", ZonedDateTime.now(ET)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));

            out.println("─── Settings (all improved filters active) ──────────────────────────────────");
            out.println("  • Min entry time       : 9:45 AM ET");
            out.println("  • Max concurrent spreads: 6");
            out.println("  • Sector cap           : 2 per sector");
            out.println("  • PCS MACD filter      : MACD ≥ 0 required");
            out.println("  • CCS signal filter    : ≥1 SELL signal required");
            out.println("  • Short expiry         : nearest monthly only");
            out.println("  Each symbol run in isolation (1-symbol watchlist).");
            out.println("  SPY included in every run for market regime filter.");
            out.println();

            out.println("─── Results (ranked by return) ──────────────────────────────────────────────");
            out.printf("  %-6s  %8s  %7s  %6s  %6s  %5s  %5s  %9s  %9s  %10s%n",
                    "Symbol", "Return%", "MaxDD%", "Trades", "WinRate", "PCS_N", "CCS_N",
                    "PCS_P&L", "CCS_P&L", "Prem_Total");
            out.println("  " + "-".repeat(90));
            for (SymbolResult sr : results) {
                out.printf("  %-6s  %7.1f%%  %6.1f%%  %6d  %5.1f%%  %5d  %5d  %+8.0f  %+8.0f  %+9.0f%n",
                        sr.symbol(), sr.returnPct(), sr.maxDrawdownPct(),
                        sr.trades(), sr.winRate(), sr.pcsN(), sr.ccsN(),
                        sr.pcsPnl(), sr.ccsPnl(), sr.totalPremPnl());
            }
            out.println();

            // Tier breakdown
            List<String> winners = new ArrayList<>(), losers = new ArrayList<>();
            for (SymbolResult sr : results) {
                if (sr.returnPct() >= 0) winners.add(sr.symbol()); else losers.add(sr.symbol());
            }
            out.println("─── Summary ─────────────────────────────────────────────────────────────────");
            out.printf("  Profitable symbols (%d): %s%n", winners.size(), String.join(", ", winners));
            out.printf("  Losing symbols     (%d): %s%n", losers.size(),  String.join(", ", losers));
            out.println();

            // Recommended allowlist = profitable symbols sorted alphabetically
            List<String> recommended = new ArrayList<>(winners);
            recommended.sort(String::compareTo);
            out.println("─── Recommended Allowlist (profitable symbols only) ─────────────────────────");
            out.println("  " + String.join(",", recommended));
        }
    }

    private static LocalDate earliestCachedDate(String symbol) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"),
                ".tradingapp", "bar-cache", "1min", symbol);
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
