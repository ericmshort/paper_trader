package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.account.TransactionRecord.TransactionAction;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class OptionsOrderExecutor {

    private static final Logger LOG = Logger.getLogger(OptionsOrderExecutor.class.getName());
    private static final double CONTRACT_FEE = 0.65;
    static final double OPTION_BUY_SLIPPAGE  = 0.05;
    static final double OPTION_SELL_SLIPPAGE = 0.02; // haircut on credit received

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

    // Short-option entry (credit spread legs): receives credit upfront, stored with negative contracts.
    // closePosition() handles buy-to-close correctly when contracts < 0.
    public void sellCallAs(String positionKey, String symbol, double strike, LocalDate expiry,
                           int contracts, double premium, String signalStr, String featureCsv) {
        sell(positionKey, symbol, "CALL", strike, expiry, contracts, premium, signalStr, featureCsv, TransactionAction.CALL_SELL);
    }

    public void sellPutAs(String positionKey, String symbol, double strike, LocalDate expiry,
                          int contracts, double premium, String signalStr, String featureCsv) {
        sell(positionKey, symbol, "PUT", strike, expiry, contracts, premium, signalStr, featureCsv, TransactionAction.PUT_SELL);
    }

    /**
     * Opens two long option legs atomically (straddle / strangle / zero-DTE).
     *
     * When the submitter supports multi-leg orders ({@code submitMultiLeg} returns non-null) the
     * two legs are sent as a single all-or-nothing broker order, eliminating the need for rollback.
     * Otherwise falls back to sequential single-leg submission with automatic rollback if either
     * leg is rejected.
     *
     * @return true if both legs opened, false if the trade was skipped or rolled back
     */
    public boolean openBuyPair(
            String callPosKey, String putPosKey,
            String symbol,
            double callStrike, double putStrike,
            LocalDate callExpiry, LocalDate putExpiry,
            int contracts,
            double callPremium, double putPremium,
            String signalStr, String featureCsv,
            String strategyName) {

        String groupId = UUID.randomUUID().toString();

        // ── Attempt atomic multi-leg submission ───────────────────────────────
        if (submitter != null) {
            List<MultiLegOrder> legs = List.of(
                    new MultiLegOrder(symbol, "CALL", callStrike, callExpiry, "buy", "buy_to_open"),
                    new MultiLegOrder(symbol, "PUT",  putStrike,  putExpiry,  "buy", "buy_to_open"));
            String orderId = submitter.submitMultiLeg(legs, contracts);
            if (orderId != null) {
                double totalCost = (callPremium + putPremium) * 100 * contracts;
                if (account.getBalance() < totalCost) return false;
                account.setBalance(account.getBalance() - totalCost);
                account.addOptionsPosition(callPosKey,
                        new OptionsPosition(symbol, "CALL", callStrike, callExpiry, contracts, callPremium));
                account.addOptionsPosition(putPosKey,
                        new OptionsPosition(symbol, "PUT", putStrike, putExpiry, contracts, putPremium));
                logRecord(symbol, TransactionAction.CALL_BUY, contracts, callPremium, 0.0,
                        strategyName + " CALL K=" + callStrike + " exp=" + callExpiry,
                        signalStr, featureCsv, orderId, groupId);
                logRecord(symbol, TransactionAction.PUT_BUY, contracts, putPremium, 0.0,
                        strategyName + " PUT K=" + putStrike + " exp=" + putExpiry,
                        signalStr, featureCsv, orderId, groupId);
                return true;
            }
            // Multi-leg not supported or rejected — fall through to sequential
        }

        // ── Sequential fallback with rollback ─────────────────────────────────
        buyCallAs(callPosKey, symbol, callStrike, callExpiry, contracts, callPremium, signalStr, featureCsv);
        buyPutAs(putPosKey,   symbol, putStrike,  putExpiry,  contracts, putPremium,  signalStr, featureCsv);

        boolean callOpened = account.getOptionsPositions().containsKey(callPosKey);
        boolean putOpened  = account.getOptionsPositions().containsKey(putPosKey);

        if (callOpened && !putOpened) {
            closePosition(callPosKey, callPremium, "Rollback: put leg rejected");
            return false;
        }
        if (!callOpened && putOpened) {
            closePosition(putPosKey, putPremium, "Rollback: call leg rejected");
            return false;
        }
        // Tag both legs with the same groupId so the UI can combine them
        if (callOpened) tagLastTwoRecords(groupId);
        return callOpened;
    }

    /**
     * Opens a credit spread atomically (one sell-to-open short leg + one buy-to-open long leg).
     *
     * When the submitter supports multi-leg orders the two legs are sent as a single all-or-nothing
     * broker order. Otherwise falls back to sequential submission with automatic rollback.
     *
     * @param optionType "CALL" (bear call spread) or "PUT" (bull put spread)
     * @return true if both legs opened, false if the trade was skipped or rolled back
     */
    public boolean openCreditSpread(
            String shortPosKey, String longPosKey,
            String symbol, String optionType,
            double shortStrike, double longStrike,
            LocalDate expiry,
            int contracts,
            double shortPremium, double longPremium,
            String signalStr, String featureCsv,
            String strategyName) {

        String groupId = UUID.randomUUID().toString();

        // ── Attempt atomic multi-leg submission ───────────────────────────────
        if (submitter != null) {
            List<MultiLegOrder> legs = List.of(
                    new MultiLegOrder(symbol, optionType, shortStrike, expiry, "sell", "sell_to_open"),
                    new MultiLegOrder(symbol, optionType, longStrike,  expiry, "buy",  "buy_to_open"));
            String orderId = submitter.submitMultiLeg(legs, contracts);
            if (orderId != null) {
                double netCredit = (shortPremium - longPremium) * 100 * contracts;
                account.setBalance(account.getBalance() + netCredit);
                account.addOptionsPosition(shortPosKey,
                        new OptionsPosition(symbol, optionType, shortStrike, expiry, -contracts, shortPremium));
                account.addOptionsPosition(longPosKey,
                        new OptionsPosition(symbol, optionType, longStrike,  expiry,  contracts, longPremium));
                TransactionAction shortAction = "CALL".equals(optionType)
                        ? TransactionAction.CALL_SELL : TransactionAction.PUT_SELL;
                TransactionAction longAction = "CALL".equals(optionType)
                        ? TransactionAction.CALL_BUY : TransactionAction.PUT_BUY;
                logRecord(symbol, shortAction, contracts, shortPremium, 0.0,
                        strategyName + " " + optionType + " K=" + shortStrike + " exp=" + expiry + " (SHORT)",
                        signalStr, featureCsv, orderId, groupId);
                logRecord(symbol, longAction,  contracts, longPremium,  0.0,
                        strategyName + " " + optionType + " K=" + longStrike  + " exp=" + expiry + " (LONG)",
                        signalStr, featureCsv, orderId, groupId);
                return true;
            }
            // Multi-leg not supported or rejected — fall through to sequential
        }

        // ── Sequential fallback with rollback ─────────────────────────────────
        if ("CALL".equals(optionType)) {
            sellCallAs(shortPosKey, symbol, shortStrike, expiry, contracts, shortPremium, signalStr, featureCsv);
            buyCallAs(longPosKey,   symbol, longStrike,  expiry, contracts, longPremium,  signalStr, featureCsv);
        } else {
            sellPutAs(shortPosKey, symbol, shortStrike, expiry, contracts, shortPremium, signalStr, featureCsv);
            buyPutAs(longPosKey,   symbol, longStrike,  expiry, contracts, longPremium,  signalStr, featureCsv);
        }

        boolean shortOpened = account.getOptionsPositions().containsKey(shortPosKey);
        boolean longOpened  = account.getOptionsPositions().containsKey(longPosKey);

        if (shortOpened && !longOpened) {
            closePosition(shortPosKey, shortPremium, "Rollback: long leg rejected");
            return false;
        }
        if (!shortOpened && longOpened) {
            closePosition(longPosKey, longPremium, "Rollback: short leg rejected");
            return false;
        }
        if (shortOpened) tagLastTwoRecords(groupId);
        return shortOpened;
    }

    private void sell(String posKey, String symbol, String optionType, double strike, LocalDate expiry, int contracts,
                      double bsPremium, String signalStr, String featureCsv, TransactionAction action) {
        double fillPremium = bsPremium - OPTION_SELL_SLIPPAGE;
        if (fillPremium <= 0) return;
        double fee = CONTRACT_FEE * contracts;
        double creditReceived = fillPremium * 100 * contracts - fee;
        account.setBalance(account.getBalance() + creditReceived);

        // Negative contracts marks this as a short position; closePosition math stays correct.
        OptionsPosition pos = new OptionsPosition(symbol, optionType, strike, expiry, -contracts, fillPremium);
        account.addOptionsPosition(posKey, pos);

        TransactionRecord rec = new TransactionRecord(symbol, action, contracts, fillPremium, fee,
                account.getBalance(), optionType + " K=" + strike + " exp=" + expiry + " (SHORT)", signalStr);
        rec.setFeatures(featureCsv);
        try {
            transactionLog.insert(rec);
        } catch (Exception e) {
            LOG.warning("Options short order placed but failed to log: " + e.getMessage());
        }
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
        OptionsPosition pos = account.getOptionsPositions().get(positionKey);
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

        recordClose(positionKey, pos, currentPremium, fee, reason, externalId, null);
    }

    /**
     * Closes two long option legs atomically (straddle / strangle / zero-DTE).
     * Attempts a single multi-leg sell_to_close order; falls back to sequential
     * close if atomic submission is unsupported or rejected.
     */
    public void closeBuyPair(String callPosKey, String putPosKey,
                             double callPremium, double putPremium,
                             String reason) {
        OptionsPosition callPos = account.getOptionsPositions().get(callPosKey);
        OptionsPosition putPos  = account.getOptionsPositions().get(putPosKey);
        if (callPos == null || putPos == null) return;

        String groupId = UUID.randomUUID().toString();

        // ── Attempt atomic multi-leg close ────────────────────────────────────
        if (submitter != null) {
            List<MultiLegOrder> legs = List.of(
                    new MultiLegOrder(callPos.getSymbol(), "CALL", callPos.getStrike(), callPos.getExpiry(), "sell", "sell_to_close"),
                    new MultiLegOrder(putPos.getSymbol(),  "PUT",  putPos.getStrike(),  putPos.getExpiry(),  "sell", "sell_to_close"));
            String orderId = submitter.submitMultiLeg(legs, callPos.getContracts());
            if (orderId != null) {
                recordClose(callPosKey, callPos, callPremium, 0.0, reason, orderId, groupId);
                recordClose(putPosKey,  putPos,  putPremium,  0.0, reason, orderId, groupId);
                return;
            }
        }

        // ── Sequential fallback ───────────────────────────────────────────────
        closePosition(callPosKey, callPremium, reason);
        closePosition(putPosKey,  putPremium,  reason);
        tagLastTwoRecords(groupId);
    }

    /**
     * Closes a credit spread atomically (buy_to_close the short leg + sell_to_close the long leg).
     * Falls back to sequential close if atomic submission is unsupported or rejected.
     */
    public void closeCreditSpread(String shortPosKey, String longPosKey,
                                  double shortPremium, double longPremium,
                                  String reason) {
        OptionsPosition shortPos = account.getOptionsPositions().get(shortPosKey);
        OptionsPosition longPos  = account.getOptionsPositions().get(longPosKey);
        if (shortPos == null || longPos == null) return;

        int contracts = Math.abs(shortPos.getContracts());
        String groupId = UUID.randomUUID().toString();

        // ── Attempt atomic multi-leg close ────────────────────────────────────
        if (submitter != null) {
            List<MultiLegOrder> legs = List.of(
                    new MultiLegOrder(shortPos.getSymbol(), shortPos.getType(), shortPos.getStrike(), shortPos.getExpiry(), "buy",  "buy_to_close"),
                    new MultiLegOrder(longPos.getSymbol(),  longPos.getType(),  longPos.getStrike(),  longPos.getExpiry(),  "sell", "sell_to_close"));
            String orderId = submitter.submitMultiLeg(legs, contracts);
            if (orderId != null) {
                recordClose(shortPosKey, shortPos, shortPremium, 0.0, reason, orderId, groupId);
                recordClose(longPosKey,  longPos,  longPremium,  0.0, reason, orderId, groupId);
                return;
            }
        }

        // ── Sequential fallback ───────────────────────────────────────────────
        closePosition(shortPosKey, shortPremium, reason);
        closePosition(longPosKey,  longPremium,  reason);
        tagLastTwoRecords(groupId);
    }

    /** Updates account state and logs a single leg close. Does not submit to broker. */
    private void recordClose(String posKey, OptionsPosition pos, double currentPremium,
                             double fee, String reason, String externalId, String groupId) {
        double proceeds = currentPremium * 100 * pos.getContracts();
        double net = proceeds - fee;
        account.setBalance(account.getBalance() + net);
        account.removeOptionsPosition(posKey);
        double pnl = net - pos.getPremiumPaid() * 100 * pos.getContracts();
        account.addRealizedPnL(pnl);

        TransactionAction action = pos.getType().equals("CALL") ? TransactionAction.CALL_SELL : TransactionAction.PUT_SELL;
        TransactionRecord rec = new TransactionRecord(pos.getSymbol(), action, pos.getContracts(),
                currentPremium, fee, account.getBalance(), reason, "");
        rec.setExternalId(externalId);
        rec.setGroupId(groupId);
        try {
            transactionLog.insert(rec);
        } catch (Exception e) {
            LOG.warning("Options close placed but failed to log: " + e.getMessage());
        }
    }

    private void logRecord(String symbol, TransactionAction action, int contracts, double premium,
                           double fee, String reason, String signalStr, String featureCsv,
                           String externalId, String groupId) {
        TransactionRecord rec = new TransactionRecord(symbol, action, contracts, premium, fee,
                account.getBalance(), reason, signalStr);
        rec.setFeatures(featureCsv);
        rec.setExternalId(externalId);
        rec.setGroupId(groupId);
        try {
            transactionLog.insert(rec);
        } catch (Exception e) {
            LOG.warning("Options order placed but failed to log: " + e.getMessage());
        }
    }

    /**
     * Tags the two most-recently inserted records in the transaction log with the given groupId.
     * Used by the sequential fallback path so both legs are still linkable in the UI even when
     * they were submitted as individual single-leg orders.
     */
    private void tagLastTwoRecords(String groupId) {
        try {
            List<TransactionRecord> recent = transactionLog.findAll();
            int tagged = 0;
            for (TransactionRecord r : recent) {
                if (r.getGroupId() == null) {
                    r.setGroupId(groupId);
                    transactionLog.updateGroupId(r.getId(), groupId);
                    if (++tagged == 2) break;
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to tag multi-leg records with groupId: " + e.getMessage());
        }
    }
}
