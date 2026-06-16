package com.tradingapp.options;

import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.HistoricalBarFetcher;
import com.tradingapp.engine.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

/**
 * Runs all enabled options strategies over the last 2 years across the live watchlist
 * and prints a sorted comparison table with implied slippage applied.
 * Not a pass/fail test — just a reporting runner.
 */
public class StrategyComparisonRunner {

    private static final List<String> SYMBOLS = DayTraderWatchList.SYMBOLS;
    private static final double       STARTING_BALANCE = 100_000.0;

    record Row(String name, double returnPct, double maxDrawdown, double winRate, int trades) {
        double riskReward() { return maxDrawdown > 0 ? returnPct / maxDrawdown : 0; }
    }

    @Test
    void compareAllStrategies() throws Exception {
        LocalDate end   = LocalDate.now().minusDays(1);
        LocalDate start = end.minusYears(2);

        System.out.println("\n=== Fetching 2-year bars (with implied slippage) for: " + SYMBOLS + " ===");
        HistoricalBarFetcher fetcher = new HistoricalBarFetcher();
        Map<String, List<HistoricalBar>> bars = new LinkedHashMap<>();
        for (String sym : SYMBOLS) {
            List<HistoricalBar> b = fetcher.fetchDailyBars(sym, start, end);
            bars.put(sym, b);
            System.out.printf("  %-5s  %d bars (%s → %s)%n", sym, b.size(),
                    b.isEmpty() ? "?" : b.get(0).getDate(),
                    b.isEmpty() ? "?" : b.get(b.size() - 1).getDate());
        }

        BacktestConfig cfg = new BacktestConfig(SYMBOLS, start, end, STARTING_BALANCE);
        IndicatorEngine  indicators = new IndicatorEngine();
        BlackScholesEngine bs       = new BlackScholesEngine();
        FeeCalculator fees          = new FeeCalculator();

        List<Row> rows = new ArrayList<>();

        // Equity baseline
        BacktestResult eq = new BacktestEngine(indicators, fees).run(cfg, bars);
        rows.add(new Row("Equity (signal-based)", eq.getTotalReturnPct(),
                eq.getMaxDrawdownPct(), eq.winRate(), eq.getTotalTrades()));

        // All options strategies
        OptionsBacktestEngine optEngine = new OptionsBacktestEngine(indicators, bs, fees);
        for (OptionsStrategy s : OptionsStrategy.values()) {
            BacktestResult r = optEngine.run(s, cfg, bars);
            rows.add(new Row(s.getDisplayName(), r.getTotalReturnPct(),
                    r.getMaxDrawdownPct(), r.winRate(), r.getTotalTrades()));
        }

        // Sort: return/risk descending, then return descending
        rows.sort(Comparator.comparingDouble(Row::riskReward)
                            .thenComparingDouble(Row::returnPct)
                            .reversed());

        // Print table
        System.out.printf("%n%-24s  %8s  %11s  %10s  %8s  %7s%n",
                "Strategy", "Return%", "MaxDrawdown%", "Return/Risk", "WinRate%", "Trades");
        System.out.println("-".repeat(80));
        for (Row r : rows) {
            String flag = r.returnPct() < 0 ? " ◄ LOSER" : "";
            System.out.printf("%-24s  %+7.1f%%  %10.1f%%  %10.2f  %7.1f%%  %7d%s%n",
                    r.name(), r.returnPct(), r.maxDrawdown(),
                    r.riskReward(), r.winRate(), r.trades(), flag);
        }
        System.out.println("-".repeat(80));

        // Summary callouts
        List<Row> losers  = rows.stream().filter(r -> r.returnPct() < 0).toList();
        List<Row> winners = rows.stream().filter(r -> r.returnPct() >= 0).toList();
        Row best  = rows.get(0);
        Row worst = rows.get(rows.size() - 1);

        System.out.println("\n=== Summary ===");
        System.out.printf("  Best  : %s  (%+.1f%% return, %.2f R/R)%n",
                best.name(), best.returnPct(), best.riskReward());
        System.out.printf("  Worst : %s  (%+.1f%% return)%n",
                worst.name(), worst.returnPct());
        System.out.printf("  Winners: %d / %d   Losers: %d / %d%n",
                winners.size(), rows.size(), losers.size(), rows.size());
        if (!losers.isEmpty()) {
            System.out.println("  Losing strategies:");
            losers.forEach(r -> System.out.printf("    - %s  (%+.1f%%)%n", r.name(), r.returnPct()));
        }
        System.out.println();
    }
}
