package com.tradingapp.ui;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.LargeCapWatchList;
import com.tradingapp.data.YahooFinanceQuoteProvider;
import com.tradingapp.engine.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

/**
 * Backtests the RSI Momentum strategy (BUY RSI<30, SELL RSI>70, 2% trailing stop)
 * across all 50 large-cap symbols. Each symbol runs independently with $100k capital.
 * Run via: mvn test -pl trading-ui -Dtest=RSIMomentumBacktestRunner#run -q
 */
public class RSIMomentumBacktestRunner {

    record Row(String symbol, double ret, double dd, double wr, int trades) {
        double rr() { return dd > 0 ? ret / dd : 0; }
    }

    @Test
    public void run() throws Exception {
        List<String> symbols = LargeCapWatchList.SYMBOLS;
        LocalDate end   = LocalDate.now().minusDays(1);
        LocalDate start = end.minusYears(1);
        double startingBalance = 100_000.0;

        System.out.println("=".repeat(76));
        System.out.printf("  RSI Momentum Backtest — %d Large-Cap Symbols%n", symbols.size());
        System.out.printf("  Strategy : BUY RSI<30 (oversold) | SELL RSI>70 (overbought) | 2%% trailing stop%n");
        System.out.printf("  Period   : %s → %s%n", start, end);
        System.out.printf("  Capital  : $%,.0f per symbol (independent runs)%n", startingBalance);
        System.out.println("=".repeat(76));
        System.out.println("  Fetching historical data from Yahoo Finance...");
        System.out.println();

        YahooFinanceQuoteProvider yf = new YahooFinanceQuoteProvider();
        RSIMomentumBacktestEngine engine = new RSIMomentumBacktestEngine(new IndicatorEngine(), new FeeCalculator());
        List<Row> rows = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (String symbol : symbols) {
            try {
                List<HistoricalBar> bars = yf.getHistoricalBars(symbol, start, end);
                if (bars.isEmpty()) {
                    failed.add(symbol);
                    System.out.printf("  %-8s  (no data)%n", symbol);
                    continue;
                }
                BacktestConfig cfg = new BacktestConfig(List.of(symbol), start, end, startingBalance);
                BacktestResult r = engine.run(cfg, Map.of(symbol, bars));
                rows.add(new Row(symbol, r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                        r.winRate(), r.getTotalTrades()));
                System.out.printf("  %-8s  %+.1f%%  (%d bars, %d trades)%n",
                        symbol, r.getTotalReturnPct(), bars.size(), r.getTotalTrades());
            } catch (Exception e) {
                failed.add(symbol);
                System.out.printf("  %-8s  FAILED: %s%n", symbol, e.getMessage());
            }
        }

        rows.sort(Comparator.comparingDouble(Row::ret).reversed());

        System.out.println();
        System.out.println("=".repeat(76));
        System.out.printf("  Results — Best to Worst by Total Return%n");
        System.out.println("=".repeat(76));
        System.out.printf("  %-4s  %-8s  %8s  %8s  %8s  %8s  %6s%n",
                "Rank", "Symbol", "Return", "MaxDD", "Ret/Risk", "WinRate", "Trades");
        System.out.println("  " + "-".repeat(70));

        String fmt = "  %-4d  %-8s  %+7.1f%%  %7.1f%%  %8.2f  %7.1f%%  %6d%n";
        int rank = 1;
        for (Row r : rows) {
            System.out.printf(fmt, rank++, r.symbol(), r.ret(), r.dd(), r.rr(), r.wr(), r.trades());
        }
        System.out.println("  " + "-".repeat(70));

        if (!rows.isEmpty()) {
            double avgReturn = rows.stream().mapToDouble(Row::ret).average().orElse(0);
            long profitable = rows.stream().filter(r -> r.ret() > 0).count();
            System.out.printf("%n  Summary:%n");
            System.out.printf("    Symbols tested  : %d%n", rows.size());
            System.out.printf("    Profitable      : %d / %d (%.0f%%)%n",
                    profitable, rows.size(), profitable * 100.0 / rows.size());
            System.out.printf("    Average return  : %+.1f%%%n", avgReturn);
            System.out.printf("    Best  : %-8s  %+.1f%%%n", rows.get(0).symbol(), rows.get(0).ret());
            System.out.printf("    Worst : %-8s  %+.1f%%%n",
                    rows.get(rows.size()-1).symbol(), rows.get(rows.size()-1).ret());
        }
        if (!failed.isEmpty()) {
            System.out.printf("%n  Skipped (%d): %s%n", failed.size(), failed);
        }
        System.out.println("=".repeat(76));
    }
}
