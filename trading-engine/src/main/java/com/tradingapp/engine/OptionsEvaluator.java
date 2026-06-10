package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

@FunctionalInterface
public interface OptionsEvaluator {
    void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr, String featureCsv);

    /**
     * Called once at backtest startup to wire the shared transaction log, account,
     * price history, and virtual clock into the evaluator.
     */
    default void onBacktestInit(TransactionLog sharedLog, Account sharedAccount,
                                PriceHistory sharedHistory, Supplier<ZonedDateTime> clock) {}

    /** Called at the start of each simulated trading day to reset intraday state. */
    default void resetForDay(LocalDate date) {}
}
