package com.tradingapp.broker;

import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.LargeCapWatchList;
import com.tradingapp.engine.BacktestDataPoint;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;

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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Stock-only intraday backtest runner.
 * Uses cached 1-min bar data from ~/.tradingapp/bar-cache/1min/.
 * Run via: mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.StockBacktestRunner
 * Output: backtest-stocks-report.txt in the current directory.
 */
public class StockBacktestRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ET);

    public static void main(String[] args) throws Exception {
        AppConfig cfg = AppConfig.load();

        // Find the most recent date that the majority of watchlist symbols have cached,
        // to avoid any API calls for missing days.
        List<String> watchlistForScan = new ArrayList<>(LargeCapWatchList.SYMBOLS);
        if (!watchlistForScan.contains("SPY")) watchlistForScan.add("SPY");
        Path cacheRoot = Path.of(System.getProperty("user.home"), ".tradingapp", "bar-cache", "1min");
        Map<LocalDate, Integer> lastDateCounts = new HashMap<>();
        for (String sym : watchlistForScan) {
            Path symDir = cacheRoot.resolve(sym);
            if (Files.isDirectory(symDir)) {
                try (var stream = Files.list(symDir)) {
                    stream.map(p -> p.getFileName().toString().replace(".json", ""))
                          .filter(s -> s.matches("\\d{4}-\\d{2}-\\d{2}"))
                          .map(LocalDate::parse)
                          .max(Comparator.naturalOrder())
                          .ifPresent(d -> lastDateCounts.merge(d, 1, Integer::sum));
                }
            }
        }
        // Pick the most-common last-cached date (majority vote across symbols)
        LocalDate endDate = lastDateCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(LocalDate.now(ET).minusDays(5));
        while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY || endDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            endDate = endDate.minusDays(1);
        }
        // Two years of calendar days back from last cached date
        LocalDate startDate = endDate.minusDays(730);

        Path reportPath = Path.of("backtest-stocks-report.txt");

        // Risk parameters (hardcoded — AppConfig carries options config, not stock risk)
        final double TRAILING_STOP_PCT    = 0.04;
        final double MAX_LOSS_PER_TRADE   = 0.003;
        final double CIRCUIT_BREAKER_PCT  = 0.02;
        final int    MAX_POSITIONS        = 8;

        System.out.println("=== Stock-Only Intraday Backtest ===");
        System.out.println("Period  : " + startDate + " → " + endDate);
        System.out.println("Config  : trailing stop=4%  max loss/trade=0.30%  circuit breaker=2%"
                + "  max positions=" + MAX_POSITIONS
                + "  daily loss limit=" + String.format("%.0f%%", cfg.getDailyLossLimitPct()));
        System.out.println("Report  : " + reportPath.toAbsolutePath());

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);

        List<String> watchlist = new ArrayList<>(LargeCapWatchList.SYMBOLS);
        if (!watchlist.contains("SPY")) watchlist.add("SPY");

        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
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
                System.out.printf("[%d/%d] SKIP %s: %s%n", cur, total, sym, e.getMessage());
            }
        }
        System.out.println("Loaded data for " + barsBySymbol.size() + "/" + total + " symbols. Running sim...");

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());

        Consumer<com.tradingapp.engine.TradingLoop> baseConfig = loop -> {
            loop.setStockTradingEnabled(true);
            loop.setTrailingStopPct(TRAILING_STOP_PCT);
            loop.setMaxLossPerTradePct(MAX_LOSS_PER_TRADE);
            loop.setCircuitBreakerPct(CIRCUIT_BREAKER_PCT);
            loop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
            loop.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
            loop.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
            loop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
            loop.setMaxConcurrentStockPositions(MAX_POSITIONS);
            loop.setAccurateOptionsValuation(false);
        };

        System.out.println("Running pass 1/2: baseline (5-day SPY MA)...");
        long t0 = System.currentTimeMillis();
        IntradayBacktestResult result1 = engine.run(
                new ArrayList<>(barsBySymbol.keySet()), barsBySymbol, 100_000.0,
                null, msg -> {}, java.util.Set.of(),
                loop -> { baseConfig.accept(loop); loop.setRegimeMaDays(5); });

        System.out.printf("Pass 1 done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                result1.getTotalReturnPct(), result1.getMaxDrawdownPct(),
                result1.getTotalTrades(), result1.getWins(), result1.getLosses());

        System.out.println("Running pass 2/2: eased (20-day SPY MA)...");
        long t1 = System.currentTimeMillis();
        IntradayBacktestResult result2 = engine.run(
                new ArrayList<>(barsBySymbol.keySet()), barsBySymbol, 100_000.0,
                null, msg -> {}, java.util.Set.of(),
                loop -> { baseConfig.accept(loop); loop.setRegimeMaDays(20); });

        System.out.printf("Pass 2 done in %.1fs  Return: %.2f%%  MaxDD: %.2f%%  Trades: %d (W:%d L:%d)%n",
                (System.currentTimeMillis() - t1) / 1000.0,
                result2.getTotalReturnPct(), result2.getMaxDrawdownPct(),
                result2.getTotalTrades(), result2.getWins(), result2.getLosses());

        double spyReturn = computeSpyReturn(barsBySymbol);

        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportPath))) {
            writeReport(out, result1, result2, cfg, startDate, endDate, spyReturn,
                    barsBySymbol.size(), TRAILING_STOP_PCT, MAX_LOSS_PER_TRADE,
                    CIRCUIT_BREAKER_PCT, MAX_POSITIONS);
        }
        System.out.println("Report written to: " + reportPath.toAbsolutePath());
    }

    private static double computeSpyReturn(Map<String, List<IntradayBar>> barsBySymbol) {
        List<IntradayBar> spyBars = barsBySymbol.get("SPY");
        if (spyBars == null || spyBars.size() < 2) return Double.NaN;
        spyBars.sort(Comparator.comparing(b -> b.time()));
        double first = spyBars.get(0).close();
        double last  = spyBars.get(spyBars.size() - 1).close();
        return (last - first) / first * 100.0;
    }

    private static void writeReport(PrintWriter out, IntradayBacktestResult result,
                                    IntradayBacktestResult result2, AppConfig cfg,
                                    LocalDate startDate, LocalDate endDate,
                                    double spyReturn, int symbolCount,
                                    double trailingStopPct, double maxLossPerTradePct,
                                    double circuitBreakerPct, int maxPositions) {
        out.println("=== STOCK-ONLY INTRADAY BACKTEST REPORT ===");
        out.println("Period    : " + startDate + " to " + endDate);
        out.println("Universe  : " + symbolCount + " symbols (LargeCapWatchList + SPY)");
        out.println("Generated : " + ZonedDateTime.now(ET).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")));
        out.println();

        out.println("--- CONFIGURATION ---");
        out.printf("Trailing Stop       : %.0f%%%n", trailingStopPct * 100);
        out.printf("Max Loss / Trade    : %.2f%% of portfolio%n", maxLossPerTradePct * 100);
        out.printf("Circuit Breaker     : %.0f%% daily loss → auto-liquidate%n", circuitBreakerPct * 100);
        out.printf("Max Positions       : %d concurrent%n", maxPositions);
        out.printf("Daily Loss Limit    : %.0f%% → halt new entries%n", cfg.getDailyLossLimitPct());
        out.printf("Max Portfolio Exp.  : %.0f%%%n", cfg.getMaxPortfolioExposurePct());
        out.printf("Avoid Overnight     : %s%n", cfg.isAvoidOvernightHolds());
        out.printf("Market Regime Filter: %s%n", cfg.isMarketRegimeFilterEnabled());
        out.println();

        // --- Regime filter comparison ---
        double spy1 = Double.isNaN(spyReturn) ? 0 : spyReturn;
        double wr1 = result.getTotalTrades()  > 0 ? 100.0 * result.getWins()  / result.getTotalTrades()  : 0;
        double wr2 = result2.getTotalTrades() > 0 ? 100.0 * result2.getWins() / result2.getTotalTrades() : 0;
        out.println("--- REGIME FILTER COMPARISON ---");
        out.printf("  %-26s  %20s  %20s%n", "", "5-Day MA (baseline)", "20-Day MA (eased)");
        out.println("  " + "-".repeat(70));
        out.printf("  %-26s  %+19.2f%%  %+19.2f%%%n", "Total Return",
                result.getTotalReturnPct(), result2.getTotalReturnPct());
        out.printf("  %-26s  %20.2f%%  %20.2f%%%n", "Max Drawdown",
                result.getMaxDrawdownPct(), result2.getMaxDrawdownPct());
        out.printf("  %-26s  %20d  %20d%n", "Total Trades",
                result.getTotalTrades(), result2.getTotalTrades());
        out.printf("  %-26s  %19.1f%%  %19.1f%%%n", "Win Rate", wr1, wr2);
        if (!Double.isNaN(spyReturn)) {
            out.printf("  %-26s  %+19.2f pp  %+19.2f pp%n", "Alpha vs SPY",
                    result.getTotalReturnPct() - spy1, result2.getTotalReturnPct() - spy1);
        }
        out.printf("  %-26s  %20.2f%%  %20.2f%%%n", "Final Balance Return",
                result.getTotalReturnPct(), result2.getTotalReturnPct());
        out.println();
        out.println("=== DETAILED RESULTS: 5-DAY MA (BASELINE) ===");
        out.println();

        out.println("--- SUMMARY ---");
        out.printf("Starting Balance   : $100,000.00%n");
        out.printf("Final Balance      : $%,.2f%n", result.getFinalBalance());
        out.printf("Total Return       : %+.2f%%%n", result.getTotalReturnPct());
        if (!Double.isNaN(spyReturn)) {
            out.printf("SPY Benchmark      : %+.2f%%%n", spyReturn);
            out.printf("Alpha vs SPY       : %+.2f pp%n", result.getTotalReturnPct() - spyReturn);
        }
        out.printf("Max Drawdown       : %.2f%%%n", result.getMaxDrawdownPct());
        out.printf("Total Trades       : %d (wins=%d losses=%d)%n",
                result.getTotalTrades(), result.getWins(), result.getLosses());
        if (result.getTotalTrades() > 0) {
            out.printf("Win Rate           : %.1f%%%n",
                    100.0 * result.getWins() / result.getTotalTrades());
        }
        List<BacktestDataPoint> curve = result.getEquityCurve();
        if (curve.size() >= 2) {
            long tradingDays = curve.size();
            double annualizedReturn = result.getTotalReturnPct() / tradingDays * 252;
            out.printf("Trading Days       : %d%n", tradingDays);
            out.printf("Annualized Return  : %+.2f%% (252-day scale)%n", annualizedReturn);
        }
        out.println();

        // --- Monthly performance ---
        out.println("--- MONTHLY PERFORMANCE ---");
        out.printf("  %-10s  %12s  %12s  %8s%n", "Month", "Start Value", "End Value", "Return");
        out.println("  " + "-".repeat(50));
        Map<String, List<BacktestDataPoint>> byMonth = new java.util.LinkedHashMap<>();
        double prevVal = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            String month = pt.getDate().toString().substring(0, 7);
            byMonth.computeIfAbsent(month, k -> new ArrayList<>()).add(pt);
        }
        double monthStart = 100_000.0;
        for (Map.Entry<String, List<BacktestDataPoint>> e : byMonth.entrySet()) {
            List<BacktestDataPoint> days = e.getValue();
            double monthEnd = days.get(days.size() - 1).getPortfolioValue();
            double pct = (monthEnd - monthStart) / monthStart * 100.0;
            out.printf("  %-10s  $%,10.2f  $%,10.2f  %+7.2f%%%n",
                    e.getKey(), monthStart, monthEnd, pct);
            monthStart = monthEnd;
        }
        out.println();

        // --- Daily equity curve ---
        out.println("--- DAILY EQUITY CURVE ---");
        prevVal = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            double dayPct = prevVal > 0 ? (pt.getPortfolioValue() - prevVal) / prevVal * 100.0 : 0;
            out.printf("%s  $%,10.2f  %+.2f%%%n", pt.getDate(), pt.getPortfolioValue(), dayPct);
            prevVal = pt.getPortfolioValue();
        }
        out.println();

        // --- Daily P&L distribution ---
        out.println("--- DAILY P&L DISTRIBUTION ---");
        int neg5More=0, neg5to2=0, neg2to0=0, flat0to2=0, pos2to5=0, pos5More=0;
        double prev2 = 100_000.0;
        for (BacktestDataPoint pt : curve) {
            double pct = (pt.getPortfolioValue() - prev2) / prev2 * 100.0;
            prev2 = pt.getPortfolioValue();
            if      (pct < -5) neg5More++;
            else if (pct < -2) neg5to2++;
            else if (pct <  0) neg2to0++;
            else if (pct <  2) flat0to2++;
            else if (pct <  5) pos2to5++;
            else               pos5More++;
        }
        int totalDays = curve.size();
        out.printf("  < -5%%       : %3d days (%4.1f%%)%n", neg5More, 100.0*neg5More/totalDays);
        out.printf("  -5%% to -2%% : %3d days (%4.1f%%)%n", neg5to2,  100.0*neg5to2/totalDays);
        out.printf("  -2%% to  0%% : %3d days (%4.1f%%)%n", neg2to0,  100.0*neg2to0/totalDays);
        out.printf("   0%% to  2%% : %3d days (%4.1f%%)%n", flat0to2, 100.0*flat0to2/totalDays);
        out.printf("   2%% to  5%% : %3d days (%4.1f%%)%n", pos2to5,  100.0*pos2to5/totalDays);
        out.printf("  > 5%%        : %3d days (%4.1f%%)%n", pos5More, 100.0*pos5More/totalDays);
        out.println();

        // --- Trade log and round-trip analysis ---
        List<TransactionRecord> trades = result.getTrades();
        if (!trades.isEmpty()) {
            List<TransactionRecord> chronological = new ArrayList<>(trades);
            chronological.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));

            Map<String, List<TransactionRecord>> buyStack = new HashMap<>();
            record RoundTrip(String ts, String sym, int qty, double entry, double exit, double pnl, String reason) {}
            List<RoundTrip> roundTrips = new ArrayList<>();

            for (TransactionRecord r : chronological) {
                String sym = r.getSymbol();
                String action = r.getAction().name();
                if ("BUY".equals(action)) {
                    buyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(r);
                } else if ("SELL".equals(action)) {
                    List<TransactionRecord> buys = buyStack.get(sym);
                    if (buys != null && !buys.isEmpty()) {
                        TransactionRecord buy = buys.remove(0);
                        double pnl = (r.getPricePerUnit() - buy.getPricePerUnit()) * r.getQuantity()
                                - buy.getFeeCharged() - r.getFeeCharged();
                        roundTrips.add(new RoundTrip(
                                DT_FMT.format(Instant.ofEpochMilli(r.getTimestamp())),
                                sym, r.getQuantity(), buy.getPricePerUnit(), r.getPricePerUnit(),
                                pnl, r.getReason() != null ? r.getReason() : ""));
                    }
                }
            }

            // --- Round-trip P&L summary ---
            out.println("--- ROUND-TRIP P&L SUMMARY ---");
            double totalPnl = 0;
            int winners = 0, losers = 0;
            Map<String, double[]> bySymbol = new TreeMap<>(); // [pnl, trades, wins, sumWin, sumLoss]
            Map<String, double[]> byReason = new TreeMap<>(); // [trades, wins, pnl]

            for (RoundTrip rt : roundTrips) {
                totalPnl += rt.pnl();
                boolean win = rt.pnl() >= 0;
                if (win) winners++; else losers++;

                double[] sv = bySymbol.computeIfAbsent(rt.sym(), k -> new double[5]);
                sv[0] += rt.pnl(); sv[1]++;
                if (win) { sv[2]++; sv[3] += rt.pnl(); } else { sv[4] += rt.pnl(); }

                String bucket = normalizeExitReason(rt.reason());
                double[] rv = byReason.computeIfAbsent(bucket, k -> new double[3]);
                rv[0]++; if (win) rv[1]++; rv[2] += rt.pnl();

                out.printf("  %s  %-6s  entry=$%8.3f exit=$%8.3f qty=%4d  P&L=$%9.2f  %s%n",
                        rt.ts(), rt.sym(), rt.entry(), rt.exit(), rt.qty(), rt.pnl(), rt.reason());
            }
            out.println();
            out.printf("  Total Round-Trip P&L: $%,.2f  (winners=%d losers=%d)%n%n",
                    totalPnl, winners, losers);

            // --- Per-symbol breakdown ---
            out.println("--- PER-SYMBOL BREAKDOWN ---");
            out.printf("  %-8s  %10s  %6s  %7s  %10s  %10s  %10s%n",
                    "Symbol", "Net P&L", "Trades", "WinRate", "Avg Win", "Avg Loss", "Expectancy");
            out.println("  " + "-".repeat(75));
            bySymbol.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue()[0]))
                    .forEach(e -> {
                        double[] v = e.getValue();
                        double wr  = v[1] > 0 ? v[2] / v[1] : 0;
                        double avgW = v[2] > 0 ? v[3] / v[2] : 0;
                        double avgL = (v[1] - v[2]) > 0 ? v[4] / (v[1] - v[2]) : 0;
                        double exp  = wr * avgW + (1 - wr) * avgL;
                        out.printf("  %-8s  %10.2f  %6.0f  %6.1f%%  %10.2f  %10.2f  %10.2f%n",
                                e.getKey(), v[0], v[1], wr * 100, avgW, avgL, exp);
                    });
            out.println();

            // --- Exit reason breakdown ---
            out.println("--- EXIT REASON BREAKDOWN ---");
            out.printf("  %-30s  %6s  %7s  %11s  %10s%n",
                    "Reason", "Trades", "WinRate", "Total P&L", "Avg P&L");
            out.println("  " + "-".repeat(70));
            byReason.entrySet().stream()
                    .sorted(Comparator.comparingDouble(e -> e.getValue()[2]))
                    .forEach(e -> {
                        double[] v = e.getValue();
                        double wr = v[0] > 0 ? v[1] / v[0] * 100 : 0;
                        out.printf("  %-30s  %6.0f  %6.1f%%  %11.2f  %10.2f%n",
                                e.getKey(), v[0], wr, v[2], v[0] > 0 ? v[2] / v[0] : 0);
                    });
            out.println();
        }

        // --- Event log pattern analysis ---
        out.println("--- EVENT LOG PATTERN ANALYSIS ---");
        List<String> log = result.getEventLog();
        out.printf("Total log lines: %d%n%n", log.size());

        String[] patterns = {
            "BUY skipped", "SPY below", "overbought", "cooldown", "circuit breaker",
            "daily loss limit", "HALTED", "Trailing stop", "capacity", "concurrent positions",
            "earnings", "VIX", "ORB", "VWAP"
        };
        Map<String, Integer> patternCounts = new java.util.LinkedHashMap<>();
        for (String p : patterns) patternCounts.put(p, 0);
        List<String> circuitBreakerLines = new ArrayList<>();
        List<String> haltLines = new ArrayList<>();

        for (String line : log) {
            String lower = line.toLowerCase();
            for (String p : patterns) {
                if (lower.contains(p.toLowerCase())) patternCounts.merge(p, 1, Integer::sum);
            }
            if (lower.contains("circuit breaker")) circuitBreakerLines.add(line);
            if (lower.contains("daily loss limit") || lower.contains("halted")) haltLines.add(line);
        }

        out.println("Event pattern frequencies:");
        patternCounts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.printf("  %-32s : %d%n", e.getKey(), e.getValue()));
        out.println();

        if (!circuitBreakerLines.isEmpty()) {
            out.println("Circuit breaker triggers (" + circuitBreakerLines.size() + "):");
            circuitBreakerLines.forEach(l -> out.println("  " + l));
            out.println();
        }

        if (!haltLines.isEmpty()) {
            out.println("Daily loss halt triggers (" + haltLines.size() + "):");
            haltLines.stream().limit(20).forEach(l -> out.println("  " + l));
            out.println();
        }

        // --- Eased regime filter summary (20-day MA) ---
        out.println("=== SUMMARY: 20-DAY MA (EASED REGIME FILTER) ===");
        out.println();
        out.printf("Starting Balance   : $100,000.00%n");
        out.printf("Final Balance      : $%,.2f%n", result2.getFinalBalance());
        out.printf("Total Return       : %+.2f%%%n", result2.getTotalReturnPct());
        if (!Double.isNaN(spyReturn)) {
            out.printf("SPY Benchmark      : %+.2f%%%n", spyReturn);
            out.printf("Alpha vs SPY       : %+.2f pp%n", result2.getTotalReturnPct() - spyReturn);
        }
        out.printf("Max Drawdown       : %.2f%%%n", result2.getMaxDrawdownPct());
        out.printf("Total Trades       : %d (wins=%d losses=%d)%n",
                result2.getTotalTrades(), result2.getWins(), result2.getLosses());
        if (result2.getTotalTrades() > 0) {
            out.printf("Win Rate           : %.1f%%%n",
                    100.0 * result2.getWins() / result2.getTotalTrades());
        }
        List<BacktestDataPoint> curve2 = result2.getEquityCurve();
        if (curve2.size() >= 2) {
            double annualized2 = result2.getTotalReturnPct() / curve2.size() * 252;
            out.printf("Trading Days       : %d%n", curve2.size());
            out.printf("Annualized Return  : %+.2f%% (252-day scale)%n", annualized2);
        }
        out.println();

        out.println("--- MONTHLY PERFORMANCE (20-DAY MA) ---");
        out.printf("  %-10s  %12s  %12s  %8s%n", "Month", "Start Value", "End Value", "Return");
        out.println("  " + "-".repeat(50));
        Map<String, List<BacktestDataPoint>> byMonth2 = new java.util.LinkedHashMap<>();
        for (BacktestDataPoint pt : curve2) {
            byMonth2.computeIfAbsent(pt.getDate().toString().substring(0, 7), k -> new ArrayList<>()).add(pt);
        }
        double ms2 = 100_000.0;
        for (Map.Entry<String, List<BacktestDataPoint>> e : byMonth2.entrySet()) {
            List<BacktestDataPoint> days = e.getValue();
            double me2 = days.get(days.size() - 1).getPortfolioValue();
            out.printf("  %-10s  $%,10.2f  $%,10.2f  %+7.2f%%%n",
                    e.getKey(), ms2, me2, (me2 - ms2) / ms2 * 100.0);
            ms2 = me2;
        }
        out.println();
    }

    private static String normalizeExitReason(String reason) {
        if (reason == null || reason.isBlank()) return "Unknown";
        String r = reason.toLowerCase();
        if (r.startsWith("trailing stop"))    return "Trailing stop";
        if (r.startsWith("signals:") && r.contains("sell")) return "Signal sell";
        if (r.startsWith("pre-close"))        return "Pre-close (overnight avoid)";
        if (r.contains("circuit breaker"))    return "Circuit breaker";
        if (r.contains("halt"))               return "Daily loss halt";
        return reason.length() > 32 ? reason.substring(0, 32) : reason;
    }
}
