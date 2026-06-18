package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.data.MasterUniverse;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.FeeCalculator;
import com.tradingapp.engine.IndicatorEngine;
import com.tradingapp.engine.IntradayBacktestEngine;
import com.tradingapp.engine.IntradayBacktestResult;
import com.tradingapp.engine.IntradayBar;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs a single 2-year backtest with all 100 MasterUniverse symbols eligible for options,
 * then ranks each symbol by its net round-trip P&L to select the best 30.
 *
 * Run via:
 *   mvn -pl trading-broker exec:java -Dexec.mainClass=com.tradingapp.broker.Top30PickerRunner
 */
public class Top30PickerRunner {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    // These are never options-traded regardless of ranking
    private static final Set<String> OPTIONS_EXCLUDED = Set.of("QQQ", "TSLA");

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

        System.out.println("=== Top-30 Picker Runner ===");
        System.out.println("Strategy: run all 100 symbols, rank by 2-year net P&L, pick best 30");
        System.out.println("Period  : " + startDate + " → " + endDate);
        System.out.println();
        System.out.println("Loading bars from cache...");

        AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);
        Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
        int total = MasterUniverse.SYMBOLS.size(), idx = 0;
        for (String sym : MasterUniverse.SYMBOLS) {
            final int cur = ++idx;
            try {
                List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                        msg -> System.out.printf("[%d/%d] %s%n", cur, total, msg));
                if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
            } catch (Exception e) {
                System.out.println("SKIP " + sym + ": " + e.getMessage());
            }
        }
        System.out.printf("Loaded %d symbols. Running backtest...%n%n", barsBySymbol.size());

        // All symbols eligible for options except the hard-excluded ones
        List<String> watchlist = new ArrayList<>(barsBySymbol.keySet());
        Set<String> optAllowlist = new java.util.HashSet<>(watchlist);
        optAllowlist.removeAll(OPTIONS_EXCLUDED);

        double maxExposure = cfg.getMaxPortfolioExposurePct() / 100.0;

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
        OptionsSignalRouter router = new OptionsSignalRouter(
                new BlackScholesEngine(), optExec, new Account(), new PriceHistory(), msg -> {}, null);
        router.setMaxPortfolioExposure(maxExposure);
        router.setEnabledStrategies(Set.of("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT"));
        router.setStopLossFrac(cfg.getOptionsStopLossFrac());
        router.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
        if (cfg.getOptionsEntryCutoff() != null) router.setEntryCutoff(cfg.getOptionsEntryCutoff());
        router.setOptionsAllowlist(optAllowlist);
        router.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
        router.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
        router.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());

        IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator())
                .setCollectEventLog(false);

        long t0 = System.currentTimeMillis();
        IntradayBacktestResult result = engine.run(watchlist, barsBySymbol, 100_000.0, router, msg -> {},
                Set.of(), loop -> {
                    router.setUptrendSupplier(loop::isUptrend);
                    loop.setStockTradingEnabled(false);
                    loop.setMaxConcurrentStockPositions(10);
                    loop.setAvoidOvernightHolds(false);
                });
        System.out.printf("Done in %.1fs  Return=%.2f%%  MaxDD=%.2f%%  Trades=%d%n%n",
                (System.currentTimeMillis() - t0) / 1000.0,
                result.getTotalReturnPct(), result.getMaxDrawdownPct(), result.getTotalTrades());

        // Compute per-symbol round-trip P&L from transaction records
        Map<String, double[]> bySymbol = new TreeMap<>(); // symbol -> [totalPnl, wins, losses, trades]
        Map<String, List<TransactionRecord>> buyStack = new HashMap<>();

        List<TransactionRecord> trades = new ArrayList<>(result.getTrades());
        trades.sort(Comparator.comparingLong(TransactionRecord::getTimestamp));

        for (TransactionRecord r : trades) {
            String sym = r.getSymbol();
            String action = r.getAction().name();
            boolean isBuy  = action.equals("CALL_BUY")  || action.equals("PUT_BUY")  || action.equals("BUY");
            boolean isSell = action.equals("CALL_SELL") || action.equals("PUT_SELL") || action.equals("SELL");
            boolean isOpts = action.startsWith("CALL_") || action.startsWith("PUT_");

            if (isBuy) {
                buyStack.computeIfAbsent(sym, k -> new ArrayList<>()).add(r);
            } else if (isSell) {
                List<TransactionRecord> buys = buyStack.get(sym);
                if (buys != null && !buys.isEmpty()) {
                    TransactionRecord buy = buys.remove(0);
                    double multiplier = isOpts ? 100.0 : 1.0;
                    double pnl = (r.getPricePerUnit() - buy.getPricePerUnit())
                            * r.getQuantity() * multiplier
                            - buy.getFeeCharged() - r.getFeeCharged();
                    double[] row = bySymbol.computeIfAbsent(sym, k -> new double[4]);
                    row[0] += pnl;
                    row[3]++;
                    if (pnl >= 0) row[1]++; else row[2]++;
                }
            }
        }

        // Rank all symbols by net P&L
        List<Map.Entry<String, double[]>> ranked = new ArrayList<>(bySymbol.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));

        System.out.println("=== ALL SYMBOLS RANKED BY NET P&L ===");
        System.out.printf("%-8s  %12s  %7s  %7s  %7s  %7s%n",
                "Symbol", "Net P&L", "Trades", "Wins", "Losses", "WinRate");
        System.out.println("-".repeat(60));
        int rank = 0;
        List<String> top30 = new ArrayList<>();
        // SPY is always first (regime anchor) — add it before ranking loop
        top30.add("SPY");

        for (Map.Entry<String, double[]> e : ranked) {
            rank++;
            double[] v = e.getValue();
            double wr = v[3] > 0 ? 100.0 * v[1] / v[3] : 0;
            boolean inTop30 = top30.size() < 30 && !e.getKey().equals("SPY");
            if (inTop30) top30.add(e.getKey());
            String marker = inTop30 ? " ◄" : (e.getKey().equals("SPY") ? " (anchor)" : "");
            System.out.printf("%3d. %-6s  $%,10.2f  %7.0f  %7.0f  %7.0f  %6.1f%%%s%n",
                    rank, e.getKey(), v[0], v[3], v[1], v[2], wr, marker);
        }

        // Report symbols with no trades (in allowlist but zero signals fired)
        List<String> noTrades = new ArrayList<>();
        for (String sym : optAllowlist) {
            if (!bySymbol.containsKey(sym)) noTrades.add(sym);
        }
        if (!noTrades.isEmpty()) {
            System.out.println("\nNo trades fired for: " + noTrades);
        }

        System.out.println();
        System.out.println("=== RECOMMENDED TOP-30 WATCHLIST ===");
        System.out.println("(SPY anchored first; remaining 29 by descending 2-year net P&L)");
        System.out.println();
        for (int i = 0; i < top30.size(); i++) {
            System.out.printf("  %2d. %s%n", i + 1, top30.get(i));
        }
        System.out.println();
        System.out.println("As a comma-separated list:");
        System.out.println("  " + String.join(",", top30));
    }
}
