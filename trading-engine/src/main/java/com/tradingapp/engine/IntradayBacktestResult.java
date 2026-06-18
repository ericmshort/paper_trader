package com.tradingapp.engine;

import com.tradingapp.account.TransactionRecord;

import java.util.List;

public class IntradayBacktestResult extends BacktestResult {

    private final List<String> eventLog;
    private final double finalBalance;
    private final List<TransactionRecord> trades;

    public IntradayBacktestResult(List<BacktestDataPoint> equityCurve, double totalReturnPct,
                                  double maxDrawdownPct, int wins, int losses, int totalTrades,
                                  List<String> eventLog, double finalBalance,
                                  List<TransactionRecord> trades) {
        super(equityCurve, totalReturnPct, maxDrawdownPct, wins, losses, totalTrades);
        this.eventLog = eventLog;
        this.finalBalance = finalBalance;
        this.trades = trades;
    }

    public List<String> getEventLog() { return eventLog; }
    public double getFinalBalance() { return finalBalance; }
    public List<TransactionRecord> getTrades() { return trades; }
}
