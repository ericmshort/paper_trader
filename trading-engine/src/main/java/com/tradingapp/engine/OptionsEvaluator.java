package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

public interface OptionsEvaluator {

    /**
     * Simple entry point — kept for backward compat. Override {@link #evaluateWithSignals} instead
     * when you need access to the raw signal list (required for name-based signal checks).
     */
    default void evaluate(String symbol, double price, int buySignals, int sellSignals,
                          String signalStr, String featureCsv) {}

    /**
     * Primary entry point. Default delegates to {@link #evaluate} so legacy implementations
     * continue to work unchanged. Override this when you need per-indicator signal details.
     */
    default void evaluateWithSignals(String symbol, double price, int buySignals, int sellSignals,
                                     String signalStr, String featureCsv,
                                     java.util.List<SignalResult> rawSignals) {
        evaluate(symbol, price, buySignals, sellSignals, signalStr, featureCsv);
    }

    /**
     * Called once at backtest startup to wire the shared transaction log, account,
     * price history, and virtual clock into the evaluator.
     */
    default void onBacktestInit(TransactionLog sharedLog, Account sharedAccount,
                                PriceHistory sharedHistory, Supplier<ZonedDateTime> clock) {}

    /** Called at the start of each simulated trading day to reset intraday state. */
    default void resetForDay(LocalDate date) {}

    /**
     * Mark all open options positions for this symbol to their current Black-Scholes value.
     * Called by TradingLoop before the daily loss limit check so the check uses fresh option
     * prices rather than values from the previous tick.
     */
    default void markPositionsToMarket(String symbol, double price) {}
}
