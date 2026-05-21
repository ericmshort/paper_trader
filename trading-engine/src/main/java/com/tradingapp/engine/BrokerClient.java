package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionRecord;

public interface BrokerClient {
    TransactionRecord submitBuy(String symbol, int shares, double price, String signals, String reason, String features);
    TransactionRecord submitSell(String symbol, int shares, double price, String signals, String reason);
    void syncAccount(Account account);
    String getName();
}
