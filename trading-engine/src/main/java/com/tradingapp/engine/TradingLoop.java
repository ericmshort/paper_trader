package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.Position;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TradingLoop implements Runnable {

    static final int SIGNAL_THRESHOLD = 2;
    static final double MAX_PORTFOLIO_EXPOSURE = 0.60;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final QuoteProvider dataClient;
    private final PriceHistory priceHistory;
    private final IndicatorEngine indicators;
    private final TrailingStopMonitor trailingStop;
    private final BrokerClient brokerClient;
    private final FeeCalculator fees;
    private final List<String> watchList;
    private final Consumer<String> researchCallback;
    private final Runnable uiRefreshCallback;
    private final Account account;
    private final Supplier<ZonedDateTime> clock;
    private final OptionsEvaluator optionsEvaluator;
    private final SignalWeightEvaluator weightEvaluator;
    private final Runnable afterMarketCallback;
    private LocalDate lastTrainingDate;

    public TradingLoop(QuoteProvider dataClient, PriceHistory priceHistory,
                       IndicatorEngine indicators, TrailingStopMonitor trailingStop,
                       BrokerClient brokerClient, FeeCalculator fees,
                       List<String> watchList, Consumer<String> researchCallback,
                       Runnable uiRefreshCallback, Account account) {
        this(dataClient, priceHistory, indicators, trailingStop, brokerClient, fees,
             watchList, researchCallback, uiRefreshCallback, account,
             () -> ZonedDateTime.now(ET), null, null, null);
    }

    public TradingLoop(QuoteProvider dataClient, PriceHistory priceHistory,
                       IndicatorEngine indicators, TrailingStopMonitor trailingStop,
                       BrokerClient brokerClient, FeeCalculator fees,
                       List<String> watchList, Consumer<String> researchCallback,
                       Runnable uiRefreshCallback, Account account,
                       OptionsEvaluator optionsEvaluator) {
        this(dataClient, priceHistory, indicators, trailingStop, brokerClient, fees,
             watchList, researchCallback, uiRefreshCallback, account,
             () -> ZonedDateTime.now(ET), optionsEvaluator, null, null);
    }

    public TradingLoop(QuoteProvider dataClient, PriceHistory priceHistory,
                       IndicatorEngine indicators, TrailingStopMonitor trailingStop,
                       BrokerClient brokerClient, FeeCalculator fees,
                       List<String> watchList, Consumer<String> researchCallback,
                       Runnable uiRefreshCallback, Account account,
                       OptionsEvaluator optionsEvaluator,
                       SignalWeightEvaluator weightEvaluator,
                       Runnable afterMarketCallback) {
        this(dataClient, priceHistory, indicators, trailingStop, brokerClient, fees,
             watchList, researchCallback, uiRefreshCallback, account,
             () -> ZonedDateTime.now(ET), optionsEvaluator, weightEvaluator, afterMarketCallback);
    }

    TradingLoop(QuoteProvider dataClient, PriceHistory priceHistory,
                IndicatorEngine indicators, TrailingStopMonitor trailingStop,
                BrokerClient brokerClient, FeeCalculator fees,
                List<String> watchList, Consumer<String> researchCallback,
                Runnable uiRefreshCallback, Account account,
                Supplier<ZonedDateTime> clock) {
        this(dataClient, priceHistory, indicators, trailingStop, brokerClient, fees,
             watchList, researchCallback, uiRefreshCallback, account, clock, null, null, null);
    }

    TradingLoop(QuoteProvider dataClient, PriceHistory priceHistory,
                IndicatorEngine indicators, TrailingStopMonitor trailingStop,
                BrokerClient brokerClient, FeeCalculator fees,
                List<String> watchList, Consumer<String> researchCallback,
                Runnable uiRefreshCallback, Account account,
                Supplier<ZonedDateTime> clock, OptionsEvaluator optionsEvaluator) {
        this(dataClient, priceHistory, indicators, trailingStop, brokerClient, fees,
             watchList, researchCallback, uiRefreshCallback, account, clock, optionsEvaluator, null, null);
    }

    TradingLoop(QuoteProvider dataClient, PriceHistory priceHistory,
                IndicatorEngine indicators, TrailingStopMonitor trailingStop,
                BrokerClient brokerClient, FeeCalculator fees,
                List<String> watchList, Consumer<String> researchCallback,
                Runnable uiRefreshCallback, Account account,
                Supplier<ZonedDateTime> clock, OptionsEvaluator optionsEvaluator,
                SignalWeightEvaluator weightEvaluator, Runnable afterMarketCallback) {
        this.dataClient = dataClient;
        this.priceHistory = priceHistory;
        this.indicators = indicators;
        this.trailingStop = trailingStop;
        this.brokerClient = brokerClient;
        this.fees = fees;
        this.watchList = watchList;
        this.researchCallback = researchCallback;
        this.uiRefreshCallback = uiRefreshCallback;
        this.account = account;
        this.clock = clock;
        this.optionsEvaluator = optionsEvaluator;
        this.weightEvaluator = weightEvaluator;
        this.afterMarketCallback = afterMarketCallback;
    }

    @Override
    public void run() {
        try {
            ZonedDateTime now = clock.get();
            LocalTime time = now.toLocalTime();
            if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
                researchCallback.accept("Market closed. Next open: " + MARKET_OPEN + " ET.");
                LocalDate today = now.toLocalDate();
                if (!time.isBefore(MARKET_CLOSE) && afterMarketCallback != null
                        && !today.equals(lastTrainingDate)) {
                    lastTrainingDate = today;
                    afterMarketCallback.run();
                }
                return;
            }
            if (account.isTradingHalted()) {
                researchCallback.accept("TRADING HALTED — balance below $100.");
                return;
            }
            brokerClient.syncAccount(account);
            List<QuoteModel> quotes = dataClient.getQuotes(watchList);
            for (QuoteModel quote : quotes) {
                String symbol = quote.getSymbol();
                double price = quote.getPrice();
                double volume = quote.getVolume();
                priceHistory.record(symbol, price, volume);
                List<Double> prices = priceHistory.getPrices(symbol);
                List<Double> volumes = priceHistory.getVolumes(symbol);
                List<SignalResult> signals = indicators.evaluateAll(prices, volumes, price);
                int buys = indicators.countBuySignals(signals);
                int sells = indicators.countSellSignals(signals);
                double weightedBuys = weightEvaluator != null
                        ? weightEvaluator.weightedBuyScore(signals) : (double) buys;
                double weightedSells = weightEvaluator != null
                        ? weightEvaluator.weightedSellScore(signals) : (double) sells;
                String signalStr = signals.stream().map(SignalResult::toString).collect(Collectors.joining(", "));
                String featureCsv = extractFeatureCsv(signals);
                boolean hasPosition = account.getPositions().containsKey(symbol);
                if (trailingStop.check(symbol, price) && hasPosition) {
                    Position pos = account.getPositions().get(symbol);
                    brokerClient.submitSell(symbol, pos.getQuantity(), price, signalStr, "Trailing stop: 5% drawdown from peak");
                    uiRefreshCallback.run();
                } else if (weightedSells >= SIGNAL_THRESHOLD && hasPosition) {
                    Position pos = account.getPositions().get(symbol);
                    brokerClient.submitSell(symbol, pos.getQuantity(), price, signalStr, "Signals: " + sells + "/" + signals.size() + " SELL");
                    uiRefreshCallback.run();
                } else if (weightedBuys >= SIGNAL_THRESHOLD && !hasPosition) {
                    if (account.totalExposureFraction() >= MAX_PORTFOLIO_EXPOSURE) {
                        researchCallback.accept(symbol + " BUY skipped: portfolio at capacity ("
                                + String.format("%.0f%%", account.totalExposureFraction() * 100) + " deployed)");
                    } else {
                        int shares = fees.maxShares(account.getBalance(), price);
                        if (shares > 0) {
                            brokerClient.submitBuy(symbol, shares, price, signalStr,
                                    "Signals: " + buys + "/" + signals.size() + " BUY", featureCsv);
                            uiRefreshCallback.run();
                        }
                    }
                }
                trailingStop.updatePeak(symbol, price);
                if (optionsEvaluator != null) {
                    optionsEvaluator.evaluate(symbol, price, buys, sells, signalStr, featureCsv);
                }
                researchCallback.accept(time + " | " + symbol + " $" + String.format("%.2f", price)
                        + " | " + signalStr + " | BUY=" + buys + " SELL=" + sells);
            }
            uiRefreshCallback.run();
        } catch (Exception e) {
            researchCallback.accept("TradingLoop error: " + e.getMessage());
        }
    }

    private static final String[] FEATURE_NAMES = {"RSI", "MACD", "BollingerBands", "MACrossover", "VolumeSurge"};

    private String extractFeatureCsv(List<SignalResult> signals) {
        double[] vals = new double[FEATURE_NAMES.length];
        for (SignalResult s : signals) {
            for (int i = 0; i < FEATURE_NAMES.length; i++) {
                if (FEATURE_NAMES[i].equals(s.getIndicatorName())) {
                    vals[i] = s.getValue();
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vals[i]);
        }
        return sb.toString();
    }
}
