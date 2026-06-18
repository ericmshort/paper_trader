package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.account.TransactionRecord.TransactionAction;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class OptionsOrderExecutor {

    private static final Logger LOG = Logger.getLogger(OptionsOrderExecutor.class.getName());
    private static final double CONTRACT_FEE = 0.65;
    static final double OPTION_BUY_SLIPPAGE  = 0.05;
    static final double OPTION_SELL_SLIPPAGE = 0.02; // haircut on credit received

    private Account account;
    private TransactionLog transactionLog;
    private final OptionsSubmitter submitter;
    private java.util.function.LongSupplier clockMs = System::currentTimeMillis;

    // Tracks stock legs of open covered-call positions (keyed by posKey)
    private final Map<String, Integer> coveredCallStockShares = new HashMap<>();
    private final Map<String, Double> coveredCallStockEntries = new HashMap<>();

    public OptionsOrderExecutor(Account account, TransactionLog transactionLog) {
        this(account, transactionLog, (OptionsSubmitter) null);
    }

    public OptionsOrderExecutor(Account account, TransactionLog transactionLog, OptionsSubmitter submitter) {
        this.account = account;
        this.transactionLog = transactionLog;
        this.submitter = submitter;
    }

    public void setTransactionLog(TransactionLog log) { this.transactionLog = log; }
    public void setAccount(Account account) { this.account = account; }
    public void setClock(java.util.function.LongSupplier c) { this.clockMs = c; }
    public int closeAllFromBroker() { return submitter != null ? submitter.closeAllOptionsPositions() : -1; }

    private TransactionRecord ts(TransactionRecord r) { r.setTimestamp(clockMs.getAsLong()); return r; }

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

        // Pre-flight: ensure we can afford both legs before touching the account.
        // Uses the paper-trading cost (slippage + fees) as the conservative floor.
        double totalRequired = (callPremium + putPremium + 2 * OPTION_BUY_SLIPPAGE) * 100 * contracts
                + 2 * CONTRACT_FEE * contracts;
        if (account.getBalance() < totalRequired) {
            LOG.warning(symbol + " buy-pair skip: insufficient balance "
                    + String.format("%.2f < %.2f required", account.getBalance(), totalRequired));
            return false;
        }

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
            // MLEG rejected by broker — do not fall back to individual legs
            LOG.warning(symbol + " buy-pair rejected by broker; not attempting individual legs");
            return false;
        }

        // ── Paper-trading: sequential (no broker, so no MLEG support) ─────────
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

        // Pre-flight: for credit spreads the net is a credit, but verify the long leg
        // can be funded from current balance alone (conservative check).
        double longLegCost = (longPremium + OPTION_BUY_SLIPPAGE) * 100 * contracts
                + CONTRACT_FEE * contracts;
        if (account.getBalance() < longLegCost) {
            LOG.warning(symbol + " credit-spread skip: insufficient balance "
                    + String.format("%.2f < %.2f long-leg cost", account.getBalance(), longLegCost));
            return false;
        }

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
            // MLEG rejected by broker — do not fall back to individual legs
            LOG.warning(symbol + " credit-spread rejected by broker; not attempting individual legs");
            return false;
        }

        // ── Paper-trading: sequential (no broker, so no MLEG support) ─────────
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

        TransactionRecord rec = ts(new TransactionRecord(symbol, action, contracts, fillPremium, fee,
                account.getBalance(), optionType + " K=" + strike + " exp=" + expiry + " (SHORT)", signalStr));
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
            double estimatedCost = bsPremium * 100 * contracts;
            double bp = account.getBuyingPower();
            if (bp > 0 && bp < estimatedCost) {
                LOG.warning(symbol + " " + optionType + " buy skipped: options buying power "
                        + String.format("%.2f", bp) + " < estimated cost " + String.format("%.2f", estimatedCost));
                return;
            }
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

        TransactionRecord rec = ts(new TransactionRecord(symbol, action, contracts, fillPremium, fee,
                account.getBalance(), optionType + " K=" + strike + " exp=" + expiry, signalStr));
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
            // Short positions (contracts < 0) require "buy_to_close"; long require "sell_to_close".
            // Always send a positive qty — Alpaca rejects negative quantities.
            // position_intent is mandatory: without it Alpaca treats the order as a new naked write.
            int qty = Math.abs(pos.getContracts());
            boolean isShort = pos.getContracts() < 0;
            String side = isShort ? "buy" : "sell";
            String positionIntent = isShort ? "buy_to_close" : "sell_to_close";
            // Use the pinned Alpaca OCC symbol when available. Re-looking up by target strike/expiry
            // can resolve a different contract than the one actually held, causing position_intent mismatch.
            String occSymbol = pos.getBrokerOccSymbol();
            if (occSymbol != null) {
                externalId = submitter.submitDirect(occSymbol, qty, side, positionIntent);
            } else {
                externalId = submitter.submit(pos.getSymbol(), pos.getType(), pos.getStrike(), pos.getExpiry(),
                        qty, side, positionIntent);
            }
            if (externalId == null) {
                // Only remove from memory if this position is NOT confirmed by the broker.
                // Broker-verified positions still exist in Alpaca — removing them causes a churn
                // loop where syncAccount re-adds them every tick and we retry the rejection forever.
                if (!account.isOptionVerified(positionKey)) {
                    LOG.warning(positionKey + " close rejected — removing unverified paper position");
                    account.removeOptionsPosition(positionKey);
                } else {
                    LOG.warning(positionKey + " close rejected — position still exists in broker, will retry next tick");
                }
                return;
            }
            fee = 0.0;
        } else {
            fee = CONTRACT_FEE * Math.abs(pos.getContracts());
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
                    new MultiLegOrder(callPos.getSymbol(), "CALL", callPos.getStrike(), callPos.getExpiry(), "sell", "sell_to_close", callPos.getBrokerOccSymbol()),
                    new MultiLegOrder(putPos.getSymbol(),  "PUT",  putPos.getStrike(),  putPos.getExpiry(),  "sell", "sell_to_close", putPos.getBrokerOccSymbol()));
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
                    new MultiLegOrder(shortPos.getSymbol(), shortPos.getType(), shortPos.getStrike(), shortPos.getExpiry(), "buy",  "buy_to_close",  shortPos.getBrokerOccSymbol()),
                    new MultiLegOrder(longPos.getSymbol(),  longPos.getType(),  longPos.getStrike(),  longPos.getExpiry(),  "sell", "sell_to_close", longPos.getBrokerOccSymbol()));
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

    /**
     * Opens an iron condor atomically: short OTM call + long further OTM call (bear call spread)
     * combined with short OTM put + long further OTM put (bull put spread).
     *
     * Net credit is received upfront. Max loss is limited to one spread wing's width.
     * Returns true if all four legs opened; rolls back any partial opens on failure.
     */
    public boolean openIronCondor(
            String shortCallKey, String longCallKey,
            String shortPutKey,  String longPutKey,
            String symbol,
            double shortCallK, double longCallK,
            double shortPutK,  double longPutK,
            LocalDate expiry, int contracts,
            double shortCallPrem, double longCallPrem,
            double shortPutPrem,  double longPutPrem,
            String signalStr, String featureCsv) {

        double netCredit = (shortCallPrem - longCallPrem) + (shortPutPrem - longPutPrem);
        if (netCredit <= 0) return false;

        // Pre-flight: long legs must be fundable (credit from shorts arrives simultaneously)
        double longLegsRequired = (longCallPrem + OPTION_BUY_SLIPPAGE + longPutPrem + OPTION_BUY_SLIPPAGE)
                * 100 * contracts + 4 * CONTRACT_FEE * contracts;
        if (account.getBalance() < longLegsRequired) {
            LOG.warning(symbol + " iron condor skip: insufficient balance "
                    + String.format("%.2f < %.2f required", account.getBalance(), longLegsRequired));
            return false;
        }

        String groupId = UUID.randomUUID().toString();

        // ── Attempt atomic 4-leg submission ──────────────────────────────────
        if (submitter != null) {
            List<MultiLegOrder> legs = List.of(
                    new MultiLegOrder(symbol, "CALL", shortCallK, expiry, "sell", "sell_to_open"),
                    new MultiLegOrder(symbol, "CALL", longCallK,  expiry, "buy",  "buy_to_open"),
                    new MultiLegOrder(symbol, "PUT",  shortPutK,  expiry, "sell", "sell_to_open"),
                    new MultiLegOrder(symbol, "PUT",  longPutK,   expiry, "buy",  "buy_to_open"));
            String orderId = submitter.submitMultiLeg(legs, contracts);
            if (orderId != null) {
                account.setBalance(account.getBalance() + netCredit * 100 * contracts);
                account.addOptionsPosition(shortCallKey, new OptionsPosition(symbol, "CALL", shortCallK, expiry, -contracts, shortCallPrem));
                account.addOptionsPosition(longCallKey,  new OptionsPosition(symbol, "CALL", longCallK,  expiry,  contracts, longCallPrem));
                account.addOptionsPosition(shortPutKey,  new OptionsPosition(symbol, "PUT",  shortPutK,  expiry, -contracts, shortPutPrem));
                account.addOptionsPosition(longPutKey,   new OptionsPosition(symbol, "PUT",  longPutK,   expiry,  contracts, longPutPrem));
                logRecord(symbol, TransactionAction.CALL_SELL, contracts, shortCallPrem, 0.0,
                        "IRON CONDOR CALL K=" + shortCallK + " exp=" + expiry + " (SHORT)", signalStr, featureCsv, orderId, groupId);
                logRecord(symbol, TransactionAction.CALL_BUY,  contracts, longCallPrem,  0.0,
                        "IRON CONDOR CALL K=" + longCallK  + " exp=" + expiry + " (LONG)",  signalStr, featureCsv, orderId, groupId);
                logRecord(symbol, TransactionAction.PUT_SELL,  contracts, shortPutPrem,  0.0,
                        "IRON CONDOR PUT K="  + shortPutK  + " exp=" + expiry + " (SHORT)", signalStr, featureCsv, orderId, groupId);
                logRecord(symbol, TransactionAction.PUT_BUY,   contracts, longPutPrem,   0.0,
                        "IRON CONDOR PUT K="  + longPutK   + " exp=" + expiry + " (LONG)",  signalStr, featureCsv, orderId, groupId);
                return true;
            }
            LOG.warning(symbol + " iron condor rejected by broker; not attempting individual legs");
            return false;
        }

        // ── Paper-trading: sequential ─────────────────────────────────────────
        sellCallAs(shortCallKey, symbol, shortCallK, expiry, contracts, shortCallPrem, signalStr, featureCsv);
        buyCallAs(longCallKey,   symbol, longCallK,  expiry, contracts, longCallPrem,  signalStr, featureCsv);
        sellPutAs(shortPutKey,   symbol, shortPutK,  expiry, contracts, shortPutPrem,  signalStr, featureCsv);
        buyPutAs(longPutKey,     symbol, longPutK,   expiry, contracts, longPutPrem,   signalStr, featureCsv);

        Map<String, OptionsPosition> opts = account.getOptionsPositions();
        boolean allOpened = opts.containsKey(shortCallKey) && opts.containsKey(longCallKey)
                         && opts.containsKey(shortPutKey)  && opts.containsKey(longPutKey);

        if (!allOpened) {
            String rollbackReason = "Rollback: iron condor leg failed";
            if (opts.containsKey(shortCallKey)) closePosition(shortCallKey, shortCallPrem, rollbackReason);
            if (opts.containsKey(longCallKey))  closePosition(longCallKey,  longCallPrem,  rollbackReason);
            if (opts.containsKey(shortPutKey))  closePosition(shortPutKey,  shortPutPrem,  rollbackReason);
            if (opts.containsKey(longPutKey))   closePosition(longPutKey,   longPutPrem,   rollbackReason);
            return false;
        }

        tagLastFourRecords(groupId);
        return true;
    }

    /**
     * Closes all four legs of an iron condor.
     * Attempts atomic 4-leg close via broker; falls back to sequential close.
     */
    public void closeIronCondor(
            String shortCallKey, String longCallKey,
            String shortPutKey,  String longPutKey,
            double shortCallPrem, double longCallPrem,
            double shortPutPrem,  double longPutPrem,
            String reason) {
        Map<String, OptionsPosition> opts = account.getOptionsPositions();
        OptionsPosition scPos = opts.get(shortCallKey);
        OptionsPosition lcPos = opts.get(longCallKey);
        OptionsPosition spPos = opts.get(shortPutKey);
        OptionsPosition lpPos = opts.get(longPutKey);
        if (scPos == null && lcPos == null && spPos == null && lpPos == null) return;

        int contracts = scPos != null ? Math.abs(scPos.getContracts())
                      : lcPos != null ? lcPos.getContracts()
                      : spPos != null ? Math.abs(spPos.getContracts())
                      : lpPos.getContracts();
        String groupId = UUID.randomUUID().toString();

        // ── Attempt atomic 4-leg close ────────────────────────────────────────
        if (submitter != null && scPos != null && lcPos != null && spPos != null && lpPos != null) {
            List<MultiLegOrder> legs = List.of(
                    new MultiLegOrder(scPos.getSymbol(), "CALL", scPos.getStrike(), scPos.getExpiry(), "buy",  "buy_to_close",  scPos.getBrokerOccSymbol()),
                    new MultiLegOrder(lcPos.getSymbol(), "CALL", lcPos.getStrike(), lcPos.getExpiry(), "sell", "sell_to_close", lcPos.getBrokerOccSymbol()),
                    new MultiLegOrder(spPos.getSymbol(), "PUT",  spPos.getStrike(), spPos.getExpiry(), "buy",  "buy_to_close",  spPos.getBrokerOccSymbol()),
                    new MultiLegOrder(lpPos.getSymbol(), "PUT",  lpPos.getStrike(), lpPos.getExpiry(), "sell", "sell_to_close", lpPos.getBrokerOccSymbol()));
            String orderId = submitter.submitMultiLeg(legs, contracts);
            if (orderId != null) {
                recordClose(shortCallKey, scPos, shortCallPrem, 0.0, reason, orderId, groupId);
                recordClose(longCallKey,  lcPos, longCallPrem,  0.0, reason, orderId, groupId);
                recordClose(shortPutKey,  spPos, shortPutPrem,  0.0, reason, orderId, groupId);
                recordClose(longPutKey,   lpPos, longPutPrem,   0.0, reason, orderId, groupId);
                return;
            }
        }

        // ── Sequential fallback ───────────────────────────────────────────────
        if (scPos != null) closePosition(shortCallKey, shortCallPrem, reason);
        if (lcPos != null) closePosition(longCallKey,  longCallPrem,  reason);
        if (spPos != null) closePosition(shortPutKey,  shortPutPrem,  reason);
        if (lpPos != null) closePosition(longPutKey,   longPutPrem,   reason);
        tagLastFourRecords(groupId);
    }

    /** Updates account state and logs a single leg close. Does not submit to broker. */
    private void recordClose(String posKey, OptionsPosition pos, double currentPremium,
                             double fee, String reason, String externalId, String groupId) {
        // In live trading the BS-computed premium may be stale or zero at close time.
        // Use cost basis as a placeholder so the account balance doesn't crater before
        // Alpaca confirms the real fill price (syncAccount will correct it shortly after).
        double accountingPremium = (submitter != null && currentPremium <= 0)
                ? pos.getPremiumPaid()
                : currentPremium;
        double proceeds = accountingPremium * 100 * pos.getContracts();
        double net = proceeds - fee;
        account.setBalance(account.getBalance() + net);
        account.removeOptionsPosition(posKey);
        double pnl = net - pos.getPremiumPaid() * 100 * pos.getContracts();
        account.addRealizedPnL(pnl);

        TransactionAction action = pos.getType().equals("CALL") ? TransactionAction.CALL_SELL : TransactionAction.PUT_SELL;
        TransactionRecord rec = ts(new TransactionRecord(pos.getSymbol(), action, pos.getContracts(),
                currentPremium, fee, account.getBalance(), reason, ""));
        rec.setExternalId(externalId);
        rec.setGroupId(groupId);
        try {
            transactionLog.insert(rec);
        } catch (Exception e) {
            LOG.warning("Options close placed but failed to log: " + e.getMessage());
        }
    }

    // ── Covered Call ──────────────────────────────────────────────────────────

    /**
     * Opens a covered call: buys stock shares and sells one OTM call per 100 shares.
     * Position sizing ignores the standard 5% cap — sized by available capital / share price.
     */
    public void openCoveredCall(String posKey, String symbol, double stockPrice,
                                double callStrike, LocalDate callExpiry, int maxContracts,
                                double callPremium, String signalStr, String featureCsv) {
        int contracts = Math.min(maxContracts, (int) (account.getBalance() / (stockPrice * 100)));
        if (contracts < 1) return;
        int stockShares = contracts * 100;
        double stockCost = stockShares * stockPrice;
        double callCredit = callPremium * 100 * contracts * (1 - OPTION_SELL_SLIPPAGE);
        if (account.getBalance() < stockCost - callCredit) return;

        String stockOrderId = null;
        if (submitter != null) {
            stockOrderId = submitter.buyStock(symbol, stockShares);
            if (stockOrderId == null) {
                LOG.warning("Covered call aborted: broker rejected stock buy for " + symbol);
                return;
            }
        }

        // Sell the call leg
        String callOrderId = null;
        if (submitter != null) {
            callOrderId = submitter.submit(symbol, "CALL", callStrike, callExpiry, contracts, "sell", "sell_to_open");
        }

        account.setBalance(account.getBalance() - stockCost + callCredit);
        account.addOrUpdatePosition(symbol, stockShares, stockPrice, com.tradingapp.account.Position.PositionType.STOCK);
        OptionsPosition callPos = new OptionsPosition(symbol, "CALL", callStrike, callExpiry, -contracts, callPremium);
        account.addOptionsPosition(posKey, callPos);

        coveredCallStockShares.put(posKey, stockShares);
        coveredCallStockEntries.put(posKey, stockPrice);

        String groupId = UUID.randomUUID().toString();
        String stockReason = "Covered call stock leg K=" + callStrike + " exp=" + callExpiry;
        TransactionRecord stockRec = ts(new TransactionRecord(symbol, TransactionAction.BUY,
                stockShares, stockPrice, 0.0, account.getBalance(), stockReason, signalStr));
        stockRec.setFeatures(featureCsv);
        stockRec.setExternalId(stockOrderId);
        stockRec.setGroupId(groupId);
        try { transactionLog.insert(stockRec); } catch (Exception e) {
            LOG.warning("Covered call stock buy log failed: " + e.getMessage());
        }

        String callReason = "Covered call CALL K=" + callStrike + " exp=" + callExpiry + " (SHORT)";
        TransactionRecord callRec = ts(new TransactionRecord(symbol, TransactionAction.CALL_SELL,
                -contracts, callPremium, 0.0, account.getBalance(), callReason, signalStr));
        callRec.setFeatures(featureCsv);
        callRec.setExternalId(callOrderId);
        callRec.setGroupId(groupId);
        try { transactionLog.insert(callRec); } catch (Exception e) {
            LOG.warning("Covered call option sell log failed: " + e.getMessage());
        }
    }

    /**
     * Closes a covered call: buys back the short call and sells the underlying stock shares.
     */
    public void closeCoveredCall(String posKey, double callPremium, double stockPrice, String reason) {
        OptionsPosition callPos = account.getOptionsPositions().get(posKey);
        if (callPos == null) return;
        String symbol = callPos.getSymbol();
        int contracts = Math.abs(callPos.getContracts());
        int stockShares = coveredCallStockShares.getOrDefault(posKey, contracts * 100);

        double callCost = callPremium * 100 * contracts * (1 + OPTION_BUY_SLIPPAGE);
        double stockProceeds = stockShares * stockPrice;
        double callFee = contracts * CONTRACT_FEE;

        String callOrderId = null;
        if (submitter != null) {
            callOrderId = submitter.submit(symbol, "CALL", callPos.getStrike(), callPos.getExpiry(),
                    contracts, "buy", "buy_to_close");
        }
        String stockOrderId = null;
        if (submitter != null) {
            stockOrderId = submitter.sellStock(symbol, stockShares);
        }

        account.setBalance(account.getBalance() - callCost + stockProceeds - callFee);
        account.removeOptionsPosition(posKey);
        account.removePosition(symbol);

        double callPnl = (callPos.getPremiumPaid() - callPremium) * 100 * contracts - callFee;
        double stockEntry = coveredCallStockEntries.getOrDefault(posKey, callPos.getPremiumPaid());
        double stockPnl = (stockPrice - stockEntry) * stockShares;
        account.addRealizedPnL(callPnl + stockPnl);

        coveredCallStockShares.remove(posKey);
        coveredCallStockEntries.remove(posKey);

        String groupId = UUID.randomUUID().toString();
        TransactionRecord callRec = ts(new TransactionRecord(symbol, TransactionAction.CALL_BUY,
                contracts, callPremium, callFee, account.getBalance(), reason, ""));
        callRec.setExternalId(callOrderId);
        callRec.setGroupId(groupId);
        try { transactionLog.insert(callRec); } catch (Exception e) {
            LOG.warning("Covered call close call-buy log failed: " + e.getMessage());
        }

        TransactionRecord stockRec = ts(new TransactionRecord(symbol, TransactionAction.SELL,
                stockShares, stockPrice, 0.0, account.getBalance(), reason, ""));
        stockRec.setExternalId(stockOrderId);
        stockRec.setGroupId(groupId);
        try { transactionLog.insert(stockRec); } catch (Exception e) {
            LOG.warning("Covered call close stock-sell log failed: " + e.getMessage());
        }
    }

    public double getStockEntryPrice(String posKey) {
        return coveredCallStockEntries.getOrDefault(posKey, 0.0);
    }

    public boolean hasCoveredCallPosition(String posKey) {
        return account.getOptionsPositions().containsKey(posKey);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void logRecord(String symbol, TransactionAction action, int contracts, double premium,
                           double fee, String reason, String signalStr, String featureCsv,
                           String externalId, String groupId) {
        TransactionRecord rec = ts(new TransactionRecord(symbol, action, contracts, premium, fee,
                account.getBalance(), reason, signalStr));
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
        tagLastNRecords(groupId, 2);
    }

    private void tagLastFourRecords(String groupId) {
        tagLastNRecords(groupId, 4);
    }

    private void tagLastNRecords(String groupId, int n) {
        try {
            List<TransactionRecord> recent = transactionLog.findAll();
            int tagged = 0;
            for (TransactionRecord r : recent) {
                if (r.getGroupId() == null) {
                    r.setGroupId(groupId);
                    transactionLog.updateGroupId(r.getId(), groupId);
                    if (++tagged == n) break;
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to tag multi-leg records with groupId: " + e.getMessage());
        }
    }
}
