package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.OptionsQuote;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.engine.OptionsEvaluator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Day-trading options router. Maps signal strength to intraday strategies only:
 *
 *   5+ buys,  0 sells → High-Delta Scalp Call (deep ITM, near-term)
 *   4 buys,   0 sells → Near-Term Momentum Call (ATM, ~7 DTE)
 *   3 buys,   0 sells → Long Call
 *
 *   5+ sells, 0 buys  → High-Delta Scalp Put
 *   4 sells,  0 buys  → Near-Term Momentum Put
 *   3 sells,  0 buys  → Long Put
 *
 *   mixed (≥2 buys + ≥1 sell, or vice-versa):
 *     → Zero-DTE Straddle on Fridays only
 */
public class OptionsSignalRouter implements OptionsEvaluator {

    private final BlackScholesEngine bsEngine;
    private final OptionsOrderExecutor optExec;
    private final Account account;
    private final PriceHistory priceHistory;
    private final Consumer<String> researchCallback;
    private final QuoteProvider dataClient;

    private static final double RISK_FREE_RATE  = 0.04;
    private static final double MIN_PREMIUM     = 1.50;  // $150/contract minimum to cover fees
    private double maxPortfolioExposure         = 0.60;
    private static final double IV_SURGE_THRESHOLD = 1.2;  // skip if recent vol > 1.2x long-term (IV crush guard)
    private static final int    IV_WINDOW          = 20;
    private static final double PROFIT_TARGET      = 2.0;
    private static final double STOP_LOSS_FRAC     = 0.35;  // close if premium drops to 35% of entry
    private static final double DEEP_ITM_OFFSET    = 10.0;

    private final Set<String> sessionStopLossed = new HashSet<>();
    private final Map<String, Long> lastMultiLegCloseMs    = new HashMap<>();
    private final Map<String, Long> lastDirectionalCloseMs = new HashMap<>();
    private final Map<String, Long> lastAnyCloseMs         = new HashMap<>();
    private final Map<String, Integer> reversalConsecutive = new HashMap<>();
    private static final long MULTILEG_REENTRY_COOLDOWN_MS = 15 * 60 * 1000L;
    private LocalDate stopLossResetDate;
    private BooleanSupplier uptrendSupplier;
    private Set<String> enabledStrategies = new HashSet<>();

    public void setUptrendSupplier(BooleanSupplier s) { this.uptrendSupplier = s; }
    public void setMaxPortfolioExposure(double fraction) { this.maxPortfolioExposure = fraction; }
    public void setEnabledStrategies(Set<String> strategies) { this.enabledStrategies = new HashSet<>(strategies); }
    private boolean isStrategyEnabled(String name) { return enabledStrategies.isEmpty() || enabledStrategies.contains(name); }

    public OptionsSignalRouter(BlackScholesEngine bsEngine, OptionsOrderExecutor optExec,
                               Account account, PriceHistory priceHistory,
                               Consumer<String> researchCallback) {
        this(bsEngine, optExec, account, priceHistory, researchCallback, null);
    }

    public OptionsSignalRouter(BlackScholesEngine bsEngine, OptionsOrderExecutor optExec,
                               Account account, PriceHistory priceHistory,
                               Consumer<String> researchCallback, QuoteProvider dataClient) {
        this.bsEngine         = bsEngine;
        this.optExec          = optExec;
        this.account          = account;
        this.priceHistory     = priceHistory;
        this.researchCallback = researchCallback;
        this.dataClient       = dataClient;
    }

    @Override
    public void evaluate(String symbol, double price, int buySignals, int sellSignals,
                         String signalStr, String featureCsv) {
        resetIfNewDay();

        String callKey          = symbol + "_CALL";
        String putKey           = symbol + "_PUT";
        String highDeltaCallKey = symbol + "_HIGHDELTA_CALL";
        String highDeltaPutKey  = symbol + "_HIGHDELTA_PUT";
        String nearTermCallKey  = symbol + "_NEARTERM_CALL";
        String nearTermPutKey   = symbol + "_NEARTERM_PUT";
        String zeroDteCallKey   = symbol + "_ZEROTE_CALL";
        String zeroDtePutKey    = symbol + "_ZEROTE_PUT";

        Map<String, OptionsPosition> opts = account.getOptionsPositions();

        // ── 1. Close existing positions ───────────────────────────────────────
        closeDirectionalLeg(opts, callKey,          true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, putKey,           false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, highDeltaCallKey, true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, highDeltaPutKey,  false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, nearTermCallKey,  true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, nearTermPutKey,   false, symbol, price, buySignals,  signalStr);
        closeMultiLegIfNeeded(opts, zeroDteCallKey, zeroDtePutKey, symbol, price, "ZEROTE", signalStr);

        opts = account.getOptionsPositions();

        // ── 2. Guard checks ───────────────────────────────────────────────────
        List<Double> dailyPs = priceHistory.getDailyPrices(symbol);
        List<Double> prices  = dailyPs.size() >= 2 ? dailyPs : priceHistory.getPrices(symbol);
        if (prices.size() < 2) return;

        if (account.isDailyLossHalted()) {
            researchCallback.accept(symbol + " options skip: daily loss limit active");
            return;
        }

        double sigma = bsEngine.historicalVol(prices);
        if (sigma == 0.0) {
            researchCallback.accept(symbol + " options skip: vol=0");
            return;
        }

        // ── 3. IV surge guard ─────────────────────────────────────────────────
        if (prices.size() >= IV_WINDOW + 2) {
            double recentVol = bsEngine.historicalVol(
                    prices.subList(prices.size() - IV_WINDOW, prices.size()));
            if (recentVol > sigma * IV_SURGE_THRESHOLD) {
                researchCallback.accept(symbol + " options skip: IV surge "
                        + String.format("%.2f", recentVol) + " > "
                        + String.format("%.2f", sigma * IV_SURGE_THRESHOLD));
                return;
            }
        }

        if (account.totalExposureFraction() >= maxPortfolioExposure) {
            researchCallback.accept(symbol + " options skip: portfolio at capacity ("
                    + String.format("%.0f%%", account.totalExposureFraction() * 100) + ")");
            return;
        }

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double K = bsEngine.roundStrike(price);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        // ── 4. Signal classification ──────────────────────────────────────────
        boolean extremeBullish  = buySignals >= 5 && sellSignals == 0;
        boolean veryStrongBull  = buySignals == 4 && sellSignals == 0;
        boolean purelyBullish   = buySignals == 3 && sellSignals == 0;
        boolean extremeBearish  = sellSignals >= 5 && buySignals == 0;
        boolean veryStrongBear  = sellSignals == 4 && buySignals == 0;
        boolean purelyBearish   = sellSignals == 3 && buySignals == 0;
        boolean mixedStrong     = (buySignals >= 2 && sellSignals >= 1)
                               || (sellSignals >= 2 && buySignals >= 1);

        // Block calls in downtrend — symmetric to the per-method put block in uptrend
        boolean inDowntrend = uptrendSupplier != null && !uptrendSupplier.getAsBoolean();

        boolean hasDirectional = opts.containsKey(callKey)          || opts.containsKey(putKey)
                              || opts.containsKey(highDeltaCallKey)  || opts.containsKey(highDeltaPutKey)
                              || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey);
        boolean hasMultiLeg   = opts.containsKey(zeroDteCallKey)    || opts.containsKey(zeroDtePutKey);

        // ── 5. Cooldown check ─────────────────────────────────────────────────
        Long lastClose = lastAnyCloseMs.getOrDefault(symbol, 0L);
        if (System.currentTimeMillis() - lastClose < MULTILEG_REENTRY_COOLDOWN_MS) {
            researchCallback.accept(symbol + " skip: options cooldown ("
                    + ((System.currentTimeMillis() - lastClose) / 60000) + "m elapsed, need 15m)");
            return;
        }

        // ── 6. Entry ──────────────────────────────────────────────────────────
        if (extremeBullish && !hasDirectional && !hasMultiLeg) {
            if (inDowntrend)
                researchCallback.accept(symbol + " CALL skip: SPY downtrend");
            else if (isStrategyEnabled("HIGH_DELTA_SCALP"))
                tryOpenHighDeltaScalp(symbol, price, K, expiry, T, sigma, true, signalStr, featureCsv);

        } else if (veryStrongBull && !hasDirectional && !hasMultiLeg) {
            if (inDowntrend)
                researchCallback.accept(symbol + " CALL skip: SPY downtrend");
            else if (isStrategyEnabled("MOMENTUM_NEAR_TERM"))
                tryOpenMomentumNearTerm(symbol, price, true, sigma, signalStr, featureCsv);

        } else if (purelyBullish && !hasDirectional && !hasMultiLeg) {
            if (inDowntrend)
                researchCallback.accept(symbol + " CALL skip: SPY downtrend");
            else if (isStrategyEnabled("LONG_CALL"))
                tryOpenLongCall(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, callKey, opts);

        } else if (extremeBearish && !hasDirectional && !hasMultiLeg) {
            if (isStrategyEnabled("HIGH_DELTA_SCALP"))
                tryOpenHighDeltaScalp(symbol, price, K, expiry, T, sigma, false, signalStr, featureCsv);

        } else if (veryStrongBear && !hasDirectional && !hasMultiLeg) {
            if (isStrategyEnabled("MOMENTUM_NEAR_TERM"))
                tryOpenMomentumNearTerm(symbol, price, false, sigma, signalStr, featureCsv);

        } else if (purelyBearish && !hasDirectional && !hasMultiLeg) {
            if (isStrategyEnabled("LONG_PUT"))
                tryOpenLongPut(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, putKey, sellSignals, opts);

        } else if (mixedStrong && !hasDirectional && !hasMultiLeg) {
            if (isZeroDteDay() && isStrategyEnabled("ZERO_DTE"))
                tryOpenZeroDTE(symbol, price, K, sigma, signalStr, featureCsv);
        }
    }

    // ── Directional close ──────────────────────────────────────────────────────

    private void closeDirectionalLeg(Map<String, OptionsPosition> opts, String posKey,
                                     boolean isCall, String symbol, double price,
                                     int reversalSignals, String signalStr) {
        OptionsPosition pos = opts.get(posKey);
        if (pos == null) return;

        double T     = bsEngine.timeToExpiry(pos.getExpiry());
        double sigma = computeVol(symbol);
        boolean canPrice = sigma > 0 && T > 0;
        double currentPremium = canPrice
                ? (isCall ? bsEngine.callPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                          : bsEngine.putPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma))
                : 0.0;

        boolean premiumStop  = canPrice && currentPremium <= pos.getPremiumPaid() * STOP_LOSS_FRAC;
        boolean profitTarget = currentPremium >= pos.getPremiumPaid() * PROFIT_TARGET;
        boolean nearExpiry   = pos.daysToExpiry() < 3;

        // Require 2 consecutive ticks of opposing signals before exiting on reversal
        int consecutive;
        if (reversalSignals >= 2) {
            consecutive = reversalConsecutive.merge(posKey, 1, Integer::sum);
        } else {
            reversalConsecutive.remove(posKey);
            consecutive = 0;
        }
        boolean reversal = consecutive >= 2;

        if (!premiumStop && !profitTarget && !reversal && !nearExpiry) return;

        reversalConsecutive.remove(posKey);
        String reason;
        if (nearExpiry)        reason = "Expiry <3 days";
        else if (profitTarget) reason = String.format("Profit target: %.2f >= 2x of %.2f",
                                        currentPremium, pos.getPremiumPaid());
        else if (premiumStop)  reason = String.format("Premium stop-loss: %.2f <= %.0f%% of %.2f",
                                        currentPremium, STOP_LOSS_FRAC * 100, pos.getPremiumPaid());
        else                   reason = "Signal reversal (2 bars): " + (isCall ? "SELL" : "BUY");

        if (premiumStop) sessionStopLossed.add(posKey);
        lastDirectionalCloseMs.put(posKey, System.currentTimeMillis());
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());
        optExec.closePosition(posKey, currentPremium, reason);
        researchCallback.accept(symbol + (isCall ? " CALL" : " PUT") + " closed: " + reason
                + " prem=" + String.format("%.2f", currentPremium));
    }

    // ── Multi-leg close (Zero-DTE) ────────────────────────────────────────────

    private void closeMultiLegIfNeeded(Map<String, OptionsPosition> opts,
                                       String callKey, String putKey,
                                       String symbol, double price,
                                       String strategyName, String signalStr) {
        OptionsPosition callPos = opts.get(callKey);
        OptionsPosition putPos  = opts.get(putKey);
        if (callPos == null || putPos == null) return;

        double sigma = computeVol(symbol);
        double T = strategyName.equals("ZEROTE") ? 0.5 / 365.0 : bsEngine.timeToExpiry(callPos.getExpiry());

        double callPrem = (sigma > 0 && T > 0)
                ? bsEngine.callPrice(price, callPos.getStrike(), RISK_FREE_RATE, T, sigma)
                : Math.max(0, price - callPos.getStrike());
        double putPrem = (sigma > 0 && T > 0)
                ? bsEngine.putPrice(price, putPos.getStrike(), RISK_FREE_RATE, T, sigma)
                : Math.max(0, putPos.getStrike() - price);

        double totalPaid    = callPos.getPremiumPaid() * 100 * callPos.getContracts()
                            + putPos.getPremiumPaid()  * 100 * putPos.getContracts();
        double totalCurrent = callPrem * 100 * callPos.getContracts()
                            + putPrem  * 100 * putPos.getContracts();

        boolean premiumStop  = totalPaid > 0 && totalCurrent <= totalPaid * STOP_LOSS_FRAC;
        boolean profitTarget = totalPaid > 0 && totalCurrent >= totalPaid * PROFIT_TARGET;
        boolean nearExpiry   = strategyName.equals("ZEROTE")
                ? callPos.daysToExpiry() < 1
                : callPos.daysToExpiry() < 3;

        if (!premiumStop && !profitTarget && !nearExpiry) return;

        String reason;
        if (nearExpiry)        reason = "Expiry <" + (strategyName.equals("ZEROTE") ? "1 day" : "3 days");
        else if (profitTarget) reason = String.format("Profit target: combined %.2f >= 2x of %.2f",
                                        totalCurrent, totalPaid);
        else                   reason = String.format("Premium stop-loss: combined %.2f <= %.0f%% of %.2f",
                                        totalCurrent, STOP_LOSS_FRAC * 100, totalPaid);

        if (premiumStop) sessionStopLossed.add(symbol + "_MULTILEG");
        lastMultiLegCloseMs.put(symbol, System.currentTimeMillis());
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());
        optExec.closeBuyPair(callKey, putKey, callPrem, putPrem, reason);
        researchCallback.accept(String.format("%s %s closed: %s combined=%.2f",
                symbol, strategyName, reason, totalCurrent));
    }

    // ── Long Call entry ───────────────────────────────────────────────────────

    private void tryOpenLongCall(String symbol, double price, double K, LocalDate expiry,
                                 double T, double sigma, String signalStr, String featureCsv,
                                 String callKey, Map<String, OptionsPosition> opts) {
        if (sessionStopLossed.contains(callKey)) {
            researchCallback.accept(symbol + " CALL skip: stop-loss cooldown");
            return;
        }
        Long callLastClose = lastDirectionalCloseMs.get(callKey);
        if (callLastClose != null && System.currentTimeMillis() - callLastClose < MULTILEG_REENTRY_COOLDOWN_MS) {
            researchCallback.accept(symbol + " CALL skip: re-entry cooldown");
            return;
        }
        if (account.getPositions().containsKey(symbol)) {
            researchCallback.accept(symbol + " CALL skip: equity position already open");
            return;
        }
        double premium = resolvePremium(symbol, K, expiry, true, T, sigma, price);
        if (premium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " CALL skip: premium too low (" + String.format("%.4f", premium) + ")");
            return;
        }
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (premium * 100)));
        if (contracts < 1) return;

        optExec.buyCall(symbol, K, expiry, contracts, premium, signalStr, featureCsv);
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());
        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, true);
        researchCallback.accept(symbol + " CALL K=" + K + " exp=" + expiry
                + " x" + contracts + " prem=" + String.format("%.2f", premium) + " | " + g);
    }

    // ── Long Put entry ────────────────────────────────────────────────────────

    private void tryOpenLongPut(String symbol, double price, double K, LocalDate expiry,
                                double T, double sigma, String signalStr, String featureCsv,
                                String putKey, int sellSignals, Map<String, OptionsPosition> opts) {
        if (sessionStopLossed.contains(putKey)) {
            researchCallback.accept(symbol + " PUT skip: stop-loss cooldown");
            return;
        }
        Long putLastClose = lastDirectionalCloseMs.get(putKey);
        if (putLastClose != null && System.currentTimeMillis() - putLastClose < MULTILEG_REENTRY_COOLDOWN_MS) {
            researchCallback.accept(symbol + " PUT skip: re-entry cooldown");
            return;
        }
        if (uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " PUT skip: bull market (SPY above 50-day MA)");
            return;
        }
        double optionsBudget = account.getPositions().containsKey(symbol)
                ? account.getBalance() * 0.025
                : account.getBalance() * 0.05;
        double premium = resolvePremium(symbol, K, expiry, false, T, sigma, price);
        if (premium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " PUT skip: premium too low (" + String.format("%.4f", premium) + ")");
            return;
        }
        int contracts = Math.min(5, (int) (optionsBudget / (premium * 100)));
        if (contracts < 1) return;

        optExec.buyPut(symbol, K, expiry, contracts, premium, signalStr, featureCsv);
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());
        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(symbol + " PUT K=" + K + " exp=" + expiry
                + " x" + contracts + " prem=" + String.format("%.2f", premium) + " | " + g);
    }

    // ── High-Delta Scalp entry ────────────────────────────────────────────────

    private void tryOpenHighDeltaScalp(String symbol, double price, double K, LocalDate expiry,
                                       double T, double sigma, boolean isCall,
                                       String signalStr, String featureCsv) {
        String posKey = symbol + (isCall ? "_HIGHDELTA_CALL" : "_HIGHDELTA_PUT");
        if (sessionStopLossed.contains(posKey)) {
            researchCallback.accept(symbol + (isCall ? " HIGH-DELTA CALL" : " HIGH-DELTA PUT") + " skip: stop-loss cooldown");
            return;
        }
        Long hdLastClose = lastDirectionalCloseMs.get(posKey);
        if (hdLastClose != null && System.currentTimeMillis() - hdLastClose < MULTILEG_REENTRY_COOLDOWN_MS) {
            researchCallback.accept(symbol + (isCall ? " HIGH-DELTA CALL" : " HIGH-DELTA PUT") + " skip: re-entry cooldown");
            return;
        }
        if (isCall && account.getPositions().containsKey(symbol)) {
            researchCallback.accept(symbol + " HIGH-DELTA CALL skip: equity position already open");
            return;
        }
        if (!isCall && uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " HIGH-DELTA PUT skip: bull market");
            return;
        }
        double deepK   = isCall ? Math.max(1.0, K - DEEP_ITM_OFFSET) : K + DEEP_ITM_OFFSET;
        LocalDate nearExpiry = bsEngine.selectNearTermExpiry();
        double nearT = bsEngine.timeToExpiry(nearExpiry);
        if (nearT <= 0) nearT = 7.0 / 365.0;

        double premium = resolvePremium(symbol, deepK, nearExpiry, isCall, nearT, sigma, price);
        if (premium < MIN_PREMIUM) {
            researchCallback.accept(symbol + (isCall ? " HIGH-DELTA CALL" : " HIGH-DELTA PUT")
                    + " skip: premium too low (" + String.format("%.4f", premium) + ")");
            return;
        }
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (premium * 100)));
        if (contracts < 1) return;

        if (isCall) optExec.buyCallAs(posKey, symbol, deepK, nearExpiry, contracts, premium, signalStr, featureCsv);
        else        optExec.buyPutAs (posKey, symbol, deepK, nearExpiry, contracts, premium, signalStr, featureCsv);
        if (!account.getOptionsPositions().containsKey(posKey)) return;
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());

        GreeksResult g = bsEngine.greeks(price, deepK, RISK_FREE_RATE, nearT, sigma, isCall);
        researchCallback.accept(String.format("%s HIGH-DELTA %s K=%.0f (deep ITM) exp=%s x%d prem=%.2f | %s",
                symbol, isCall ? "CALL" : "PUT", deepK, nearExpiry, contracts, premium, g));
    }

    // ── Near-Term Momentum entry ──────────────────────────────────────────────

    private void tryOpenMomentumNearTerm(String symbol, double price, boolean isCall,
                                         double sigma, String signalStr, String featureCsv) {
        String posKey = symbol + (isCall ? "_NEARTERM_CALL" : "_NEARTERM_PUT");
        if (sessionStopLossed.contains(posKey)) {
            researchCallback.accept(symbol + (isCall ? " NEARTERM CALL" : " NEARTERM PUT") + " skip: stop-loss cooldown");
            return;
        }
        Long ntLastClose = lastDirectionalCloseMs.get(posKey);
        if (ntLastClose != null && System.currentTimeMillis() - ntLastClose < MULTILEG_REENTRY_COOLDOWN_MS) {
            researchCallback.accept(symbol + (isCall ? " NEARTERM CALL" : " NEARTERM PUT") + " skip: re-entry cooldown");
            return;
        }
        if (isCall && account.getPositions().containsKey(symbol)) {
            researchCallback.accept(symbol + " NEARTERM CALL skip: equity position already open");
            return;
        }
        if (!isCall && uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " NEARTERM PUT skip: bull market");
            return;
        }
        LocalDate nearExpiry = bsEngine.selectNearTermExpiry();
        double nearT = bsEngine.timeToExpiry(nearExpiry);
        if (nearT <= 0) nearT = 7.0 / 365.0;

        double K = bsEngine.roundStrike(price);
        double premium = resolvePremium(symbol, K, nearExpiry, isCall, nearT, sigma, price);
        if (premium < MIN_PREMIUM) {
            researchCallback.accept(symbol + (isCall ? " NEARTERM CALL" : " NEARTERM PUT") + " skip: premium too low");
            return;
        }
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (premium * 100)));
        if (contracts < 1) return;

        if (isCall) optExec.buyCallAs(posKey, symbol, K, nearExpiry, contracts, premium, signalStr, featureCsv);
        else        optExec.buyPutAs (posKey, symbol, K, nearExpiry, contracts, premium, signalStr, featureCsv);
        if (!account.getOptionsPositions().containsKey(posKey)) return;
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());

        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, nearT, sigma, isCall);
        researchCallback.accept(String.format("%s NEAR-TERM %s K=%.0f exp=%s x%d prem=%.2f | %s",
                symbol, isCall ? "CALL" : "PUT", K, nearExpiry, contracts, premium, g));
    }

    // ── Zero-DTE Straddle entry ───────────────────────────────────────────────

    private void tryOpenZeroDTE(String symbol, double price, double K, double sigma,
                                String signalStr, String featureCsv) {
        String callKey = symbol + "_ZEROTE_CALL";
        String putKey  = symbol + "_ZEROTE_PUT";

        if (sessionStopLossed.contains(symbol + "_MULTILEG")) {
            researchCallback.accept(symbol + " ZERO-DTE skip: stop-loss cooldown");
            return;
        }
        Long lastClose = lastMultiLegCloseMs.get(symbol);
        if (lastClose != null && System.currentTimeMillis() - lastClose < MULTILEG_REENTRY_COOLDOWN_MS) {
            researchCallback.accept(symbol + " ZERO-DTE skip: re-entry cooldown");
            return;
        }

        LocalDate today = LocalDate.now();
        double T = 0.5 / 365.0;
        double callPremium = resolvePremium(symbol, K, today, true,  T, sigma, price);
        double putPremium  = resolvePremium(symbol, K, today, false, T, sigma, price);

        if (callPremium < MIN_PREMIUM || putPremium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " ZERO-DTE skip: premium too low call="
                    + String.format("%.4f", callPremium) + " put=" + String.format("%.4f", putPremium));
            return;
        }
        double combinedPremium = callPremium + putPremium;
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (combinedPremium * 100)));
        if (contracts < 1) {
            researchCallback.accept(symbol + " ZERO-DTE skip: insufficient budget");
            return;
        }

        boolean opened = optExec.openBuyPair(callKey, putKey, symbol, K, K,
                today, today, contracts, callPremium, putPremium, signalStr, featureCsv, "ZERO-DTE");
        if (!opened) {
            researchCallback.accept(symbol + " ZERO-DTE did not open (legs rejected)");
            return;
        }
        lastAnyCloseMs.put(symbol, System.currentTimeMillis());

        GreeksResult cg = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, true);
        GreeksResult pg = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(String.format("%s ZERO-DTE K=%.0f x%d combined=%.2f | call: %s | put: %s",
                symbol, K, contracts, combinedPremium, cg, pg));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double resolvePremium(String symbol, double strike, LocalDate expiry,
                                  boolean isCall, double T, double sigma, double price) {
        double bsPremium = isCall
                ? bsEngine.callPrice(price, strike, RISK_FREE_RATE, T, sigma)
                : bsEngine.putPrice(price, strike, RISK_FREE_RATE, T, sigma);

        if (dataClient == null) return bsPremium;

        OptionsChain chain = dataClient.getOptionsChain(symbol, expiry);
        OptionsQuote quote = isCall ? chain.getCall(strike) : chain.getPut(strike);
        if (quote == null || !quote.isValid()) {
            researchCallback.accept(symbol + (isCall ? " CALL" : " PUT") + " skip: no market quote at K=" + strike);
            return 0.0;
        }
        if (!quote.isLiquid()) {
            researchCallback.accept(symbol + (isCall ? " CALL" : " PUT") + " skip: illiquid " + quote.liquidityInfo());
            return 0.0;
        }
        return quote.getAsk();
    }

    private boolean isZeroDteDay() {
        return LocalDate.now().getDayOfWeek() == DayOfWeek.FRIDAY;
    }

    private double computeVol(String symbol) {
        List<Double> daily  = priceHistory.getDailyPrices(symbol);
        List<Double> prices = daily.size() >= 2 ? daily : priceHistory.getPrices(symbol);
        return prices.size() < 2 ? 0.0 : bsEngine.historicalVol(prices);
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(stopLossResetDate)) {
            sessionStopLossed.clear();
            lastMultiLegCloseMs.clear();
            lastDirectionalCloseMs.clear();
            reversalConsecutive.clear();
            stopLossResetDate = today;
        }
    }
}
