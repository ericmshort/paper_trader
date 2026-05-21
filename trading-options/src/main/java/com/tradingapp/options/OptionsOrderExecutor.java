package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.account.TransactionRecord.TransactionAction;

import java.time.LocalDate;
import java.util.Map;

public class OptionsOrderExecutor {

    private static final double CONTRACT_FEE = 0.65;
    // Simulates crossing the bid/ask spread; options use Black-Scholes theoretical prices, not real market bid/ask
    static final double OPTION_BUY_SLIPPAGE = 0.05;

    private final Account account;
    private final TransactionLog transactionLog;
    private final boolean brokerMode;

    public OptionsOrderExecutor(Account account, TransactionLog transactionLog) {
        this(account, transactionLog, false);
    }

    public OptionsOrderExecutor(Account account, TransactionLog transactionLog, boolean brokerMode) {
        this.account = account;
        this.transactionLog = transactionLog;
        this.brokerMode = brokerMode;
    }

    public void buyCall(String symbol, double strike, LocalDate expiry, int contracts,
                        double premium, String signalStr, String featureCsv) {
        double fillPremium = brokerMode ? premium : premium + OPTION_BUY_SLIPPAGE;
        double fee = brokerMode ? 0.0 : CONTRACT_FEE * contracts;
        double totalCost = fillPremium * 100 * contracts + fee;
        if (account.getBalance() < totalCost) return;
        account.setBalance(account.getBalance() - totalCost);
        OptionsPosition pos = new OptionsPosition(symbol, "CALL", strike, expiry, contracts, fillPremium);
        account.addOptionsPosition(symbol + "_CALL", pos);
        TransactionRecord rec = new TransactionRecord(symbol, TransactionAction.CALL_BUY,
                contracts, fillPremium, fee, account.getBalance(), "CALL K=" + strike + " exp=" + expiry, signalStr);
        rec.setFeatures(featureCsv);
        transactionLog.insert(rec);
    }

    public void buyPut(String symbol, double strike, LocalDate expiry, int contracts,
                       double premium, String signalStr, String featureCsv) {
        double fillPremium = brokerMode ? premium : premium + OPTION_BUY_SLIPPAGE;
        double fee = brokerMode ? 0.0 : CONTRACT_FEE * contracts;
        double totalCost = fillPremium * 100 * contracts + fee;
        if (account.getBalance() < totalCost) return;
        account.setBalance(account.getBalance() - totalCost);
        OptionsPosition pos = new OptionsPosition(symbol, "PUT", strike, expiry, contracts, fillPremium);
        account.addOptionsPosition(symbol + "_PUT", pos);
        TransactionRecord rec = new TransactionRecord(symbol, TransactionAction.PUT_BUY,
                contracts, fillPremium, fee, account.getBalance(), "PUT K=" + strike + " exp=" + expiry, signalStr);
        rec.setFeatures(featureCsv);
        transactionLog.insert(rec);
    }

    public void closePosition(String positionKey, double currentPremium, String reason) {
        Map<String, OptionsPosition> opts = account.getOptionsPositions();
        OptionsPosition pos = opts.get(positionKey);
        if (pos == null) return;
        double proceeds = currentPremium * 100 * pos.getContracts();
        double fee = brokerMode ? 0.0 : CONTRACT_FEE * pos.getContracts();
        double net = proceeds - fee;
        account.setBalance(account.getBalance() + net);
        account.removeOptionsPosition(positionKey);
        double pnl = net - pos.getPremiumPaid() * 100 * pos.getContracts();
        account.addRealizedPnL(pnl);
        TransactionAction action = pos.getType().equals("CALL") ? TransactionAction.CALL_SELL : TransactionAction.PUT_SELL;
        TransactionRecord rec = new TransactionRecord(pos.getSymbol(), action,
                pos.getContracts(), currentPremium, fee, account.getBalance(), reason, "");
        transactionLog.insert(rec);
    }
}
