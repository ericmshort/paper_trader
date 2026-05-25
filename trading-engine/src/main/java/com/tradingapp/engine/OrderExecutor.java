package com.tradingapp.engine;

import com.tradingapp.account.*;

public class OrderExecutor {

    // Simulates crossing the bid/ask spread on a market buy order
    static final double STOCK_BUY_SLIPPAGE = 0.02;

    private final Account account;
    private final SafetyStop safetyStop;
    private final TransactionLog log;
    private final FeeCalculator fees;

    public OrderExecutor(Account account, SafetyStop safetyStop, TransactionLog log, FeeCalculator fees) {
        this.account = account;
        this.safetyStop = safetyStop;
        this.log = log;
        this.fees = fees;
    }

    public Account getAccount() {
        return account;
    }

    public TransactionRecord buy(String symbol, int shares, double price, String signals, String reason) {
        return buy(symbol, shares, price, signals, reason, null);
    }

    public TransactionRecord buy(String symbol, int shares, double price, String signals, String reason, String features) {
        if (safetyStop.check() || account.isTradingHalted()) return null;
        double fillPrice = price + STOCK_BUY_SLIPPAGE;
        double fee = fees.calculateFee(shares);
        double totalCost = shares * fillPrice + fee;
        if (account.getBalance() < totalCost) return null;
        account.setBalance(account.getBalance() - totalCost);
        account.addOrUpdatePosition(symbol, shares, fillPrice, Position.PositionType.STOCK);
        TransactionRecord r = new TransactionRecord(symbol, TransactionRecord.TransactionAction.BUY,
                shares, fillPrice, fee, account.getBalance(), reason, signals);
        r.setFeatures(features);
        log.insert(r);
        return r;
    }

    public TransactionRecord sell(String symbol, int shares, double price, String signals, String reason) {
        if (safetyStop.check()) return null;
        double fee = fees.calculateFee(shares);
        double proceeds = shares * price - fee;
        Position pos = account.getPositions().get(symbol);
        if (pos != null) {
            account.addRealizedPnL((price - pos.getAverageCost()) * shares - fee);
        }
        account.setBalance(account.getBalance() + proceeds);
        account.removePosition(symbol);
        TransactionRecord r = new TransactionRecord(symbol, TransactionRecord.TransactionAction.SELL,
                shares, price, fee, account.getBalance(), reason, signals);
        log.insert(r);
        return r;
    }
}
