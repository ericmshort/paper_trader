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

        // Close positions that have reversed or are near expiry
        if (opts.containsKey(callKey)) {
            OptionsPosition pos = opts.get(callKey);
            if (sellSignals >= 2 || pos.daysToExpiry() < 3) {
                double T = bsEngine.timeToExpiry(pos.getExpiry());
                double sigma = computeVol(symbol);
                double currentPremium = sigma > 0 && T > 0
                        ? bsEngine.callPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                        : 0.0;
                String reason = pos.daysToExpiry() < 3 ? "Expiry <3 days" : "Signal reversal: SELL";
                optExec.closePosition(callKey, currentPremium, reason);
                researchCallback.accept(symbol + " CALL closed: " + reason + " prem=" + String.format("%.2f", currentPremium));
            }
        }
        if (opts.containsKey(putKey)) {
            OptionsPosition pos = opts.get(putKey);
            if (buySignals >= 2 || pos.daysToExpiry() < 3) {
                double T = bsEngine.timeToExpiry(pos.getExpiry());
                double sigma = computeVol(symbol);
                double currentPremium = sigma > 0 && T > 0
                        ? bsEngine.putPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                        : 0.0;
                String reason = pos.daysToExpiry() < 3 ? "Expiry <3 days" : "Signal reversal: BUY";
                optExec.closePosition(putKey, currentPremium, reason);
                researchCallback.accept(symbol + " PUT closed: " + reason + " prem=" + String.format("%.2f", currentPremium));
            }
        }

        // Re-read positions map after potential closures
        opts = account.getOptionsPositions();
        List<Double> prices = priceHistory.getPrices(symbol);
        if (prices.size() < 2) return;

        double sigma = bsEngine.historicalVol(prices);
        if (sigma == 0.0) {
            researchCallback.accept(symbol + " options skip: vol=0");
            return;
        }
        LocalDate expiry = bsEngine.nextMonthlyExpiry();
        double K = bsEngine.roundStrike(price);
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) return;

        if (buySignals >= 2 && !opts.containsKey(callKey)) {
            double bsPremium = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
            if (bsPremium <= 0) return;
            double premium = bsPremium;
            String priceSource = "BS";
            if (dataClient != null) {
                OptionsChain chain = dataClient.getOptionsChain(symbol, expiry);
                OptionsQuote quote = chain.getCall(K);
                if (quote != null && quote.isValid()) {
                    premium = quote.getAsk();
                    priceSource = "mkt";
                }
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

        if (sellSignals >= 2 && !opts.containsKey(putKey)) {
            double bsPremium = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma);
            if (bsPremium <= 0) return;
            double premium = bsPremium;
            String priceSource = "BS";
            if (dataClient != null) {
                OptionsChain chain = dataClient.getOptionsChain(symbol, expiry);
                OptionsQuote quote = chain.getPut(K);
                if (quote != null && quote.isValid()) {
                    premium = quote.getAsk();
                    priceSource = "mkt";
                }
            }
            int contracts = Math.min(5, (int) (account.getBalance() * 0.05 / (premium * 100)));
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
