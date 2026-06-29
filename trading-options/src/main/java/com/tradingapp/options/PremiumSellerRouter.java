package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.engine.OptionsEvaluator;
import com.tradingapp.engine.SignalResult;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Multi-day premium selling router. Manages positions held for days/weeks:
 *   PUT_CREDIT_SPREAD  — bullish/neutral; short OTM put + long lower put
 *   CALL_CREDIT_SPREAD — bearish/neutral; short OTM call + long higher call
 *   IRON_CONDOR        — neutral; both spreads combined
 *   CASH_SECURED_PUT   — bullish; sell OTM put, hold secured cash
 *   COVERED_CALL       — bullish; own stock, sell OTM call against it
 *
 * Positions are held until:
 *   - 50% of credit is captured (profit target)
 *   - Underlying closes through the short strike (price-based stop)
 *   - < 7 DTE (approaching expiry)
 *
 * Premium positions are NEVER force-closed at 15:55. They survive overnight
 * and across multiple trading days, immune to OptionsSignalRouter's EOD sweep.
 */
public class PremiumSellerRouter implements OptionsEvaluator {

    public static final String PUTSPREAD_SHORT  = "_PUTSPREAD_SHORTPUT";
    public static final String PUTSPREAD_LONG   = "_PUTSPREAD_LONGPUT";
    public static final String CALLSPREAD_SHORT = "_CALLSPREAD_SHORTCALL";
    public static final String CALLSPREAD_LONG  = "_CALLSPREAD_LONGCALL";
    public static final String IC_SHORTCALL     = "_IRONCONDOR_SHORTCALL";
    public static final String IC_LONGCALL      = "_IRONCONDOR_LONGCALL";
    public static final String IC_SHORTPUT      = "_IRONCONDOR_SHORTPUT";
    public static final String IC_LONGPUT       = "_IRONCONDOR_LONGPUT";
    public static final String CSP_PUT          = "_CSP_PUT";
    public static final String CC_CALL          = "_CC_CALL";

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double RISK_FREE_RATE  = 0.04;
    private static final double SPREAD_WIDTH    = 10.0;   // $10 spread width
    private static final double DELTA_TARGET    = 0.20;   // ~80% OTM short strike
    private static final double MIN_CREDIT_PCT  = 0.20;   // skip if credit < 20% of spread
    private static final double PROFIT_TARGET   = 0.50;   // close at 50% of credit
    private static final int    CLOSE_DTE       = 7;      // close when < 7 DTE
    private static final double IV_PREMIUM      = 0.15;   // IV > HV edge: 15% premium on short legs
    private static final int    MAX_CONTRACTS   = 10;

    public static final String STRATEGY_PUT_CREDIT_SPREAD  = "PUT_CREDIT_SPREAD";
    public static final String STRATEGY_CALL_CREDIT_SPREAD = "CALL_CREDIT_SPREAD";
    public static final String STRATEGY_IRON_CONDOR        = "IRON_CONDOR";
    public static final String STRATEGY_CASH_SECURED_PUT   = "CASH_SECURED_PUT";
    public static final String STRATEGY_COVERED_CALL       = "COVERED_CALL";

    private final BlackScholesEngine bsEngine;
    private final OptionsOrderExecutor optExec;
    private Account account;
    private PriceHistory priceHistory;
    private final Consumer<String> log;

    // Blocks same-day re-entry after a stop-loss per symbol+strategy
    private final Map<String, LocalDate> lastExitDate = new HashMap<>();

    private Supplier<ZonedDateTime> clock = () -> ZonedDateTime.now(ET);

    // When set, PCS entries are blocked when this returns false (SPY below regime MA).
    private java.util.function.BooleanSupplier uptrendSupplier = null;

    // Maximum fraction of portfolio that may be deployed before new entries are blocked.
    private double maxPortfolioExposure = 0.70;

    // When non-null, only these strategies attempt new entries (all others still close open positions)
    private java.util.Set<String> enabledStrategies = null;

    // When non-empty, new entries are restricted to symbols in this set.
    // Exits for any open position still run regardless.
    private java.util.Set<String> allowlist = new java.util.HashSet<>();

    public PremiumSellerRouter(BlackScholesEngine bsEngine, OptionsOrderExecutor optExec,
                               Account account, PriceHistory priceHistory,
                               Consumer<String> log) {
        this.bsEngine    = bsEngine;
        this.optExec     = optExec;
        this.account     = account;
        this.priceHistory = priceHistory;
        this.log         = log;
    }

    public void setUptrendSupplier(java.util.function.BooleanSupplier supplier) {
        this.uptrendSupplier = supplier;
    }

    public void setMaxPortfolioExposure(double fraction) {
        this.maxPortfolioExposure = fraction;
    }

    public void setEnabledStrategies(java.util.Set<String> strategies) {
        this.enabledStrategies = strategies;
    }

    public void setAllowlist(java.util.Set<String> symbols) {
        this.allowlist = new java.util.HashSet<>(symbols);
    }

    private boolean isEnabled(String strategy) {
        return enabledStrategies == null || enabledStrategies.contains(strategy);
    }

    // ── OptionsEvaluator lifecycle ────────────────────────────────────────────

    @Override
    public void onBacktestInit(TransactionLog sharedLog, Account sharedAccount,
                               PriceHistory sharedHistory, Supplier<ZonedDateTime> clock) {
        optExec.setTransactionLog(sharedLog);
        optExec.setAccount(sharedAccount);
        optExec.setClock(() -> clock.get().toInstant().toEpochMilli());
        this.account      = sharedAccount;
        this.priceHistory = sharedHistory;
        this.clock        = clock;
    }

    @Override
    public void resetForDay(LocalDate date) {
        bsEngine.setReferenceDate(date);
    }

    @Override
    public void markPositionsToMarket(String symbol, double price) {
        double sigma = computeVol(symbol);
        if (sigma <= 0) return;
        for (Map.Entry<String, OptionsPosition> e : account.getOptionsPositions().entrySet()) {
            if (!isPremiumPositionFor(e.getKey(), symbol)) continue;
            OptionsPosition pos = e.getValue();
            double T = bsEngine.timeToExpiry(pos.getExpiry());
            if (T <= 0) continue;
            boolean isCall = "CALL".equals(pos.getType());
            double bsPrice = isCall
                    ? bsEngine.callPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                    : bsEngine.putPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma);
            pos.setCurrentMarketPrice(bsPrice);
        }
    }

    // ── Main evaluation (called every tick for each symbol) ───────────────────

    @Override
    public void evaluateWithSignals(String symbol, double price, int buys, int sells,
                                    String signalStr, String featureCsv,
                                    List<SignalResult> rawSignals) {
        LocalDate today = clock.get().toLocalDate();
        bsEngine.setReferenceDate(today);

        double sigma = computeVol(symbol);
        if (sigma <= 0) return;

        // ── 1. Monitor open positions for exits ───────────────────────────────
        checkExitPutSpread(symbol, price, sigma, today);
        checkExitCallSpread(symbol, price, sigma, today);
        checkExitIronCondor(symbol, price, sigma, today);
        checkExitCsp(symbol, price, sigma, today);
        checkExitCoveredCall(symbol, price, sigma, today);

        // Skip new entries while daily loss limit is active
        if (account.isDailyLossHalted()) return;

        // Skip new entries for symbols not in the allowlist (exits above still run).
        if (!allowlist.isEmpty() && !allowlist.contains(symbol)) return;

        // Skip new entries if max-loss-at-risk across open premium positions exceeds the cap.
        // Uses spread width × |contracts| × 100 per position — more accurate than cash-deployed
        // for credit spreads, which receive cash on entry and look cheap on a deployment basis.
        double portfolioValue = account.getTotalPortfolioValue();
        if (portfolioValue > 0 && premiumMaxLossAtRisk() / portfolioValue >= maxPortfolioExposure) return;

        // ── 2. New entries (once per day per symbol per strategy) ─────────────
        boolean inUptrend = uptrendSupplier == null || uptrendSupplier.getAsBoolean();

        // Put Credit Spread / CSP: bullish or neutral — require uptrend (SPY above regime MA)
        if (buys >= sells && sells < 2 && inUptrend) {
            if (isEnabled(STRATEGY_PUT_CREDIT_SPREAD))
                tryEnterPutSpread(symbol, price, sigma, today, signalStr, featureCsv);
            if (isEnabled(STRATEGY_CASH_SECURED_PUT))
                tryEnterCsp(symbol, price, sigma, today, signalStr, featureCsv);
        }
        // Call Credit Spread: bearish or neutral
        if (sells >= buys && buys < 2 && isEnabled(STRATEGY_CALL_CREDIT_SPREAD)) {
            tryEnterCallSpread(symbol, price, sigma, today, signalStr, featureCsv);
        }
        // Iron Condor: neutral (no strong directional push)
        if (buys <= 1 && sells <= 1 && isEnabled(STRATEGY_IRON_CONDOR)) {
            tryEnterIronCondor(symbol, price, sigma, today, signalStr, featureCsv);
        }
        // Covered Call: bullish
        if (buys >= 2 && isEnabled(STRATEGY_COVERED_CALL)) {
            tryEnterCoveredCall(symbol, price, sigma, today, signalStr, featureCsv);
        }
    }

    // ── Exit checks ───────────────────────────────────────────────────────────

    private void checkExitPutSpread(String symbol, double price, double sigma, LocalDate today) {
        OptionsPosition shortPos = account.getOptionsPositions().get(symbol + PUTSPREAD_SHORT);
        OptionsPosition longPos  = account.getOptionsPositions().get(symbol + PUTSPREAD_LONG);
        if (shortPos == null && longPos == null) return;
        if (shortPos == null || longPos == null) {
            // Orphaned single leg — close it immediately
            String orphanKey = shortPos != null ? symbol + PUTSPREAD_SHORT : symbol + PUTSPREAD_LONG;
            double orphanPrem = shortPos != null
                    ? bsPut(price, shortPos.getStrike(), bsEngine.timeToExpiry(shortPos.getExpiry()), sigma)
                    : bsPut(price, longPos.getStrike(),  bsEngine.timeToExpiry(longPos.getExpiry()),  sigma);
            optExec.closePosition(orphanKey, orphanPrem, "Orphaned spread leg — closing");
            log.accept(symbol + " PUT SPREAD orphaned leg closed");
            return;
        }

        double T = bsEngine.timeToExpiry(shortPos.getExpiry());
        long dte = ChronoUnit.DAYS.between(today, shortPos.getExpiry());
        double sCur = bsPut(price, shortPos.getStrike(), T, sigma) * (1 + IV_PREMIUM);
        double lCur = bsPut(price, longPos.getStrike(),  T, sigma);

        int contracts = Math.abs(shortPos.getContracts());
        double credit    = (shortPos.getPremiumPaid() - longPos.getPremiumPaid()) * 100 * contracts;
        double closeCost = (sCur - lCur) * 100 * contracts;
        double pnl       = credit - closeCost;

        String reason = exitReason(price, shortPos.getStrike(), false, credit, pnl, dte);
        if (reason == null) return;

        optExec.closeCreditSpread(symbol + PUTSPREAD_SHORT, symbol + PUTSPREAD_LONG,
                sCur, lCur, reason);
        lastExitDate.put(symbol + "_PUTSPREAD", today);
        log.accept(String.format("%s PUT CREDIT SPREAD closed: %s | P&L $%.0f", symbol, reason, pnl));
    }

    private void checkExitCallSpread(String symbol, double price, double sigma, LocalDate today) {
        OptionsPosition shortPos = account.getOptionsPositions().get(symbol + CALLSPREAD_SHORT);
        OptionsPosition longPos  = account.getOptionsPositions().get(symbol + CALLSPREAD_LONG);
        if (shortPos == null && longPos == null) return;
        if (shortPos == null || longPos == null) {
            String orphanKey = shortPos != null ? symbol + CALLSPREAD_SHORT : symbol + CALLSPREAD_LONG;
            double orphanPrem = shortPos != null
                    ? bsCall(price, shortPos.getStrike(), bsEngine.timeToExpiry(shortPos.getExpiry()), sigma)
                    : bsCall(price, longPos.getStrike(),  bsEngine.timeToExpiry(longPos.getExpiry()),  sigma);
            optExec.closePosition(orphanKey, orphanPrem, "Orphaned spread leg — closing");
            log.accept(symbol + " CALL SPREAD orphaned leg closed");
            return;
        }

        double T = bsEngine.timeToExpiry(shortPos.getExpiry());
        long dte = ChronoUnit.DAYS.between(today, shortPos.getExpiry());
        double sCur = bsCall(price, shortPos.getStrike(), T, sigma) * (1 + IV_PREMIUM);
        double lCur = bsCall(price, longPos.getStrike(),  T, sigma);

        int contracts = Math.abs(shortPos.getContracts());
        double credit    = (shortPos.getPremiumPaid() - longPos.getPremiumPaid()) * 100 * contracts;
        double closeCost = (sCur - lCur) * 100 * contracts;
        double pnl       = credit - closeCost;

        String reason = exitReason(price, shortPos.getStrike(), true, credit, pnl, dte);
        if (reason == null) return;

        optExec.closeCreditSpread(symbol + CALLSPREAD_SHORT, symbol + CALLSPREAD_LONG,
                sCur, lCur, reason);
        lastExitDate.put(symbol + "_CALLSPREAD", today);
        log.accept(String.format("%s CALL CREDIT SPREAD closed: %s | P&L $%.0f", symbol, reason, pnl));
    }

    private void checkExitIronCondor(String symbol, double price, double sigma, LocalDate today) {
        OptionsPosition scPos = account.getOptionsPositions().get(symbol + IC_SHORTCALL);
        OptionsPosition lcPos = account.getOptionsPositions().get(symbol + IC_LONGCALL);
        OptionsPosition spPos = account.getOptionsPositions().get(symbol + IC_SHORTPUT);
        OptionsPosition lpPos = account.getOptionsPositions().get(symbol + IC_LONGPUT);
        boolean anyPresent = scPos != null || lcPos != null || spPos != null || lpPos != null;
        if (!anyPresent) return;
        boolean allPresent = scPos != null && lcPos != null && spPos != null && lpPos != null;
        if (!allPresent) {
            // Orphaned IC legs — close whatever is open and bail
            double T = scPos != null ? bsEngine.timeToExpiry(scPos.getExpiry())
                     : lcPos != null ? bsEngine.timeToExpiry(lcPos.getExpiry())
                     : spPos != null ? bsEngine.timeToExpiry(spPos.getExpiry())
                     :                 bsEngine.timeToExpiry(lpPos.getExpiry());
            if (scPos != null) optExec.closePosition(symbol + IC_SHORTCALL, bsCall(price, scPos.getStrike(), T, sigma), "Orphaned IC leg — closing");
            if (lcPos != null) optExec.closePosition(symbol + IC_LONGCALL,  bsCall(price, lcPos.getStrike(), T, sigma), "Orphaned IC leg — closing");
            if (spPos != null) optExec.closePosition(symbol + IC_SHORTPUT,  bsPut (price, spPos.getStrike(), T, sigma), "Orphaned IC leg — closing");
            if (lpPos != null) optExec.closePosition(symbol + IC_LONGPUT,   bsPut (price, lpPos.getStrike(), T, sigma), "Orphaned IC leg — closing");
            log.accept(symbol + " IRON CONDOR orphaned leg(s) closed");
            return;
        }

        double T = bsEngine.timeToExpiry(scPos.getExpiry());
        long dte = ChronoUnit.DAYS.between(today, scPos.getExpiry());
        double scCur = bsCall(price, scPos.getStrike(), T, sigma) * (1 + IV_PREMIUM);
        double lcCur = bsCall(price, lcPos.getStrike(), T, sigma);
        double spCur = bsPut (price, spPos.getStrike(), T, sigma) * (1 + IV_PREMIUM);
        double lpCur = bsPut (price, lpPos.getStrike(), T, sigma);

        int contracts = Math.abs(scPos.getContracts());
        double credit = ((scPos.getPremiumPaid() - lcPos.getPremiumPaid())
                       + (spPos.getPremiumPaid() - lpPos.getPremiumPaid())) * 100 * contracts;
        double closeCost = ((scCur - lcCur) + (spCur - lpCur)) * 100 * contracts;
        double pnl       = credit - closeCost;

        boolean callStop = price > scPos.getStrike();
        boolean putStop  = price < spPos.getStrike();
        boolean target   = credit > 0 && pnl >= credit * PROFIT_TARGET;
        boolean expiring = dte < CLOSE_DTE;
        if (!callStop && !putStop && !target && !expiring) return;

        String reason = callStop  ? pricestop(price, scPos.getStrike(), true)
                      : putStop   ? pricestop(price, spPos.getStrike(), false)
                      : expiring  ? "DTE < " + CLOSE_DTE
                      : profitMsg(pnl);

        optExec.closeIronCondor(symbol + IC_SHORTCALL, symbol + IC_LONGCALL,
                symbol + IC_SHORTPUT, symbol + IC_LONGPUT,
                scCur, lcCur, spCur, lpCur, reason);
        lastExitDate.put(symbol + "_IRONCONDOR", today);
        log.accept(String.format("%s IRON CONDOR closed: %s | P&L $%.0f", symbol, reason, pnl));
    }

    private void checkExitCsp(String symbol, double price, double sigma, LocalDate today) {
        OptionsPosition pos = account.getOptionsPositions().get(symbol + CSP_PUT);
        if (pos == null) return;

        double T = bsEngine.timeToExpiry(pos.getExpiry());
        long dte = ChronoUnit.DAYS.between(today, pos.getExpiry());
        double curPrem = bsPut(price, pos.getStrike(), T, sigma) * (1 + IV_PREMIUM);

        int contracts = Math.abs(pos.getContracts());
        double credit    = pos.getPremiumPaid() * 100 * contracts;
        double closeCost = curPrem * 100 * contracts;
        double pnl       = credit - closeCost;

        String reason = exitReason(price, pos.getStrike(), false, credit, pnl, dte);
        if (reason == null) return;

        optExec.closePosition(symbol + CSP_PUT, curPrem, reason);
        lastExitDate.put(symbol + "_CSP", today);
        log.accept(String.format("%s CSP closed: %s | P&L $%.0f", symbol, reason, pnl));
    }

    private void checkExitCoveredCall(String symbol, double price, double sigma, LocalDate today) {
        if (!optExec.hasCoveredCallPosition(symbol + CC_CALL)) return;
        OptionsPosition pos = account.getOptionsPositions().get(symbol + CC_CALL);
        if (pos == null) return;

        double T = bsEngine.timeToExpiry(pos.getExpiry());
        long dte = ChronoUnit.DAYS.between(today, pos.getExpiry());
        double curPrem = bsCall(price, pos.getStrike(), T, sigma) * (1 + IV_PREMIUM);

        int contracts = Math.abs(pos.getContracts());
        double credit    = pos.getPremiumPaid() * 100 * contracts;
        double closeCost = curPrem * 100 * contracts;
        double pnl       = credit - closeCost;

        // Close on profit target, near expiry, or if stock materially above strike (likely assigned)
        boolean target   = credit > 0 && pnl >= credit * PROFIT_TARGET;
        boolean expiring = dte < CLOSE_DTE;
        boolean assigned = price > pos.getStrike() * 1.02;
        if (!target && !expiring && !assigned) return;

        String reason = assigned  ? pricestop(price, pos.getStrike(), true)
                      : expiring  ? "DTE < " + CLOSE_DTE
                      : profitMsg(pnl);

        optExec.closeCoveredCall(symbol + CC_CALL, curPrem, price, reason);
        lastExitDate.put(symbol + "_CC", today);
        log.accept(String.format("%s COVERED CALL closed: %s | option P&L $%.0f", symbol, reason, pnl));
    }

    // ── Entry logic ───────────────────────────────────────────────────────────

    private void tryEnterPutSpread(String symbol, double price, double sigma, LocalDate today,
                                   String signalStr, String featureCsv) {
        if (account.getOptionsPositions().containsKey(symbol + PUTSPREAD_SHORT)
                || account.getOptionsPositions().containsKey(symbol + PUTSPREAD_LONG)) return;
        if (today.equals(lastExitDate.get(symbol + "_PUTSPREAD"))) return;

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        double shortK = findOtmPutStrike(price, T, sigma);
        double longK  = bsEngine.roundStrike(shortK - SPREAD_WIDTH);
        if (longK <= 0) return;

        double shortPrem = bsEngine.putPrice(price, shortK, RISK_FREE_RATE, T, sigma) * (1 + IV_PREMIUM);
        double longPrem  = bsEngine.putPrice(price, longK,  RISK_FREE_RATE, T, sigma);
        double credit    = shortPrem - longPrem;
        if (credit < SPREAD_WIDTH * MIN_CREDIT_PCT) {
            log.accept(symbol + " PUT SPREAD skip: credit $" + String.format("%.2f", credit * 100) + " < min");
            return;
        }

        int c = creditContracts(account.getBalance(), SPREAD_WIDTH - credit);
        if (c < 1) return;

        boolean opened = optExec.openCreditSpread(
                symbol + PUTSPREAD_SHORT, symbol + PUTSPREAD_LONG,
                symbol, "PUT", shortK, longK, expiry, c,
                shortPrem, longPrem, signalStr, featureCsv, "PUT SPREAD");
        if (opened) {
            log.accept(String.format("%s PUT CREDIT SPREAD K=%.0f/%.0f exp=%s x%d credit=$%.0f",
                    symbol, shortK, longK, expiry, c, credit * 100 * c));
        }
    }

    private void tryEnterCallSpread(String symbol, double price, double sigma, LocalDate today,
                                    String signalStr, String featureCsv) {
        if (account.getOptionsPositions().containsKey(symbol + CALLSPREAD_SHORT)
                || account.getOptionsPositions().containsKey(symbol + CALLSPREAD_LONG)) return;
        if (account.getOptionsPositions().containsKey(symbol + PUTSPREAD_SHORT)) return; // PCS takes priority
        if (today.equals(lastExitDate.get(symbol + "_CALLSPREAD"))) return;

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        double shortK = findOtmCallStrike(price, T, sigma);
        double longK  = bsEngine.roundStrike(shortK + SPREAD_WIDTH);

        double shortPrem = bsEngine.callPrice(price, shortK, RISK_FREE_RATE, T, sigma) * (1 + IV_PREMIUM);
        double longPrem  = bsEngine.callPrice(price, longK,  RISK_FREE_RATE, T, sigma);
        double credit    = shortPrem - longPrem;
        if (credit < SPREAD_WIDTH * MIN_CREDIT_PCT) {
            log.accept(symbol + " CALL SPREAD skip: credit $" + String.format("%.2f", credit * 100) + " < min");
            return;
        }

        int c = creditContracts(account.getBalance(), SPREAD_WIDTH - credit);
        if (c < 1) return;

        boolean opened = optExec.openCreditSpread(
                symbol + CALLSPREAD_SHORT, symbol + CALLSPREAD_LONG,
                symbol, "CALL", shortK, longK, expiry, c,
                shortPrem, longPrem, signalStr, featureCsv, "CALL SPREAD");
        if (opened) {
            log.accept(String.format("%s CALL CREDIT SPREAD K=%.0f/%.0f exp=%s x%d credit=$%.0f",
                    symbol, shortK, longK, expiry, c, credit * 100 * c));
        }
    }

    private void tryEnterIronCondor(String symbol, double price, double sigma, LocalDate today,
                                    String signalStr, String featureCsv) {
        if (account.getOptionsPositions().containsKey(symbol + IC_SHORTCALL)
                || account.getOptionsPositions().containsKey(symbol + IC_LONGCALL)
                || account.getOptionsPositions().containsKey(symbol + IC_SHORTPUT)
                || account.getOptionsPositions().containsKey(symbol + IC_LONGPUT)) return;
        if (today.equals(lastExitDate.get(symbol + "_IRONCONDOR"))) return;

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        double shortPutK  = findOtmPutStrike(price, T, sigma);
        double longPutK   = bsEngine.roundStrike(shortPutK - SPREAD_WIDTH);
        double shortCallK = findOtmCallStrike(price, T, sigma);
        double longCallK  = bsEngine.roundStrike(shortCallK + SPREAD_WIDTH);
        if (longPutK <= 0 || shortPutK >= shortCallK) return;

        double spPrem = bsEngine.putPrice (price, shortPutK,  RISK_FREE_RATE, T, sigma) * (1 + IV_PREMIUM);
        double lpPrem = bsEngine.putPrice (price, longPutK,   RISK_FREE_RATE, T, sigma);
        double scPrem = bsEngine.callPrice(price, shortCallK, RISK_FREE_RATE, T, sigma) * (1 + IV_PREMIUM);
        double lcPrem = bsEngine.callPrice(price, longCallK,  RISK_FREE_RATE, T, sigma);

        double credit = (spPrem - lpPrem) + (scPrem - lcPrem);
        if (credit < SPREAD_WIDTH * MIN_CREDIT_PCT) {
            log.accept(symbol + " IRON CONDOR skip: credit $" + String.format("%.2f", credit * 100) + " < min");
            return;
        }

        int c = creditContracts(account.getBalance(), SPREAD_WIDTH - credit);
        if (c < 1) return;

        boolean opened = optExec.openIronCondor(
                symbol + IC_SHORTCALL, symbol + IC_LONGCALL,
                symbol + IC_SHORTPUT,  symbol + IC_LONGPUT,
                symbol, shortCallK, longCallK, shortPutK, longPutK,
                expiry, c, scPrem, lcPrem, spPrem, lpPrem,
                signalStr, featureCsv);
        if (opened) {
            log.accept(String.format("%s IRON CONDOR puts=%.0f/%.0f calls=%.0f/%.0f exp=%s x%d credit=$%.0f",
                    symbol, shortPutK, longPutK, shortCallK, longCallK, expiry, c, credit * 100 * c));
        }
    }

    private void tryEnterCsp(String symbol, double price, double sigma, LocalDate today,
                             String signalStr, String featureCsv) {
        if (account.getOptionsPositions().containsKey(symbol + CSP_PUT)) return;
        if (today.equals(lastExitDate.get(symbol + "_CSP"))) return;

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        double K    = findOtmPutStrike(price, T, sigma);
        double prem = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma) * (1 + IV_PREMIUM);
        if (prem < 1.00) return;

        // Secured capital = strike × 100 per contract; cap at 20% of balance
        double securedPerContract = K * 100;
        int c = Math.max(1, Math.min(5, (int) (account.getBalance() * 0.20 / securedPerContract)));
        if (account.getBalance() < securedPerContract * c) return;

        optExec.sellPutAs(symbol + CSP_PUT, symbol, K, expiry, c, prem, signalStr, featureCsv);
        if (account.getOptionsPositions().containsKey(symbol + CSP_PUT)) {
            log.accept(String.format("%s CSP K=%.0f exp=%s x%d credit=$%.0f",
                    symbol, K, expiry, c, prem * 100 * c));
        }
    }

    private void tryEnterCoveredCall(String symbol, double price, double sigma, LocalDate today,
                                     String signalStr, String featureCsv) {
        if (optExec.hasCoveredCallPosition(symbol + CC_CALL)) return;
        if (today.equals(lastExitDate.get(symbol + "_CC"))) return;
        if (account.getPositions().containsKey(symbol)) return; // existing equity position conflicts

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        double callK = findOtmCallStrike(price, T, sigma);
        double prem  = bsEngine.callPrice(price, callK, RISK_FREE_RATE, T, sigma) * (1 + IV_PREMIUM);
        if (prem < 0.50) return;

        int maxContracts = Math.min(3, (int) (account.getBalance() / (price * 100)));
        if (maxContracts < 1) return;

        optExec.openCoveredCall(symbol + CC_CALL, symbol, price, callK,
                expiry, maxContracts, prem, signalStr, featureCsv);
        if (account.getOptionsPositions().containsKey(symbol + CC_CALL)) {
            log.accept(String.format("%s COVERED CALL K=%.0f exp=%s prem=$%.2f",
                    symbol, callK, expiry, prem));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Total max-loss-at-risk across all open premium positions.
     * For credit spreads the max loss is SPREAD_WIDTH × |contracts| × 100.
     * Iron Condor counted once (one wing can breach at a time).
     * CSP counted at strike × |contracts| × 100 (full downside).
     */
    private double premiumMaxLossAtRisk() {
        double risk = 0;
        for (Map.Entry<String, OptionsPosition> e : account.getOptionsPositions().entrySet()) {
            String key = e.getKey();
            OptionsPosition pos = e.getValue();
            int n = Math.abs(pos.getContracts());
            if (key.endsWith(PUTSPREAD_SHORT) || key.endsWith(CALLSPREAD_SHORT)) {
                risk += SPREAD_WIDTH * n * 100;
            } else if (key.endsWith(IC_SHORTPUT)) {
                // Count one IC wing only — max loss is the breached side, not both simultaneously
                risk += SPREAD_WIDTH * n * 100;
            } else if (key.endsWith(CSP_PUT)) {
                risk += pos.getStrike() * n * 100;
            }
        }
        return risk;
    }

    /**
     * Returns an exit reason string when an exit condition is triggered, or null to hold.
     * isCall=true means check if price rose above the short strike (call stop).
     * isCall=false means check if price fell below the short strike (put stop).
     */
    private String exitReason(double price, double shortStrike, boolean isCall,
                              double credit, double pnl, long dte) {
        boolean stopHit  = isCall ? price > shortStrike : price < shortStrike;
        boolean target   = credit > 0 && pnl >= credit * PROFIT_TARGET;
        boolean expiring = dte < CLOSE_DTE;
        if (!stopHit && !target && !expiring) return null;
        if (expiring) return "DTE < " + CLOSE_DTE;
        if (target)   return profitMsg(pnl);
        return pricestop(price, shortStrike, isCall);
    }

    private static String pricestop(double price, double strike, boolean isCall) {
        return String.format("Price stop: %.2f %s K=%.0f",
                price, isCall ? ">" : "<", strike);
    }

    private static String profitMsg(double pnl) {
        return String.format("Profit target 50%%: +$%.0f", pnl);
    }

    private double bsCall(double S, double K, double T, double sigma) {
        return T > 0 ? bsEngine.callPrice(S, K, RISK_FREE_RATE, T, sigma)
                     : Math.max(0, S - K);
    }

    private double bsPut(double S, double K, double T, double sigma) {
        return T > 0 ? bsEngine.putPrice(S, K, RISK_FREE_RATE, T, sigma)
                     : Math.max(0, K - S);
    }

    private double findOtmPutStrike(double S, double T, double sigma) {
        double K = bsEngine.roundStrike(S);
        for (int i = 0; i < 20; i++) {
            GreeksResult g = bsEngine.greeks(S, K, RISK_FREE_RATE, T, sigma, false);
            if (Math.abs(g.delta) <= DELTA_TARGET) break;
            K -= 5.0;
            if (K <= 0) return bsEngine.roundStrike(S * 0.85);
        }
        return Math.max(5.0, K);
    }

    private double findOtmCallStrike(double S, double T, double sigma) {
        double K = bsEngine.roundStrike(S);
        for (int i = 0; i < 20; i++) {
            GreeksResult g = bsEngine.greeks(S, K, RISK_FREE_RATE, T, sigma, true);
            if (g.delta <= DELTA_TARGET) break;
            K += 5.0;
        }
        return K;
    }

    private int creditContracts(double cash, double riskPerShare) {
        if (riskPerShare <= 0) return 0;
        return Math.min(MAX_CONTRACTS, Math.max(1, (int) (cash * 0.02 / (riskPerShare * 100))));
    }

    private double computeVol(String symbol) {
        List<Double> daily = priceHistory.getDailyPrices(symbol);
        List<Double> prices = daily.size() >= 2 ? daily : priceHistory.getPrices(symbol);
        return prices.size() < 2 ? 0.0 : bsEngine.historicalVol(prices);
    }

    /**
     * Returns true when posKey is a premium seller position for the given symbol.
     * Used by OptionsSignalRouter to skip these in its 15:55 force-close sweep.
     */
    public static boolean isPremiumPositionFor(String posKey, String symbol) {
        return posKey.startsWith(symbol + "_PUTSPREAD_")
            || posKey.startsWith(symbol + "_CALLSPREAD_")
            || posKey.startsWith(symbol + "_IRONCONDOR_")
            || posKey.startsWith(symbol + "_CSP_")
            || posKey.equals(symbol + "_CC_CALL");
    }

    public static boolean isPremiumKey(String posKey) {
        return posKey.contains("_PUTSPREAD_") || posKey.contains("_CALLSPREAD_")
            || posKey.contains("_IRONCONDOR_") || posKey.contains("_CSP_")
            || posKey.endsWith("_CC_CALL");
    }
}
