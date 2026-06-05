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
 * Full strategy comparison over multiple time windows.
 * Run via: mvn test -pl trading-ui -Dtest=CoveredCallBacktestRunner#run -q
 */
public class CoveredCallBacktestRunner {

    record Row(String name, double ret, double dd, double wr, int trades) {
        double rr() { return dd > 0 ? ret / dd : 0; }
    }

    @org.junit.jupiter.api.Test
    public void run() throws Exception {
        List<String> symbols = List.of(
                "AAPL", "MSFT", "NVDA", "META", "GOOGL",
                "AMZN", "JPM", "UNH", "XOM", "V",
                "SPY", "QQQ"
        );
        double startingBalance = 100_000.0;
        LocalDate end = LocalDate.now().minusDays(1);

        YahooFinanceQuoteProvider yf = new YahooFinanceQuoteProvider();

        // Run two windows: 5-year and 10-year (covers multiple market regimes)
        for (int years : new int[]{5, 10}) {
            LocalDate start = end.minusYears(years);

            System.out.println("\n" + "=".repeat(76));
            System.out.printf("  All-Strategy Comparison  |  %d-year window: %s → %s%n", years, start, end);
            System.out.printf("  Symbols: %s%n", symbols);
            System.out.printf("  Capital: $%,.0f%n", startingBalance);
            System.out.println("=".repeat(76));
            System.out.println("  Fetching data...");

            Map<String, List<HistoricalBar>> bars = new LinkedHashMap<>();
            for (String sym : symbols) {
                List<HistoricalBar> b = yf.getHistoricalBars(sym, start, end);
                if (!b.isEmpty()) bars.put(sym, b);
            }
            int tradingDays = bars.values().stream().mapToInt(List::size).max().orElse(0);
            System.out.printf("  Loaded %d symbols, ~%d trading days each%n%n", bars.size(), tradingDays);

            BacktestConfig cfg = new BacktestConfig(new ArrayList<>(bars.keySet()), start, end, startingBalance);
            OptionsBacktestEngine optEngine = new OptionsBacktestEngine(
                    new IndicatorEngine(), new BlackScholesEngine(), new FeeCalculator());

            List<Row> rows = new ArrayList<>();

            // Equity baseline
            BacktestResult eq = new BacktestEngine(new IndicatorEngine(), new FeeCalculator()).run(cfg, bars);
            rows.add(new Row("Equity (signals)", eq.getTotalReturnPct(),
                    eq.getMaxDrawdownPct(), eq.winRate(), eq.getTotalTrades()));

            // All options strategies
            for (OptionsStrategy s : OptionsStrategy.values()) {
                BacktestResult r = optEngine.run(s, cfg, bars);
                rows.add(new Row(s.getDisplayName(), r.getTotalReturnPct(),
                        r.getMaxDrawdownPct(), r.winRate(), r.getTotalTrades()));
            }

            // Sort by risk-adjusted return (descending)
            rows.sort(Comparator.comparingDouble(Row::rr).reversed());

            String fmt = "  %-26s  %+8.1f%%  %8.1f%%  %8.2f  %7.1f%%  %6d%n";
            System.out.printf("  %-26s  %8s  %8s  %8s  %7s  %6s%n",
                    "Strategy", "Return", "MaxDD", "Ret/Risk", "WinRate", "Trades");
            System.out.println("  " + "-".repeat(74));
            for (Row r : rows) {
                System.out.printf(fmt, r.name(), r.ret(), r.dd(), r.rr(), r.wr(), r.trades());
            }
            System.out.println("  " + "-".repeat(74));
            System.out.printf("  Sorted by Ret/Risk (Return%% / MaxDrawdown%%) — higher = better%n");
            System.out.printf("  Winner: %s%n", rows.get(0).name());
        }
        System.out.println("=".repeat(76));
    }
}
