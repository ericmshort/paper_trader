package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.account.TransactionRecord.TransactionAction;

import java.time.LocalDate;
import java.util.Map;
import java.util.logging.Logger;

public class OptionsOrderExecutor {

    private static final Logger LOG = Logger.getLogger(OptionsOrderExecutor.class.getName());
    private static final double CONTRACT_FEE = 0.65;
    static final double OPTION_BUY_SLIPPAGE = 0.05;

    private final Account account;
    private final TransactionLog transactionLog;
    private final OptionsSubmitter submitter;

    public OptionsOrderExecutor(Account account, TransactionLog transactionLog) {
        this(account, transactionLog, (OptionsSubmitter) null);
    }

    public OptionsOrderExecutor(Account account, TransactionLog transactionLog, OptionsSubmitter submitter) {
        this.account = account;
        this.transactionLog = transactionLog;
        this.submitter = submitter;
    }

    public void buyCall(String symbol, double strike, LocalDate expiry, int contracts,
                        double premium, String signalStr, String featureCsv) {
        buy(symbol + "_CALL", symbol, "CALL", strike, expiry, contracts, premium, signalStr, featureCsv, TransactionAction.CALL_BUY);
    }

    public void buyPut(String symbol, double strike, LocalDate expiry, int contracts,
                       double premium, String signalStr, String featureCsv) {
        buy(symbol + "_PUT", symbol, "PUT", strike, expiry, contracts, premium, signalStr, featureCsv, TransactionAction.PUT_BUY);
    }

    // Used for multi-leg strategies (straddle, strangle) that need a unique position key per leg
    public void buyCallAs(String positionKey, String symbol, double strike, LocalDate expiry,
                          int contracts, double premium, String signalStr, String featureCsv) {
        buy(positionKey, symbol, "CALL", strike, expiry, contracts, premium, signalStr, featureCsv, TransactionAction.CALL_BUY);
    }

    public void buyPutAs(String positionKey, String symbol, double strike, LocalDate expiry,
                         int contracts, double premium, String signalStr, String featureCsv) {
        buy(positionKey, symbol, "PUT", strike, expiry, contracts, premium, signalStr, featureCsv, TransactionAction.PUT_BUY);
    }

    private void buy(String posKey, String symbol, String optionType, double strike, LocalDate expiry, int contracts,
                     double bsPremium, String signalStr, String featureCsv, TransactionAction action) {
        String externalId = null;
        double fillPremium;
        double fee;

        if (submitter != null) {
            externalId = submitter.submit(symbol, optionType, strike, expiry, contracts, "buy");
            if (externalId == null) return; // broker rejected the order
            fillPremium = bsPremium; // Alpaca market orders fill at market; use BS as display price
            fee = 0.0;
        } else {
            fillPremium = bsPremium + OPTION_BUY_SLIPPAGE;
            fee = CONTRACT_FEE * contracts;
        }

        double totalCost = fillPremium * 100 * contracts + fee;
        if (account.getBalance() < totalCost) return;
        account.setBalance(account.getBalance() - totalCost);

        OptionsPosition pos = new OptionsPosition(symbol, optionType, strike, expiry, contracts, fillPremium);
        account.addOptionsPosition(posKey, pos);

        TransactionRecord rec = new TransactionRecord(symbol, action, contracts, fillPremium, fee,
                account.getBalance(), optionType + " K=" + strike + " exp=" + expiry, signalStr);
        rec.setFeatures(featureCsv);
        rec.setExternalId(externalId);
        try {
            transactionLog.insert(rec);
        } catch (Exception e) {
            LOG.warning("Options order placed but failed to log: " + e.getMessage());
        }
    }

    public void closePosition(String positionKey, double currentPremium, String reason) {
        Map<String, OptionsPosition> opts = account.getOptionsPositions();
        OptionsPosition pos = opts.get(positionKey);
        if (pos == null) return;

        String externalId = null;
        double fee;

        if (submitter != null) {
            String side = "sell";
            externalId = submitter.submit(pos.getSymbol(), pos.getType(), pos.getStrike(), pos.getExpiry(),
                    pos.getContracts(), side);
            if (externalId == null) return; // broker rejected
            fee = 0.0;
        } else {
            fee = CONTRACT_FEE * pos.getContracts();
        }

        double proceeds = currentPremium * 100 * pos.getContracts();
        double net = proceeds - fee;
        account.setBalance(account.getBalance() + net);
        account.removeOptionsPosition(positionKey);
        double pnl = net - pos.getPremiumPaid() * 100 * pos.getContracts();
        account.addRealizedPnL(pnl);

        TransactionAction action = pos.getType().equals("CALL") ? TransactionAction.CALL_SELL : TransactionAction.PUT_SELL;
        TransactionRecord rec = new TransactionRecord(pos.getSymbol(), action, pos.getContracts(),
                currentPremium, fee, account.getBalance(), reason, "");
        rec.setExternalId(externalId);
        try {
            transactionLog.insert(rec);
        } catch (Exception e) {
            LOG.warning("Options close placed but failed to log: " + e.getMessage());
        }
    }
}
