package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.OptionsQuote;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.engine.OptionsEvaluator;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OptionsSignalRouter implements OptionsEvaluator {

    private final BlackScholesEngine bsEngine;
    private final OptionsOrderExecutor optExec;
    private final Account account;
    private final PriceHistory priceHistory;
    private final Consumer<String> researchCallback;
    private final QuoteProvider dataClient;

    private static final double RISK_FREE_RATE = 0.04;
    private static final double MIN_PREMIUM = 0.10; // $0.10/share minimum; below this the contract is junk
    private static final double MAX_PORTFOLIO_EXPOSURE = 0.60;
    private static final double IV_SURGE_THRESHOLD = 1.5;
    private static final int IV_WINDOW = 20;
    private static final double PROFIT_TARGET = 2.0;

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
    public void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr, String featureCsv) {
        String callKey = symbol + "_CALL";
        String putKey  = symbol + "_PUT";
        Map<String, OptionsPosition> opts = account.getOptionsPositions();

        // Close positions that have reversed, are near expiry, or have lost 50% of premium
        if (opts.containsKey(callKey)) {
            OptionsPosition pos = opts.get(callKey);
            double T = bsEngine.timeToExpiry(pos.getExpiry());
            double sigma = computeVol(symbol);
            double currentPremium = sigma > 0 && T > 0
                    ? bsEngine.callPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                    : 0.0;
            boolean premiumStop = currentPremium > 0 && currentPremium <= pos.getPremiumPaid() * 0.50;
            boolean profitTarget = currentPremium >= pos.getPremiumPaid() * PROFIT_TARGET;
            if (sellSignals >= 2 || pos.daysToExpiry() < 3 || premiumStop || profitTarget) {
                String reason;
                if (pos.daysToExpiry() < 3) reason = "Expiry <3 days";
                else if (profitTarget) reason = String.format("Profit target: %.2f >= 2x of %.2f", currentPremium, pos.getPremiumPaid());
                else if (premiumStop) reason = String.format("Premium stop-loss: %.2f <= 50%% of %.2f", currentPremium, pos.getPremiumPaid());
                else reason = "Signal reversal: SELL";
                optExec.closePosition(callKey, currentPremium, reason);
                researchCallback.accept(symbol + " CALL closed: " + reason + " prem=" + String.format("%.2f", currentPremium));
            }
        }
        if (opts.containsKey(putKey)) {
            OptionsPosition pos = opts.get(putKey);
            double T = bsEngine.timeToExpiry(pos.getExpiry());
            double sigma = computeVol(symbol);
            double currentPremium = sigma > 0 && T > 0
                    ? bsEngine.putPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                    : 0.0;
            boolean premiumStop = currentPremium > 0 && currentPremium <= pos.getPremiumPaid() * 0.50;
            boolean profitTarget = currentPremium >= pos.getPremiumPaid() * PROFIT_TARGET;
            if (buySignals >= 2 || pos.daysToExpiry() < 3 || premiumStop || profitTarget) {
                String reason;
                if (pos.daysToExpiry() < 3) reason = "Expiry <3 days";
                else if (profitTarget) reason = String.format("Profit target: %.2f >= 2x of %.2f", currentPremium, pos.getPremiumPaid());
                else if (premiumStop) reason = String.format("Premium stop-loss: %.2f <= 50%% of %.2f", currentPremium, pos.getPremiumPaid());
                else reason = "Signal reversal: BUY";
                optExec.closePosition(putKey, currentPremium, reason);
                researchCallback.accept(symbol + " PUT closed: " + reason + " prem=" + String.format("%.2f", currentPremium));
            }
        }

        // Re-read positions map after potential closures
        opts = account.getOptionsPositions();
        List<Double> prices = priceHistory.getPrices(symbol);
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
            double recentVol = bsEngine.historicalVol(prices.subList(prices.size() - IV_WINDOW, prices.size()));
            if (recentVol > sigma * IV_SURGE_THRESHOLD) {
                researchCallback.accept(symbol + " options skip: IV surge " + String.format("%.2f", recentVol)
                        + " > " + String.format("%.2f", sigma * IV_SURGE_THRESHOLD));
                return;
            }
        }
        LocalDate expiry = bsEngine.nextMonthlyExpiry();
        double K = bsEngine.roundStrike(price);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        if (buySignals >= 2 && !opts.containsKey(callKey)) {
            if (account.getPositions().containsKey(symbol)) {
                researchCallback.accept(symbol + " CALL skip: equity position already open (avoid double-dip)");
            } else {
                double bsPremium = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (bsPremium < MIN_PREMIUM) {
                    researchCallback.accept(symbol + " CALL skip: premium too low (" + String.format("%.4f", bsPremium) + ")");
                    return;
                }
                double premium = bsPremium;
                String priceSource = "BS";
                if (dataClient != null) {
                    OptionsChain chain = dataClient.getOptionsChain(symbol, expiry);
                    OptionsQuote quote = chain.getCall(K);
                    if (quote != null && quote.isValid()) {
                        if (!quote.isLiquid()) {
                            researchCallback.accept(symbol + " CALL skip: illiquid " + quote.liquidityInfo());
                            return;
                        }
                        premium = quote.getAsk();
                        priceSource = "mkt " + quote.liquidityInfo();
                    }
                }
                if (premium < MIN_PREMIUM) {
                    researchCallback.accept(symbol + " CALL skip: market ask too low (" + String.format("%.4f", premium) + ")");
                    return;
                }
                int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (premium * 100)));
                if (contracts >= 1) {
                    optExec.buyCall(symbol, K, expiry, contracts, premium, signalStr, featureCsv);
                    GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, true);
                    researchCallback.accept(symbol + " CALL K=" + K + " exp=" + expiry
                            + " x" + contracts + " ask=" + String.format("%.2f", premium) + " [" + priceSource + "]"
                            + " | " + g.toString());
                }
            }
        }

        if (sellSignals >= 2 && !opts.containsKey(putKey)) {
            // Protective put on existing equity uses half the normal budget to avoid over-exposure
            double optionsBudget = account.getPositions().containsKey(symbol)
                    ? account.getBalance() * 0.025
                    : account.getBalance() * 0.05;
            double bsPremium = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma);
            if (bsPremium < MIN_PREMIUM) {
                researchCallback.accept(symbol + " PUT skip: premium too low (" + String.format("%.4f", bsPremium) + ")");
                return;
            }
            double premium = bsPremium;
            String priceSource = "BS";
            if (dataClient != null) {
                OptionsChain chain = dataClient.getOptionsChain(symbol, expiry);
                OptionsQuote quote = chain.getPut(K);
                if (quote != null && quote.isValid()) {
                    if (!quote.isLiquid()) {
                        researchCallback.accept(symbol + " PUT skip: illiquid " + quote.liquidityInfo());
                        return;
                    }
                    premium = quote.getAsk();
                    priceSource = "mkt " + quote.liquidityInfo();
                }
            }
            if (premium < MIN_PREMIUM) {
                researchCallback.accept(symbol + " PUT skip: market ask too low (" + String.format("%.4f", premium) + ")");
                return;
            }
            int contracts = Math.min(5, (int) (optionsBudget / (premium * 100)));
            if (contracts >= 1) {
                optExec.buyPut(symbol, K, expiry, contracts, premium, signalStr, featureCsv);
                GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, false);
                researchCallback.accept(symbol + " PUT K=" + K + " exp=" + expiry
                        + " x" + contracts + " ask=" + String.format("%.2f", premium) + " [" + priceSource + "]"
                        + " | " + g.toString());
            }
        }
    }

    private double computeVol(String symbol) {
        List<Double> prices = priceHistory.getPrices(symbol);
        return prices.size() < 2 ? 0.0 : bsEngine.historicalVol(prices);
    }
}
