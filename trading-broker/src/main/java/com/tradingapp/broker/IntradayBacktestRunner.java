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
import java.nio.file.StandardOpenOption;
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
import java.util.TreeMap;

/**
 * Standalone CLI runner: fetches ~100 trading days of 1-min bars, replays them through
 * the real TradingLoop/OptionsSignalRouter stack, and writes a detailed analysis report.
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.IntradayBacktestRunner
 */
public class IntradayBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ET);

    record RunResult(String label, IntradayBacktestResult result, Set<String> strategies) {}

    // Lightweight summary kept between runs in strategy-compare mode (avoids holding full result in memory)
    record RunSummary(String label, Set<String> strategies, double returnPct, double maxDd, int trades, int wins, int losses) {}

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();
        if (cfg.getAlpacaApiKey().isBlank() || cfg.getAlpacaApiSecret().isBlank()) {
            System.err.println("ERROR: Alpaca API keys not set in ~/.tradingapp/day-trader/app.properties");
            System.exit(1);
        }

        Path reportPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-report.txt");
        Files.createDirectories(reportPath.getParent());

        System.out.println("=== Intraday Backtest Runner ===");
        System.out.println("Report will be written to: " + reportPath);

        // --- Fetch bars ---
        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        LocalDate endDate = LocalDate.now(ET).minusDays(1);
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            endDate = endDate.minusDays(1);
        }
        LocalDate startDate = endDate.minusDays(800);

        // Empty = confirmation run; populate for swap-testing candidates against the final 30
        List<String> newCandidates = List.of();

        List<String> baseWatchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        List<String> allSymbols = new ArrayList<>(baseWatchlist);
        allSymbols.addAll(newCandidates);

        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();

        System.out.println("Fetching bars " + startDate + " -> " + endDate + " for " + allSymbols.size() + " symbols...");
        int total = allSymbols.size();
        int idx = 0;
        for (String sym : allSymbols) {
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

        if (barsBySymbol.isEmpty()) {
            System.err.println("ERROR: no bar data fetched");
            System.exit(1);
        }
        System.out.println("Fetched data for " + barsBySymbol.size() + " symbols. Running sim...");

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());

        Set<String> BASE_OPTS      = cfg.getOptionsSymbolAllowlist();
        Set<String> CALLS_DISABLED = cfg.getOptionsCallsDisabled();

        record RunCfg(String label, List<String> watchlist, Set<String> optAllowlist, Set<String> strategies) {}

        java.util.List<RunCfg> runs = new java.util.ArrayList<>();

        String mode = System.getProperty("backtest.mode", "");
        if ("strategy-compare".equals(mode)) {
            // Run each strategy individually, then the current config as the combined baseline
            List<String> ALL_STRATEGIES = List.of(
                    "HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT",
                    "ZERO_DTE", "OPENING_BREAKOUT", "STOCHASTIC_REVERSAL",
                    "RELATIVE_STRENGTH_DIVERGENCE", "MACD_CROSSOVER");
            for (String s : ALL_STRATEGIES) {
                runs.add(new RunCfg(String.format("%-35s", s), baseWatchlist, BASE_OPTS, Set.of(s)));
            }
            // Add the current live config as the combined baseline at the end
            runs.add(new RunCfg("CURRENT CONFIG (combined)", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies()));
        } else if (newCandidates.isEmpty()) {
            // Watchlist is at capacity — single confirmation run with all 30 symbols
            runs.add(new RunCfg("FINAL: all 30 symbols (capacity confirmation)", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies()));
        } else {
            // Screening mode — baseline + one run per candidate + combined
            runs.add(new RunCfg("A: Baseline", baseWatchlist, BASE_OPTS, cfg.getEnabledStrategies()));
            for (String sym : newCandidates) {
                List<String> wl = new ArrayList<>(baseWatchlist);
                if (barsBySymbol.containsKey(sym)) wl.add(sym);
                java.util.HashSet<String> opts = new java.util.HashSet<>(BASE_OPTS);
                opts.add(sym);
                runs.add(new RunCfg(String.format("%-6s", sym) + ": baseline + " + sym, wl, Set.copyOf(opts), cfg.getEnabledStrategies()));
            }
            List<String> allWl = new ArrayList<>(baseWatchlist);
            java.util.HashSet<String> allOpts = new java.util.HashSet<>(BASE_OPTS);
            for (String sym : newCandidates) {
                if (barsBySymbol.containsKey(sym)) { allWl.add(sym); allOpts.add(sym); }
            }
            runs.add(new RunCfg("ALL: baseline + all candidates", allWl, Set.copyOf(allOpts), cfg.getEnabledStrategies()));
        }

        java.util.List<RunResult> results = new java.util.ArrayList<>();
        java.util.List<RunSummary> summaries = new java.util.ArrayList<>();

        for (RunCfg cfg2 : runs) {
            System.out.println("\n=== " + cfg2.label() + " ===");
            OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
            OptionsSignalRouter router = new OptionsSignalRouter(
                    new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
            router.setMaxPortfolioExposure(maxExposure);
            router.setEnabledStrategies(cfg2.strategies());
            router.setStopLossFrac(cfg.getOptionsStopLossFrac());
            router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
            if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
            router.setOptionsAllowlist(cfg2.optAllowlist());
            router.setCallsDisabledSymbols(CALLS_DISABLED);
            router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
            router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());

            long t0 = System.currentTimeMillis();
            IntradayBacktestResult r = engine.run(cfg2.watchlist(), barsBySymbol, 100_000.0, router, msg -> {},
                    Set.of(), loop -> {
                        router.setUptrendSupplier(loop::isUptrend);
                        loop.setStockTradingEnabled(false);
                        loop.setMaxConcurrentStockPositions(10);
                        loop.setAvoidOvernightHolds(false);
                        loop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
                        loop.setAccurateOptionsValuation(true);
                        router.setClosePositionsOnHalt(true);
                    });
            System.out.printf("Done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                    (System.currentTimeMillis() - t0) / 1000.0,
                    r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), r.getWins(), r.getLosses());

            if ("strategy-compare".equals(mode)) {
                // Discard full result immediately to free memory; keep only summary numbers
                summaries.add(new RunSummary(cfg2.label().trim(), cfg2.strategies(),
                        r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                        r.getTotalTrades(), r.getWins(), r.getLosses()));
            } else {
                results.add(new RunResult(cfg2.label(), r, cfg2.strategies()));
            }
        }

        if ("strategy-compare".equals(mode)) {
            summaries.sort(Comparator.comparingDouble(RunSummary::returnPct).reversed());
            System.out.printf("%n%-37s  %8s  %8s  %7s  %7s%n", "Strategy", "Return", "MaxDD", "Trades", "WinRate");
            System.out.println("-".repeat(80));
            for (RunSummary s : summaries) {
                double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                System.out.printf("%-37s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                        s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
            }
            appendHistorySummaries(cfg, summaries, startDate, endDate);
            System.out.println("\nHistory appended to: " + Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv"));
            return;
        }

        System.out.printf("%-40s  %8s  %8s  %7s  %7s%n", "Config", "Return", "MaxDD", "Trades", "WinRate");
        System.out.println("-".repeat(80));
        for (RunResult rr : results) {
            IntradayBacktestResult r = rr.result();
            double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
            System.out.printf("%-40s  %7.2f%%  %7.2f%%  %7d  %6.1f%%%n",
                    rr.label(), r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                    r.getTotalTrades(), wr);
        }

        // Write report for the highest-return run
        RunResult best = results.stream()
                .max(Comparator.comparingDouble(rr -> rr.result().getTotalReturnPct()))
                .orElseThrow();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            writeReport(out, best.result(), startDate, endDate);
        }
        System.out.println("\nReport written for best run (" + best.label() + "): " + reportPath);

        // Append a one-line summary to the persistent history log so every run is traceable.
        appendHistory(cfg, results, startDate, endDate);
    }

    private static void appendHistorySummaries(AppConfig cfg, java.util.List<RunSummary> summaries,
                                               LocalDate startDate, LocalDate endDate) {
        try {
            Path histPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv");
            boolean isNew = !Files.exists(histPath);
            try (PrintWriter h = new PrintWriter(Files.newBufferedWriter(histPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                if (isNew) {
                    h.println("timestamp\tperiod\tstrategies\tstop_loss\tentry_cutoff\tallowlist_count\tlabel\treturn_pct\tmax_drawdown\ttrades\twin_rate");
                }
                String ts = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String period = startDate + " to " + endDate;
                String stopLoss = String.valueOf(cfg.getOptionsStopLossFrac());
                String cutoff = cfg.getOptionsEntryCutoff() != null ? cfg.getOptionsEntryCutoff().toString() : "";
                int allowlistCount = cfg.getOptionsSymbolAllowlist().size();
                for (RunSummary s : summaries) {
                    double wr = s.trades() > 0 ? 100.0 * s.wins() / s.trades() : 0.0;
                    h.printf("%s\t%s\t%s\t%s\t%s\t%d\t%s\t%.2f\t%.2f\t%d\t%.1f%n",
                            ts, period, String.join("|", s.strategies()),
                            stopLoss, cutoff, allowlistCount,
                            s.label(), s.returnPct(), s.maxDd(), s.trades(), wr);
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not write backtest history: " + e.getMessage());
        }
    }

    private static void appendHistory(AppConfig cfg, java.util.List<RunResult> results,
                                       LocalDate startDate, LocalDate endDate) {
        try {
            Path histPath = Path.of(System.getProperty("user.home"), ".tradingapp", "backtest-history.tsv");
            boolean isNew = !Files.exists(histPath);
            try (PrintWriter h = new PrintWriter(Files.newBufferedWriter(histPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                if (isNew) {
                    h.println("timestamp\tperiod\tstrategies\tstop_loss\tentry_cutoff\tallowlist_count\tlabel\treturn_pct\tmax_drawdown\ttrades\twin_rate");
                }
                String ts = ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String period = startDate + " to " + endDate;
                String stopLoss = String.valueOf(cfg.getOptionsStopLossFrac());
                String cutoff = cfg.getOptionsEntryCutoff() != null ? cfg.getOptionsEntryCutoff().toString() : "";
                int allowlistCount = cfg.getOptionsSymbolAllowlist().size();
                for (RunResult rr : results) {
                    IntradayBacktestResult r = rr.result();
                    double wr = r.getTotalTrades() > 0 ? 100.0 * r.getWins() / r.getTotalTrades() : 0.0;
                    String strategies = String.join("|", rr.strategies());
                    h.printf("%s\t%s\t%s\t%s\t%s\t%d\t%s\t%.2f\t%.2f\t%d\t%.1f%n",
                            ts, period, strategies, stopLoss, cutoff, allowlistCount,
                            rr.label().trim(), r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                            r.getTotalTrades(), wr);
                }
            }
            System.out.println("History appended to: " + histPath);
        } catch (Exception e) {
            System.err.println("Warning: could not write backtest history: " + e.getMessage());
        }
    }

    private static void writeReport(PrintWriter out, IntradayBacktestResult result,
                                    LocalDate startDate, LocalDate endDate) {
        out.println("=== INTRADAY BACKTEST REPORT ===");
        out.println("Period : " + startDate + " to " + endDate);
        out.println("Generated: " + ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        out.println();

        // --- Summary stats ---
        out.println("--- SUMMARY ---");
        out.printf("Final Balance  : $%,.2f%n", result.getFinalBalance());
        out.printf("Total Return   : %.2f%%%n", result.getTotalReturnPct());
        out.printf("Max Drawdown   : %.2f%%%n", result.getMaxDrawdownPct());
        out.printf("Total Trades   : %d (wins=%d losses=%d)%n",
                result.getTotalTrades(), result.getWins(), result.getLosses());
        if (result.getTotalTrades() > 0) {
            out.printf("Win Rate       : %.1f%%%n",
                    100.0 * result.getWins() / result.getTotalTrades());
        }
        out.println();

        // --- Equity curve ---
        out.println("--- DAILY EQUITY CURVE ---");
        List<BacktestDataPoint> curve = result.getEquityCurve();
        double prev = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            double pct = prev > 0 ? (pt.getPortfolioValue() - prev) / prev * 100.0 : 0;
            out.printf("%s  $%,10.2f  %+.2f%%%n", pt.getDate(), pt.getPortfolioValue(), pct);
            prev = pt.getPortfolioValue();
        }
        out.println();

        // --- Transaction analysis ---
        List<TransactionRecord> trades = result.getTrades();
        if (!trades.isEmpty()) {
            out.println("--- TRADE LOG (chronological) ---");
            // findAll returns DESC; reverse to chronological
            List<TransactionRecord> chronological = new ArrayList<>(trades);
            chronological.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));

            // Group buys/sells by symbol to compute round-trip P&L
            Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
            List<String[]> roundTrips = new ArrayList<>(); // [ts, sym, action, qty, entry, exit, pnl, reason]

            for (TransactionRecord r : chronological) {
                String sym = r.getSymbol();
                String action = r.getAction().name();
                String ts = DT_FMT.format(Instant.ofEpochMilli(r.getTimestamp()));
                out.printf("%s  %-20s %-12s qty=%4d  price=$%8.3f  fee=$%.2f  bal=$%,.2f  %s%n",
                        ts, sym, action, r.getQuantity(), r.getPricePerUnit(),
                        r.getFeeCharged(), r.getBalanceAfter(),
                        r.getReason() != null ? r.getReason() : "");

                boolean isBuy = action.equals("BUY") || action.equals("CALL_BUY") || action.equals("PUT_BUY");
                boolean isSell = action.equals("SELL") || action.equals("CALL_SELL") || action.equals("PUT_SELL");

                if (isBuy) {
                    buyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(r);
                } else if (isSell) {
                    List<TransactionRecord> buys = buyStack.get(sym);
                    if (buys != null && !buys.isEmpty()) {
                        TransactionRecord buy = buys.remove(0);
                        double costBasis = buy.getPricePerUnit() * r.getQuantity();
                        double proceeds = r.getPricePerUnit() * r.getQuantity();
                        boolean isOptions = action.startsWith("CALL_") || action.startsWith("PUT_");
                        double multiplier = isOptions ? 100.0 : 1.0;
                        double pnl = (proceeds - costBasis) * multiplier - buy.getFeeCharged() - r.getFeeCharged();
                        roundTrips.add(new String[]{
                                ts, sym, action,
                                String.valueOf(r.getQuantity()),
                                String.format("%.3f", buy.getPricePerUnit()),
                                String.format("%.3f", r.getPricePerUnit()),
                                String.format("%.2f", pnl),
                                r.getReason() != null ? r.getReason() : ""
                        });
                    }
                }
            }
            out.println();

            // --- Round-trip P&L summary ---
            out.println("--- ROUND-TRIP P&L SUMMARY ---");
            double totalPnl = 0;
            int winners = 0, losers = 0;
            Map<String, double[]> bySymbol = new TreeMap<>(); // symbol -> [totalPnl, count]
            Map<String, double[]> byType = new TreeMap<>();   // STOCK/CALL/PUT -> [totalPnl, count]

            for (String[] rt : roundTrips) {
                double pnl = Double.parseDouble(rt[6]);
                totalPnl += pnl;
                if (pnl >= 0) winners++; else losers++;

                bySymbol.computeIfAbsent(rt[1], k -> new double[2]);
                bySymbol.get(rt[1])[0] += pnl;
                bySymbol.get(rt[1])[1]++;

                String type = rt[2].contains("CALL") ? "CALL" : rt[2].contains("PUT") ? "PUT" : "STOCK";
                byType.computeIfAbsent(type, k -> new double[2]);
                byType.get(type)[0] += pnl;
                byType.get(type)[1]++;

                out.printf("  %s  %-20s %-5s  entry=$%s exit=$%s qty=%s  P&L=$%s  %s%n",
                        rt[0], rt[1], rt[2].replace("_SELL",""), rt[4], rt[5], rt[3], rt[6], rt[7]);
            }
            out.println();
            out.printf("  Total Round-Trip P&L: $%.2f  (winners=%d losers=%d)%n", totalPnl, winners, losers);
            out.println();

            out.println("  By Symbol:");
            bySymbol.entrySet().stream()
                    .sorted((a, b) -> Double.compare(a.getValue()[0], b.getValue()[0]))
                    .forEach(e -> out.printf("    %-8s  P&L=$%10.2f  trades=%.0f%n",
                            e.getKey(), e.getValue()[0], e.getValue()[1]));
            out.println();
            out.println("  By Trade Type:");
            byType.forEach((type, v) ->
                    out.printf("    %-6s  P&L=$%10.2f  trades=%.0f%n", type, v[0], v[1]));
            out.println();
        }

        // --- Event log pattern analysis ---
        out.println("--- EVENT LOG PATTERN ANALYSIS ---");
        List<String> log = result.getEventLog();
        out.printf("Total log lines: %d%n%n", log.size());

        Map<String, Integer> patternCounts = new TreeMap<>();
        String[] patterns = {
            "downtrend", "SPY below", "overbought", "cooldown", "re-entry cooldown",
            "MULTILEG_REENTRY", "daily loss limit", "HALTED", "stop loss", "STOP",
            "profit target", "earnings", "VIX", "capacity", "concurrent positions",
            "CALL skip", "PUT skip", "BUY skipped", "SELL skipped"
        };
        for (String p : patterns) patternCounts.put(p, 0);

        List<String> lossLines = new ArrayList<>();
        List<String> haltLines = new ArrayList<>();

        for (String line : log) {
            String lower = line.toLowerCase();
            for (String p : patterns) {
                if (lower.contains(p.toLowerCase())) {
                    patternCounts.merge(p, 1, Integer::sum);
                }
            }
            if (lower.contains("stop loss") || lower.contains("stop-loss") || lower.contains("closed") && lower.contains("loss")) {
                lossLines.add(line);
            }
            if (lower.contains("halted") || lower.contains("daily loss limit")) {
                haltLines.add(line);
            }
        }

        out.println("Skip/Block pattern frequencies:");
        patternCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.printf("  %-30s : %d%n", e.getKey(), e.getValue()));
        out.println();

        if (!haltLines.isEmpty()) {
            out.println("Daily loss limit triggers (" + haltLines.size() + "):");
            haltLines.forEach(l -> out.println("  " + l));
            out.println();
        }

        if (!lossLines.isEmpty()) {
            out.println("Stop-loss / closed-at-loss events (first 50):");
            lossLines.stream().limit(50).forEach(l -> out.println("  " + l));
            out.println();
        }

        // --- Full event log ---
        out.println("--- FULL EVENT LOG ---");
        log.forEach(out::println);
    }
}
