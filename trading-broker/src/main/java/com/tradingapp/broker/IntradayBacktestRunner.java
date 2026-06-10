package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.BacktestDataPoint;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.engine.OptionsEvaluator;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;

import java.io.File;
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
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * Standalone CLI runner: fetches ~100 trading days of 1-min bars, replays them through
 * the real TradingLoop/OptionsSignalRouter stack, and writes a detailed analysis report.
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.IntradayBacktestRunner
 */
public class IntradayBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ET);

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
        LocalDate startDate = endDate.minusDays(140);

        List<String> watchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();

        System.out.println("Fetching bars " + startDate + " -> " + endDate + " for " + watchlist.size() + " symbols...");
        int total = watchlist.size();
        int idx = 0;
        for (String sym : watchlist) {
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

        // --- Wire OptionsSignalRouter per day ---
        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;
        java.util.Set<String> enabledStrategies = cfg.getEnabledStrategies();

        BiFunction<Account, PriceHistory, OptionsEvaluator> optFactory = (acct, ph) -> {
            try {
                File tmpDb = File.createTempFile("opts-bt", ".db");
                tmpDb.deleteOnExit();
                TransactionLog tl = new TransactionLog(tmpDb.getAbsolutePath());
                OptionsOrderExecutor exec = new OptionsOrderExecutor(acct, tl);
                OptionsSignalRouter router = new OptionsSignalRouter(
                        new BlackScholesEngine(), exec, acct, ph, msg -> {}, null);
                router.setMaxPortfolioExposure(maxExposure);
                if (!enabledStrategies.isEmpty()) {
                    router.setEnabledStrategies(enabledStrategies);
                }
                return router;
            } catch (Exception e) {
                System.err.println("WARNING: could not create OptionsSignalRouter: " + e.getMessage());
                return null;
            }
        };

        // --- Run backtest ---
        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());

        long t0 = System.currentTimeMillis();
        // Options trading disabled in backtest: ReplayQuoteProvider returns empty chains, and the
        // Black-Scholes router would synthesize unrealistic trades with no real market data.
        IntradayBacktestResult result = engine.run(
                watchlist, barsBySymbol, 100_000.0, null,
                msg -> System.out.println("  " + msg));
        long elapsed = System.currentTimeMillis() - t0;

        System.out.printf("Sim done in %.1fs%n", elapsed / 1000.0);
        System.out.printf("Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                result.getTotalReturnPct(), result.getMaxDrawdownPct(),
                result.getTotalTrades(), result.getWins(), result.getLosses());

        // --- Write report ---
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            writeReport(out, result, startDate, endDate);
        }
        System.out.println("Report written: " + reportPath);
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
