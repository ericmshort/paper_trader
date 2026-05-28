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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Routes options signals to strategies based on signal strength and IV regime:
 *
 *   5 buys,  0 sells → High-Delta Scalp Call (deep ITM, near-term expiry)
 *   4 buys,  0 sells → Near-Term Momentum Call (ATM, ~7 DTE)
 *   3 buys,  0 sells → Long Call
 *   2 buys,  0 sells → Bull Put Spread (credit, moderately bullish)
 *
 *   5 sells, 0 buys  → High-Delta Scalp Put
 *   4 sells, 0 buys  → Near-Term Momentum Put
 *   3 sells, 0 buys  → Long Put
 *   2 sells, 0 buys  → Bear Call Spread (credit, moderately bearish)
 *
 *   mixed (≥2 buys + ≥1 sell, or vice-versa):
 *     → Zero-DTE Straddle on Fridays
 *     → Straddle or Strangle otherwise
 */
public class OptionsSignalRouter implements OptionsEvaluator {

    private final BlackScholesEngine bsEngine;
    private final OptionsOrderExecutor optExec;
    private final Account account;
    private final PriceHistory priceHistory;
    private final Consumer<String> researchCallback;
    private final QuoteProvider dataClient;

    private static final double RISK_FREE_RATE    = 0.04;
    private static final double MIN_PREMIUM       = 0.10;
    private static final double MAX_PORTFOLIO_EXPOSURE = 0.60;
    private static final double IV_SURGE_THRESHOLD = 1.5;
    private static final int    IV_WINDOW         = 20;
    private static final double PROFIT_TARGET     = 2.0;
    private static final double DEEP_ITM_OFFSET   = 10.0; // strike shift for high-delta positions

    // Straddle vs strangle selection
    static final double IV_STRADDLE_THRESHOLD = 0.80;
    static final int    IV_RECENT_WINDOW      = 10;
    static final double STRANGLE_SPREAD       = 5.0;

    private final Set<String> sessionStopLossed = new HashSet<>();
    private LocalDate stopLossResetDate;
    private BooleanSupplier uptrendSupplier;

    public void setUptrendSupplier(BooleanSupplier s) { this.uptrendSupplier = s; }

    public OptionsSignalRouter(BlackScholesEngine bsEngine, OptionsOrderExecutor optExec,
                               Account account, PriceHistory priceHistory,
                               Consumer<String> researchCallback) {
        this(bsEngine, optExec, account, priceHistory, researchCallback, null);
    }

    public OptionsSignalRouter(BlackScholesEngine bsEngine, OptionsOrderExecutor optExec,
                               Account account, PriceHistory priceHistory,
                               Consumer<String> researchCallback, QuoteProvider dataClient) {
        this.bsEngine = bsEngine;
        this.optExec = optExec;
        this.account = account;
        this.priceHistory = priceHistory;
        this.researchCallback = researchCallback;
        this.dataClient = dataClient;
    }

    @Override
    public void evaluate(String symbol, double price, int buySignals, int sellSignals,
                         String signalStr, String featureCsv) {
        resetIfNewDay();

        // ── Position keys ─────────────────────────────────────────────────────────
        String callKey          = symbol + "_CALL";
        String putKey           = symbol + "_PUT";
        String straddleCallKey  = symbol + "_STRADDLE_CALL";
        String straddlePutKey   = symbol + "_STRADDLE_PUT";
        String strangleCallKey  = symbol + "_STRANGLE_CALL";
        String stranglePutKey   = symbol + "_STRANGLE_PUT";
        String highDeltaCallKey = symbol + "_HIGHDELTA_CALL";
        String highDeltaPutKey  = symbol + "_HIGHDELTA_PUT";
        String nearTermCallKey  = symbol + "_NEARTERM_CALL";
        String nearTermPutKey   = symbol + "_NEARTERM_PUT";
        String zeroDteCallKey   = symbol + "_ZEROTE_CALL";
        String zeroDtePutKey    = symbol + "_ZEROTE_PUT";
        String bullPutShortKey  = symbol + "_BULLPUTSPREAD_SHORT";
        String bullPutLongKey   = symbol + "_BULLPUTSPREAD_LONG";
        String bearCallShortKey = symbol + "_BEARCALLSPREAD_SHORT";
        String bearCallLongKey  = symbol + "_BEARCALLSPREAD_LONG";

        Map<String, OptionsPosition> opts = account.getOptionsPositions();

        // ── 1. Close existing positions ───────────────────────────────────────────
        closeDirectionalLeg(opts, callKey,          true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, putKey,           false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, highDeltaCallKey, true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, highDeltaPutKey,  false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, nearTermCallKey,  true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, nearTermPutKey,   false, symbol, price, buySignals,  signalStr);

        closeMultiLegIfNeeded(opts, straddleCallKey, straddlePutKey, symbol, price, "STRADDLE", signalStr);
        closeMultiLegIfNeeded(opts, strangleCallKey, stranglePutKey, symbol, price, "STRANGLE", signalStr);
        closeMultiLegIfNeeded(opts, zeroDteCallKey,  zeroDtePutKey,  symbol, price, "ZEROTE",   signalStr);

        closeCreditSpreadIfNeeded(opts, bullPutShortKey,  bullPutLongKey,   false, symbol, price, "BULLPUTSPREAD");
        closeCreditSpreadIfNeeded(opts, bearCallShortKey, bearCallLongKey,  true,  symbol, price, "BEARCALLSPREAD");

        // Re-read after potential closures
        opts = account.getOptionsPositions();

        // ── 2. Guard checks ───────────────────────────────────────────────────────
        List<Double> dailyPs = priceHistory.getDailyPrices(symbol);
        List<Double> prices  = dailyPs.size() >= 2 ? dailyPs : priceHistory.getPrices(symbol);
        if (prices.size() < 2) return;

        if (account.totalExposureFraction() >= MAX_PORTFOLIO_EXPOSURE) {
            researchCallback.accept(symbol + " options skip: portfolio at capacity ("
                    + String.format("%.0f%%", account.totalExposureFraction() * 100) + " deployed)");
            return;
        }
        if (account.isDailyLossHalted()) {
            researchCallback.accept(symbol + " options skip: daily loss limit active");
            return;
        }

        double sigma = bsEngine.historicalVol(prices);
        if (sigma == 0.0) {
            researchCallback.accept(symbol + " options skip: vol=0");
            return;
        }

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

        LocalDate expiry = bsEngine.selectExpiry(symbol);
        double K = bsEngine.roundStrike(price);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        // ── 3. Signal classification ──────────────────────────────────────────────
        boolean extremeBullish   = buySignals >= 5 && sellSignals == 0;
        boolean veryStrongBull   = buySignals == 4 && sellSignals == 0;
        boolean purelyBullish    = buySignals == 3 && sellSignals == 0;
        boolean moderateBullish  = buySignals == 2 && sellSignals == 0;
        boolean extremeBearish   = sellSignals >= 5 && buySignals == 0;
        boolean veryStrongBear   = sellSignals == 4 && buySignals == 0;
        boolean purelyBearish    = sellSignals == 3 && buySignals == 0;
        boolean moderateBearish  = sellSignals == 2 && buySignals == 0;
        boolean mixedStrong      = (buySignals >= 2 && sellSignals >= 1)
                                || (sellSignals >= 2 && buySignals >= 1);

        boolean hasDirectional = opts.containsKey(callKey)         || opts.containsKey(putKey)
                              || opts.containsKey(highDeltaCallKey) || opts.containsKey(highDeltaPutKey)
                              || opts.containsKey(nearTermCallKey)  || opts.containsKey(nearTermPutKey);
        boolean hasMultiLeg    = opts.containsKey(straddleCallKey) || opts.containsKey(straddlePutKey)
                              || opts.containsKey(strangleCallKey)  || opts.containsKey(stranglePutKey)
                              || opts.containsKey(bullPutShortKey)  || opts.containsKey(bullPutLongKey)
                              || opts.containsKey(bearCallShortKey) || opts.containsKey(bearCallLongKey)
                              || opts.containsKey(zeroDteCallKey)   || opts.containsKey(zeroDtePutKey);

        // ── 4. Entry ──────────────────────────────────────────────────────────────
        if (extremeBullish && !hasDirectional && !hasMultiLeg) {
            tryOpenHighDeltaScalp(symbol, price, K, expiry, T, sigma, true, signalStr, featureCsv);

        } else if (veryStrongBull && !hasDirectional && !hasMultiLeg) {
            tryOpenMomentumNearTerm(symbol, price, true, sigma, signalStr, featureCsv);

        } else if (purelyBullish && !hasDirectional && !hasMultiLeg) {
            tryOpenLongCall(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, callKey, opts);

        } else if (moderateBullish && !hasDirectional && !hasMultiLeg) {
            tryOpenBullPutSpread(symbol, price, K, expiry, T, sigma, signalStr, featureCsv);

        } else if (extremeBearish && !hasDirectional && !hasMultiLeg) {
            tryOpenHighDeltaScalp(symbol, price, K, expiry, T, sigma, false, signalStr, featureCsv);

        } else if (veryStrongBear && !hasDirectional && !hasMultiLeg) {
            tryOpenMomentumNearTerm(symbol, price, false, sigma, signalStr, featureCsv);

        } else if (purelyBearish && !hasDirectional && !hasMultiLeg) {
            tryOpenLongPut(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, putKey, sellSignals, opts);

        } else if (moderateBearish && !hasDirectional && !hasMultiLeg) {
            tryOpenBearCallSpread(symbol, price, K, expiry, T, sigma, signalStr, featureCsv);

        } else if (mixedStrong && !hasDirectional && !hasMultiLeg) {
            if (isZeroDteDay()) {
                tryOpenZeroDTE(symbol, price, K, sigma, signalStr, featureCsv);
            } else {
                tryOpenStraddleOrStrangle(symbol, price, K, expiry, T, sigma, prices, signalStr, featureCsv);
            }
        }
    }

    // ── Directional close ──────────────────────────────────────────────────────────

    private void closeDirectionalLeg(Map<String, OptionsPosition> opts, String posKey,
                                     boolean isCall, String symbol, double price,
                                     int reversalSignals, String signalStr) {
        OptionsPosition pos = opts.get(posKey);
        if (pos == null) return;

        double T     = bsEngine.timeToExpiry(pos.getExpiry());
        double sigma = computeVol(symbol);
        double currentPremium = (sigma > 0 && T > 0)
                ? (isCall ? bsEngine.callPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                          : bsEngine.putPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma))
                : 0.0;

        boolean premiumStop  = currentPremium > 0 && currentPremium <= pos.getPremiumPaid() * 0.50;
        boolean profitTarget = currentPremium >= pos.getPremiumPaid() * PROFIT_TARGET;
        boolean reversal     = reversalSignals >= 2;
        boolean nearExpiry   = pos.daysToExpiry() < 3;

        if (!premiumStop && !profitTarget && !reversal && !nearExpiry) return;

        String reason;
        if (nearExpiry)        reason = "Expiry <3 days";
        else if (profitTarget) reason = String.format("Profit target: %.2f >= 2x of %.2f",
                                        currentPremium, pos.getPremiumPaid());
        else if (premiumStop)  reason = String.format("Premium stop-loss: %.2f <= 50%% of %.2f",
                                        currentPremium, pos.getPremiumPaid());
        else                   reason = "Signal reversal: " + (isCall ? "SELL" : "BUY");

        if (premiumStop) sessionStopLossed.add(posKey);
        optExec.closePosition(posKey, currentPremium, reason);
        researchCallback.accept(symbol + (isCall ? " CALL" : " PUT") + " closed: " + reason
                + " prem=" + String.format("%.2f", currentPremium));
    }

    // ── Multi-leg close (straddle / strangle / zero-DTE) ─────────────────────────

    private void closeMultiLegIfNeeded(Map<String, OptionsPosition> opts,
                                       String callKey, String putKey,
                                       String symbol, double price,
                                       String strategyName, String signalStr) {
        OptionsPosition callPos = opts.get(callKey);
        OptionsPosition putPos  = opts.get(putKey);
        if (callPos == null || putPos == null) return;

        double sigma = computeVol(symbol);
        double T = (strategyName.equals("ZEROTE"))
                ? 0.5 / 365.0
                : bsEngine.timeToExpiry(callPos.getExpiry());

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

        boolean premiumStop  = totalPaid > 0 && totalCurrent <= totalPaid * 0.50;
        boolean profitTarget = totalPaid > 0 && totalCurrent >= totalPaid * PROFIT_TARGET;
        boolean nearExpiry   = strategyName.equals("ZEROTE")
                ? callPos.daysToExpiry() < 1
                : callPos.daysToExpiry() < 3;

        if (!premiumStop && !profitTarget && !nearExpiry) return;

        String reason;
        if (nearExpiry)        reason = "Expiry <" + (strategyName.equals("ZEROTE") ? "1 day" : "3 days");
        else if (profitTarget) reason = String.format("Profit target: combined %.2f >= 2x of %.2f",
                                        totalCurrent, totalPaid);
        else                   reason = String.format("Premium stop-loss: combined %.2f <= 50%% of %.2f",
                                        totalCurrent, totalPaid);

        if (premiumStop) sessionStopLossed.add(symbol + "_" + strategyName);
        optExec.closePosition(callKey, callPrem, reason);
        optExec.closePosition(putKey,  putPrem,  reason);
        researchCallback.accept(String.format("%s %s closed: %s combined=%.2f",
                symbol, strategyName, reason, totalCurrent));
    }

    // ── Credit spread close ────────────────────────────────────────────────────────

    private void closeCreditSpreadIfNeeded(Map<String, OptionsPosition> opts,
                                           String shortKey, String longKey,
                                           boolean isCall, String symbol, double price,
                                           String strategyName) {
        OptionsPosition shortPos = opts.get(shortKey); // contracts < 0
        OptionsPosition longPos  = opts.get(longKey);  // contracts > 0
        if (shortPos == null || longPos == null) return;

        double sigma = computeVol(symbol);
        double T = bsEngine.timeToExpiry(shortPos.getExpiry());
        int c = Math.abs(shortPos.getContracts());

        double shortPrem = (sigma > 0 && T > 0)
                ? (isCall ? bsEngine.callPrice(price, shortPos.getStrike(), RISK_FREE_RATE, T, sigma)
                          : bsEngine.putPrice(price, shortPos.getStrike(), RISK_FREE_RATE, T, sigma))
                : (isCall ? Math.max(0, price - shortPos.getStrike())
                          : Math.max(0, shortPos.getStrike() - price));
        double longPrem = (sigma > 0 && T > 0)
                ? (isCall ? bsEngine.callPrice(price, longPos.getStrike(), RISK_FREE_RATE, T, sigma)
                          : bsEngine.putPrice(price, longPos.getStrike(), RISK_FREE_RATE, T, sigma))
                : (isCall ? Math.max(0, price - longPos.getStrike())
                          : Math.max(0, longPos.getStrike() - price));

        double creditReceived = shortPos.getPremiumPaid() * 100 * c;
        double netCostToClose = (shortPrem - longPrem) * 100 * c; // what we'd pay to buy back the spread

        boolean profitTarget = netCostToClose <= creditReceived * 0.50; // kept 50% of credit
        boolean stopLoss     = netCostToClose >= creditReceived * 2.0;  // cost = 2x credit received
        boolean nearExpiry   = shortPos.daysToExpiry() < 3;

        if (!profitTarget && !stopLoss && !nearExpiry) return;

        String reason;
        if (nearExpiry)        reason = "Expiry <3 days";
        else if (profitTarget) reason = String.format("Profit target: close cost=%.2f <= 50%% of credit=%.2f",
                                        netCostToClose, creditReceived);
        else                   reason = String.format("Stop-loss: close cost=%.2f >= 2x credit=%.2f",
                                        netCostToClose, creditReceived);

        if (stopLoss) sessionStopLossed.add(symbol + "_" + strategyName);
        optExec.closePosition(shortKey, shortPrem, reason); // buy-to-close: neg contracts → balance decreases
        optExec.closePosition(longKey,  longPrem,  reason); // sell-to-close
        researchCallback.accept(String.format("%s %s closed: %s credit=%.2f costToClose=%.2f",
                symbol, strategyName, reason, creditReceived, netCostToClose));
    }

    // ── Long Call entry ───────────────────────────────────────────────────────────

    private void tryOpenLongCall(String symbol, double price, double K, LocalDate expiry,
                                 double T, double sigma, String signalStr, String featureCsv,
                                 String callKey, Map<String, OptionsPosition> opts) {
        if (sessionStopLossed.contains(callKey)) {
            researchCallback.accept(symbol + " CALL skip: stop-loss cooldown");
            return;
        }
        if (account.getPositions().containsKey(symbol)) {
            researchCallback.accept(symbol + " CALL skip: equity position already open (avoid double-dip)");
            return;
        }
        double premium = resolvePremium(symbol, K, expiry, true, T, sigma, price);
        if (premium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " CALL skip: premium too low ("
                    + String.format("%.4f", premium) + ")");
            return;
        }
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (premium * 100)));
        if (contracts < 1) return;

        optExec.buyCall(symbol, K, expiry, contracts, premium, signalStr, featureCsv);
        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, true);
        researchCallback.accept(symbol + " CALL K=" + K + " exp=" + expiry
                + " x" + contracts + " prem=" + String.format("%.2f", premium) + " | " + g);
    }

    // ── Long Put entry ────────────────────────────────────────────────────────────

    private void tryOpenLongPut(String symbol, double price, double K, LocalDate expiry,
                                double T, double sigma, String signalStr, String featureCsv,
                                String putKey, int sellSignals, Map<String, OptionsPosition> opts) {
        if (sessionStopLossed.contains(putKey)) {
            researchCallback.accept(symbol + " PUT skip: stop-loss cooldown");
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
            researchCallback.accept(symbol + " PUT skip: premium too low ("
                    + String.format("%.4f", premium) + ")");
            return;
        }
        int contracts = Math.min(5, (int) (optionsBudget / (premium * 100)));
        if (contracts < 1) return;

        optExec.buyPut(symbol, K, expiry, contracts, premium, signalStr, featureCsv);
        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(symbol + " PUT K=" + K + " exp=" + expiry
                + " x" + contracts + " prem=" + String.format("%.2f", premium) + " | " + g);
    }

    // ── Bull Put Spread entry (credit) ────────────────────────────────────────────

    private void tryOpenBullPutSpread(String symbol, double price, double K, LocalDate expiry,
                                      double T, double sigma, String signalStr, String featureCsv) {
        String cooldownKey  = symbol + "_BULLPUTSPREAD";
        String shortKey     = symbol + "_BULLPUTSPREAD_SHORT";
        String longKey      = symbol + "_BULLPUTSPREAD_LONG";

        if (sessionStopLossed.contains(cooldownKey)) {
            researchCallback.accept(symbol + " BULL PUT SPREAD skip: stop-loss cooldown");
            return;
        }
        if (uptrendSupplier != null && !uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " BULL PUT SPREAD skip: bear market (SPY below 50-day MA)");
            return;
        }

        double shortK = K;
        double longK  = K - STRANGLE_SPREAD;

        double shortPrem = resolvePremium(symbol, shortK, expiry, false, T, sigma, price);
        double longPrem  = resolvePremium(symbol, longK,  expiry, false, T, sigma, price);

        if (shortPrem < MIN_PREMIUM || longPrem < MIN_PREMIUM) {
            researchCallback.accept(symbol + " BULL PUT SPREAD skip: premium too low");
            return;
        }
        double netCredit = shortPrem - longPrem;
        if (netCredit <= 0) {
            researchCallback.accept(symbol + " BULL PUT SPREAD skip: no credit available");
            return;
        }

        // Size by max loss (spread width) not by credit received
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (STRANGLE_SPREAD * 100)));
        if (contracts < 1) return;

        optExec.sellPutAs(shortKey, symbol, shortK, expiry, contracts, shortPrem, signalStr, featureCsv);
        optExec.buyPutAs(longKey,   symbol, longK,  expiry, contracts, longPrem,  signalStr, featureCsv);

        boolean shortOpened = account.getOptionsPositions().containsKey(shortKey);
        boolean longOpened  = account.getOptionsPositions().containsKey(longKey);
        if (shortOpened && !longOpened) {
            optExec.closePosition(shortKey, shortPrem, "Rollback: BULL PUT SPREAD long leg did not open");
            researchCallback.accept(symbol + " BULL PUT SPREAD SHORT rolled back: long leg failed");
            return;
        }
        if (!shortOpened && longOpened) {
            optExec.closePosition(longKey, longPrem, "Rollback: BULL PUT SPREAD short leg did not open");
            researchCallback.accept(symbol + " BULL PUT SPREAD LONG rolled back: short leg failed");
            return;
        }
        if (!shortOpened) return;

        GreeksResult sg = bsEngine.greeks(price, shortK, RISK_FREE_RATE, T, sigma, false);
        GreeksResult lg = bsEngine.greeks(price, longK,  RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(String.format(
                "%s BULL PUT SPREAD short_K=%.0f long_K=%.0f exp=%s x%d credit=%.2f | short: %s | long: %s",
                symbol, shortK, longK, expiry, contracts, netCredit, sg, lg));
    }

    // ── Bear Call Spread entry (credit) ───────────────────────────────────────────

    private void tryOpenBearCallSpread(String symbol, double price, double K, LocalDate expiry,
                                       double T, double sigma, String signalStr, String featureCsv) {
        String cooldownKey  = symbol + "_BEARCALLSPREAD";
        String shortKey     = symbol + "_BEARCALLSPREAD_SHORT";
        String longKey      = symbol + "_BEARCALLSPREAD_LONG";

        if (sessionStopLossed.contains(cooldownKey)) {
            researchCallback.accept(symbol + " BEAR CALL SPREAD skip: stop-loss cooldown");
            return;
        }
        if (uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " BEAR CALL SPREAD skip: bull market (SPY above 50-day MA)");
            return;
        }

        double shortK = K;
        double longK  = K + STRANGLE_SPREAD;

        double shortPrem = resolvePremium(symbol, shortK, expiry, true, T, sigma, price);
        double longPrem  = resolvePremium(symbol, longK,  expiry, true, T, sigma, price);

        if (shortPrem < MIN_PREMIUM || longPrem < MIN_PREMIUM) {
            researchCallback.accept(symbol + " BEAR CALL SPREAD skip: premium too low");
            return;
        }
        double netCredit = shortPrem - longPrem;
        if (netCredit <= 0) {
            researchCallback.accept(symbol + " BEAR CALL SPREAD skip: no credit available");
            return;
        }

        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (STRANGLE_SPREAD * 100)));
        if (contracts < 1) return;

        optExec.sellCallAs(shortKey, symbol, shortK, expiry, contracts, shortPrem, signalStr, featureCsv);
        optExec.buyCallAs(longKey,   symbol, longK,  expiry, contracts, longPrem,  signalStr, featureCsv);

        boolean shortOpened = account.getOptionsPositions().containsKey(shortKey);
        boolean longOpened  = account.getOptionsPositions().containsKey(longKey);
        if (shortOpened && !longOpened) {
            optExec.closePosition(shortKey, shortPrem, "Rollback: BEAR CALL SPREAD long leg did not open");
            researchCallback.accept(symbol + " BEAR CALL SPREAD SHORT rolled back: long leg failed");
            return;
        }
        if (!shortOpened && longOpened) {
            optExec.closePosition(longKey, longPrem, "Rollback: BEAR CALL SPREAD short leg did not open");
            researchCallback.accept(symbol + " BEAR CALL SPREAD LONG rolled back: short leg failed");
            return;
        }
        if (!shortOpened) return;

        GreeksResult sg = bsEngine.greeks(price, shortK, RISK_FREE_RATE, T, sigma, true);
        GreeksResult lg = bsEngine.greeks(price, longK,  RISK_FREE_RATE, T, sigma, true);
        researchCallback.accept(String.format(
                "%s BEAR CALL SPREAD short_K=%.0f long_K=%.0f exp=%s x%d credit=%.2f | short: %s | long: %s",
                symbol, shortK, longK, expiry, contracts, netCredit, sg, lg));
    }

    // ── High-Delta Scalp entry ────────────────────────────────────────────────────

    private void tryOpenHighDeltaScalp(String symbol, double price, double K, LocalDate expiry,
                                       double T, double sigma, boolean isCall,
                                       String signalStr, String featureCsv) {
        String posKey = symbol + (isCall ? "_HIGHDELTA_CALL" : "_HIGHDELTA_PUT");

        if (sessionStopLossed.contains(posKey)) {
            researchCallback.accept(symbol + (isCall ? " HIGH-DELTA CALL" : " HIGH-DELTA PUT") + " skip: stop-loss cooldown");
            return;
        }
        if (!isCall && uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " HIGH-DELTA PUT skip: bull market");
            return;
        }

        // Shift strike deep ITM to achieve delta ~0.80+
        double deepK = isCall ? Math.max(1.0, K - DEEP_ITM_OFFSET) : K + DEEP_ITM_OFFSET;

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

        if (isCall) {
            optExec.buyCallAs(posKey, symbol, deepK, nearExpiry, contracts, premium, signalStr, featureCsv);
        } else {
            optExec.buyPutAs(posKey, symbol, deepK, nearExpiry, contracts, premium, signalStr, featureCsv);
        }
        if (!account.getOptionsPositions().containsKey(posKey)) return;

        GreeksResult g = bsEngine.greeks(price, deepK, RISK_FREE_RATE, nearT, sigma, isCall);
        researchCallback.accept(String.format("%s HIGH-DELTA %s K=%.0f (deep ITM) exp=%s x%d prem=%.2f | %s",
                symbol, isCall ? "CALL" : "PUT", deepK, nearExpiry, contracts, premium, g));
    }

    // ── Near-Term Momentum entry ──────────────────────────────────────────────────

    private void tryOpenMomentumNearTerm(String symbol, double price, boolean isCall,
                                         double sigma, String signalStr, String featureCsv) {
        String posKey = symbol + (isCall ? "_NEARTERM_CALL" : "_NEARTERM_PUT");

        if (sessionStopLossed.contains(posKey)) {
            researchCallback.accept(symbol + (isCall ? " NEARTERM CALL" : " NEARTERM PUT") + " skip: stop-loss cooldown");
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

        if (isCall) {
            optExec.buyCallAs(posKey, symbol, K, nearExpiry, contracts, premium, signalStr, featureCsv);
        } else {
            optExec.buyPutAs(posKey, symbol, K, nearExpiry, contracts, premium, signalStr, featureCsv);
        }
        if (!account.getOptionsPositions().containsKey(posKey)) return;

        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, nearT, sigma, isCall);
        researchCallback.accept(String.format("%s NEAR-TERM %s K=%.0f exp=%s x%d prem=%.2f | %s",
                symbol, isCall ? "CALL" : "PUT", K, nearExpiry, contracts, premium, g));
    }

    // ── Zero-DTE Straddle entry ────────────────────────────────────────────────────

    private void tryOpenZeroDTE(String symbol, double price, double K, double sigma,
                                String signalStr, String featureCsv) {
        String callKey    = symbol + "_ZEROTE_CALL";
        String putKey     = symbol + "_ZEROTE_PUT";
        String cooldownKey = symbol + "_ZEROTE";

        if (sessionStopLossed.contains(cooldownKey)) {
            researchCallback.accept(symbol + " ZERO-DTE skip: stop-loss cooldown");
            return;
        }

        LocalDate today = LocalDate.now();
        double T = 0.5 / 365.0; // remaining intraday time value

        double callPremium = resolvePremium(symbol, K, today, true,  T, sigma, price);
        double putPremium  = resolvePremium(symbol, K, today, false, T, sigma, price);

        if (callPremium < MIN_PREMIUM || putPremium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " ZERO-DTE skip: premium too low"
                    + " call=" + String.format("%.4f", callPremium)
                    + " put="  + String.format("%.4f", putPremium));
            return;
        }

        double combinedPremium = callPremium + putPremium;
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (combinedPremium * 100)));
        if (contracts < 1) {
            researchCallback.accept(symbol + " ZERO-DTE skip: insufficient budget for premium "
                    + String.format("%.2f", combinedPremium));
            return;
        }

        optExec.buyCallAs(callKey, symbol, K, today, contracts, callPremium, signalStr, featureCsv);
        optExec.buyPutAs(putKey,  symbol, K, today, contracts, putPremium,  signalStr, featureCsv);

        boolean callOpened = account.getOptionsPositions().containsKey(callKey);
        boolean putOpened  = account.getOptionsPositions().containsKey(putKey);
        if (callOpened && !putOpened) {
            optExec.closePosition(callKey, callPremium, "Rollback: ZERO-DTE put leg did not open");
            researchCallback.accept(symbol + " ZERO-DTE CALL rolled back: put leg failed");
            return;
        }
        if (!callOpened && putOpened) {
            optExec.closePosition(putKey, putPremium, "Rollback: ZERO-DTE call leg did not open");
            researchCallback.accept(symbol + " ZERO-DTE PUT rolled back: call leg failed");
            return;
        }
        if (!callOpened) return;

        GreeksResult cg = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, true);
        GreeksResult pg = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(String.format("%s ZERO-DTE K=%.0f x%d combined=%.2f | call: %s | put: %s",
                symbol, K, contracts, combinedPremium, cg, pg));
    }

    // ── Straddle / Strangle entry ─────────────────────────────────────────────────

    private void tryOpenStraddleOrStrangle(String symbol, double price, double K, LocalDate expiry,
                                           double T, double sigma, List<Double> prices,
                                           String signalStr, String featureCsv) {
        boolean useStraddle  = isLowRelativeIV(prices);
        String  strategyName = useStraddle ? "STRADDLE" : "STRANGLE";
        String  cooldownKey  = symbol + "_" + strategyName;

        if (sessionStopLossed.contains(cooldownKey)) {
            researchCallback.accept(symbol + " " + strategyName + " skip: stop-loss cooldown");
            return;
        }

        double callK, putK;
        if (useStraddle) {
            callK = K;
            putK  = K;
        } else {
            callK = K + STRANGLE_SPREAD;
            putK  = Math.max(STRANGLE_SPREAD, K - STRANGLE_SPREAD);
        }

        String posCallKey = symbol + "_" + strategyName + "_CALL";
        String posPutKey  = symbol + "_" + strategyName + "_PUT";

        double callPremium = resolvePremium(symbol, callK, expiry, true,  T, sigma, price);
        double putPremium  = resolvePremium(symbol, putK,  expiry, false, T, sigma, price);

        if (callPremium < MIN_PREMIUM || putPremium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " " + strategyName + " skip: premium too low"
                    + " call=" + String.format("%.4f", callPremium)
                    + " put="  + String.format("%.4f", putPremium));
            return;
        }

        double combinedPremium = callPremium + putPremium;
        int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (combinedPremium * 100)));
        if (contracts < 1) {
            researchCallback.accept(symbol + " " + strategyName
                    + " skip: insufficient budget for combined premium "
                    + String.format("%.2f", combinedPremium));
            return;
        }

        optExec.buyCallAs(posCallKey, symbol, callK, expiry, contracts, callPremium, signalStr, featureCsv);
        optExec.buyPutAs(posPutKey,   symbol, putK,  expiry, contracts, putPremium,  signalStr, featureCsv);

        boolean callOpened = account.getOptionsPositions().containsKey(posCallKey);
        boolean putOpened  = account.getOptionsPositions().containsKey(posPutKey);

        if (callOpened && !putOpened) {
            optExec.closePosition(posCallKey, callPremium,
                    "Rollback: " + strategyName + " put leg did not open");
            researchCallback.accept(symbol + " " + strategyName + " CALL rolled back: put leg failed");
            return;
        }
        if (!callOpened && putOpened) {
            optExec.closePosition(posPutKey, putPremium,
                    "Rollback: " + strategyName + " call leg did not open");
            researchCallback.accept(symbol + " " + strategyName + " PUT rolled back: call leg failed");
            return;
        }
        if (!callOpened) {
            return;
        }

        GreeksResult cg = bsEngine.greeks(price, callK, RISK_FREE_RATE, T, sigma, true);
        GreeksResult pg = bsEngine.greeks(price, putK,  RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(String.format(
                "%s %s K_call=%.0f K_put=%.0f exp=%s x%d combined=%.2f | call: %s | put: %s",
                symbol, strategyName, callK, putK, expiry, contracts, combinedPremium, cg, pg));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private double resolvePremium(String symbol, double strike, LocalDate expiry,
                                  boolean isCall, double T, double sigma, double price) {
        double bsPremium = isCall
                ? bsEngine.callPrice(price, strike, RISK_FREE_RATE, T, sigma)
                : bsEngine.putPrice(price, strike, RISK_FREE_RATE, T, sigma);

        if (dataClient == null) return bsPremium;

        OptionsChain chain = dataClient.getOptionsChain(symbol, expiry);
        OptionsQuote quote = isCall ? chain.getCall(strike) : chain.getPut(strike);
        if (quote == null || !quote.isValid()) return bsPremium;
        if (!quote.isLiquid()) {
            researchCallback.accept(symbol + (isCall ? " CALL" : " PUT")
                    + " skip: illiquid " + quote.liquidityInfo());
            return 0.0;
        }
        return quote.getAsk();
    }

    private boolean isLowRelativeIV(List<Double> prices) {
        if (prices.size() < IV_RECENT_WINDOW + 2) return false;
        double fullVol   = bsEngine.historicalVol(prices);
        double recentVol = bsEngine.historicalVol(
                prices.subList(prices.size() - IV_RECENT_WINDOW, prices.size()));
        return fullVol > 0 && (recentVol / fullVol) < IV_STRADDLE_THRESHOLD;
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
            stopLossResetDate = today;
        }
    }
}
