package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionRecord;

public class SimulatedBroker implements BrokerClient {

    private final OrderExecutor orderExecutor;

    public SimulatedBroker(OrderExecutor orderExecutor) {
        this.orderExecutor = orderExecutor;
    }

    public OrderExecutor getOrderExecutor() { return orderExecutor; }

    @Override
    public TransactionRecord submitBuy(String symbol, int shares, double price, String signals, String reason, String features) {
        return orderExecutor.buy(symbol, shares, price, signals, reason, features);
    }

    @Override
    public TransactionRecord submitSell(String symbol, int shares, double price, String signals, String reason) {
        return orderExecutor.sell(symbol, shares, price, signals, reason);
    }

    @Override
    public void syncAccount(Account account) {
        // local account is already authoritative in simulated mode
    }

    @Override
    public String getName() { return "Simulated"; }
}
