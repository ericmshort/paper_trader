package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.OptionsQuote;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.engine.OptionsEvaluator;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Routes options signals to the appropriate strategy:
 *
 *   purelyBullish (buys ≥ 3, sells = 0) → Long Call
 *   purelyBearish (sells ≥ 3, buys = 0) → Long Put
 *   mixedStrong   (buys ≥ 2 + sells ≥ 1, or vice-versa) → Straddle or Strangle
 *
 * Straddle vs Strangle selection: when recent IV (last 10 bars) is below 80% of
 * the full-history IV, the market is relatively calm → Straddle (ATM, more sensitive).
 * Otherwise → Strangle (OTM, cheaper, needs a bigger move).
 */
public class OptionsSignalRouter implements OptionsEvaluator {

    private final BlackScholesEngine bsEngine;
    private final OptionsOrderExecutor optExec;
    private final Account account;
    private final PriceHistory priceHistory;
    private final Consumer<String> researchCallback;
    private final QuoteProvider dataClient;

    private static final double RISK_FREE_RATE = 0.04;
    private static final double MIN_PREMIUM = 0.10;
    private static final double MAX_PORTFOLIO_EXPOSURE = 0.60;
    private static final double IV_SURGE_THRESHOLD = 1.5;
    private static final int    IV_WINDOW = 20;
    private static final double PROFIT_TARGET = 2.0;

    // Straddle vs strangle selection: use straddle when recent/full IV ratio < this threshold
    static final double IV_STRADDLE_THRESHOLD = 0.80;
    static final int    IV_RECENT_WINDOW = 10;
    // OTM offset for strangle strikes (proportional to $5 standard increment)
    static final double STRANGLE_SPREAD = 5.0;

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

        String callKey         = symbol + "_CALL";
        String putKey          = symbol + "_PUT";
        String straddleCallKey = symbol + "_STRADDLE_CALL";
        String straddlePutKey  = symbol + "_STRADDLE_PUT";
        String strangleCallKey = symbol + "_STRANGLE_CALL";
        String stranglePutKey  = symbol + "_STRANGLE_PUT";

        Map<String, OptionsPosition> opts = account.getOptionsPositions();

        // ── 1. Close existing directional positions that have reversed / hit stops ──
        closeDirectionalLeg(opts, callKey, true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, putKey,  false, symbol, price, buySignals,  signalStr);

        // ── 2. Close straddle / strangle on combined stop or profit target ──
        closeMultiLegIfNeeded(opts, straddleCallKey, straddlePutKey, symbol, price, "STRADDLE", signalStr);
        closeMultiLegIfNeeded(opts, strangleCallKey, stranglePutKey, symbol, price, "STRANGLE", signalStr);

        // Re-read after potential closures
        opts = account.getOptionsPositions();

        // ── 3. Guard checks ──
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

        // ── 4. Signal classification ──
        boolean purelyBullish = buySignals >= 3 && sellSignals == 0;
        boolean purelyBearish = sellSignals >= 3 && buySignals == 0;
        boolean mixedStrong   = (buySignals >= 2 && sellSignals >= 1)
                             || (sellSignals >= 2 && buySignals >= 1);

        boolean hasDirectional = opts.containsKey(callKey) || opts.containsKey(putKey);
        boolean hasMultiLeg    = opts.containsKey(straddleCallKey) || opts.containsKey(straddlePutKey)
                              || opts.containsKey(strangleCallKey) || opts.containsKey(stranglePutKey);

        // ── 5. Entry ──
        if (purelyBullish && !hasDirectional && !hasMultiLeg) {
            tryOpenLongCall(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, callKey, opts);

        } else if (purelyBearish && !hasDirectional && !hasMultiLeg) {
            tryOpenLongPut(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, putKey, sellSignals, opts);

        } else if (mixedStrong && !hasDirectional && !hasMultiLeg) {
            tryOpenStraddleOrStrangle(symbol, price, K, expiry, T, sigma, prices, signalStr, featureCsv);
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
        if (nearExpiry)      reason = "Expiry <3 days";
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

    // ── Multi-leg close (straddle / strangle) ─────────────────────────────────────

    private void closeMultiLegIfNeeded(Map<String, OptionsPosition> opts,
                                       String callKey, String putKey,
                                       String symbol, double price,
                                       String strategyName, String signalStr) {
        OptionsPosition callPos = opts.get(callKey);
        OptionsPosition putPos  = opts.get(putKey);
        if (callPos == null || putPos == null) return;

        double sigma = computeVol(symbol);
        double T = bsEngine.timeToExpiry(callPos.getExpiry());

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
        boolean nearExpiry   = callPos.daysToExpiry() < 3;

        if (!premiumStop && !profitTarget && !nearExpiry) return;

        String reason;
        if (nearExpiry)        reason = "Expiry <3 days";
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

    // ── Straddle / Strangle entry ─────────────────────────────────────────────────

    private void tryOpenStraddleOrStrangle(String symbol, double price, double K, LocalDate expiry,
                                           double T, double sigma, List<Double> prices,
                                           String signalStr, String featureCsv) {
        // Select strategy: straddle when IV is relatively quiet (cheap ATM options),
        // strangle when IV is more elevated (OTM options give better risk/reward).
        boolean useStraddle  = isLowRelativeIV(prices);
        String  strategyName = useStraddle ? "STRADDLE" : "STRANGLE";
        String  cooldownKey  = symbol + "_" + strategyName;

        if (sessionStopLossed.contains(cooldownKey)) {
            researchCallback.accept(symbol + " " + strategyName + " skip: stop-loss cooldown");
            return;
        }

        double callK, putK;
        if (useStraddle) {
            callK = K;                                        // ATM call
            putK  = K;                                        // ATM put
        } else {
            callK = K + STRANGLE_SPREAD;                      // OTM call
            putK  = Math.max(STRANGLE_SPREAD, K - STRANGLE_SPREAD); // OTM put
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

        // Atomicity guard: if one leg opened but the other didn't (broker rejection, balance
        // exhausted by slippage, etc.), close the opened leg immediately to avoid a half-open
        // position that neither the multi-leg nor directional close logic would ever manage.
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
            return; // both legs failed; nothing to log
        }

        GreeksResult cg = bsEngine.greeks(price, callK, RISK_FREE_RATE, T, sigma, true);
        GreeksResult pg = bsEngine.greeks(price, putK,  RISK_FREE_RATE, T, sigma, false);
        researchCallback.accept(String.format(
                "%s %s K_call=%.0f K_put=%.0f exp=%s x%d combined=%.2f | call: %s | put: %s",
                symbol, strategyName, callK, putK, expiry, contracts, combinedPremium, cg, pg));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /**
     * Uses market ask if a live quote is available and liquid; falls back to Black-Scholes.
     */
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
            return 0.0; // signal to caller to abort
        }
        return quote.getAsk();
    }

    /**
     * Returns true when recent volatility is below 80% of full-history volatility,
     * indicating a quiet market where ATM (straddle) options are cheaper.
     */
    private boolean isLowRelativeIV(List<Double> prices) {
        if (prices.size() < IV_RECENT_WINDOW + 2) return false;
        double fullVol   = bsEngine.historicalVol(prices);
        double recentVol = bsEngine.historicalVol(
                prices.subList(prices.size() - IV_RECENT_WINDOW, prices.size()));
        return fullVol > 0 && (recentVol / fullVol) < IV_STRADDLE_THRESHOLD;
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
