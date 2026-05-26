package com.tradingapp.ui;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.YahooFinanceQuoteProvider;
import com.tradingapp.engine.*;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsBacktestEngine;
import com.tradingapp.options.OptionsStrategy;

import java.time.LocalDate;
import java.util.*;

/**
 * Console runner: fetches ~1 year of real price data and compares all 9 strategies.
 * Run via: mvn test -pl trading-ui -Dtest=BacktestComparisonRunner#run -q
 */
public class BacktestComparisonRunner {

    record Row(String name, double ret, double dd, double wr, int trades) {
        double rr() { return dd > 0 ? ret / dd : 0; }
    }

    @org.junit.jupiter.api.Test
    public void run() throws Exception {
        List<String> symbols = List.of("AAPL", "MSFT", "NVDA", "SPY");
        LocalDate end   = LocalDate.now().minusDays(1);
        LocalDate start = end.minusYears(1);
        double startingBalance = 100_000.0;

        System.out.println("=".repeat(72));
        System.out.printf("  Strategy Comparison Backtest%n");
        System.out.printf("  Symbols : %s%n", symbols);
        System.out.printf("  Period  : %s → %s%n", start, end);
        System.out.printf("  Capital : $%,.0f%n", startingBalance);
        System.out.println("=".repeat(72));
        System.out.println("Fetching historical data from Yahoo Finance...");

        YahooFinanceQuoteProvider yf = new YahooFinanceQuoteProvider();
        Map<String, List<HistoricalBar>> bars = new LinkedHashMap<>();
        for (String sym : symbols) {
            bars.put(sym, yf.getHistoricalBars(sym, start, end));
        }
        System.out.printf("Loaded %d symbols, %d bars each (approx)%n%n",
                symbols.size(), bars.values().stream().mapToInt(List::size).max().orElse(0));

        BacktestConfig cfg = new BacktestConfig(symbols, start, end, startingBalance);
        List<Row> rows = new ArrayList<>();

        // --- Equity baseline ---
        BacktestResult eq = new BacktestEngine(new IndicatorEngine(), new FeeCalculator())
                .run(cfg, bars);
        rows.add(new Row("Equity (Current)", eq.getTotalReturnPct(),
                eq.getMaxDrawdownPct(), eq.winRate(), eq.getTotalTrades()));

        // --- All options strategies ---
        OptionsBacktestEngine optEngine = new OptionsBacktestEngine(
                new IndicatorEngine(), new BlackScholesEngine(), new FeeCalculator());
        for (OptionsStrategy s : OptionsStrategy.values()) {
            BacktestResult r = optEngine.run(s, cfg, bars);
            rows.add(new Row(s.getDisplayName(), r.getTotalReturnPct(),
                    r.getMaxDrawdownPct(), r.winRate(), r.getTotalTrades()));
        }

        // Sort by risk-adjusted return (Return / MaxDrawdown), descending
        rows.sort(Comparator.comparingDouble(Row::rr).reversed());

        // Print table
        String fmt = "  %-22s  %+8.1f%%  %8.1f%%  %8.2f  %7.1f%%  %6d%n";
        System.out.printf("  %-22s  %8s  %8s  %8s  %7s  %6s%n",
                "Strategy", "Return", "MaxDD", "Ret/Risk", "WinRate", "Trades");
        System.out.println("  " + "-".repeat(68));
        for (Row r : rows) {
            System.out.printf(fmt, r.name(), r.ret(), r.dd(), r.rr(), r.wr(), r.trades());
        }
        System.out.println("  " + "-".repeat(68));
        System.out.println();
        System.out.printf("  Ret/Risk = Return%% / MaxDrawdown%% (higher = better risk-adjusted)%n");
        System.out.printf("  Best strategy: %s%n", rows.get(0).name());
        System.out.println("=".repeat(72));
    }
}
