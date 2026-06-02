package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.EarningsCalendar;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TradingLoop implements Runnable {

    static final double SIGNAL_THRESHOLD = 2.5;
    private double maxPortfolioExposure = 0.60;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime PRE_CLOSE_CUTOFF = LocalTime.of(15, 45);
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
    private double dailyLossLimitPct = 0.05;
    private double dayStartValue = -1;
    private LocalDate lastDayTrackingDate;
    private TransactionLog transactionLog;
    private boolean avoidOvernightHolds = true;
    private boolean marketRegimeFilterEnabled = true;
    private EarningsCalendar earningsCalendar;
    private int earningsBlackoutDays = 3;
    private final Map<String, Double> lastKnownPrices = new HashMap<>();
    private final Map<String, LocalDate> dailyBarLastRecorded = new HashMap<>();

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

    public void setDailyLossLimitPct(double pct) { this.dailyLossLimitPct = pct; }
    public void setMaxPortfolioExposure(double fraction) { this.maxPortfolioExposure = fraction; }
    public void setTransactionLog(TransactionLog log) { this.transactionLog = log; }
    public void setAvoidOvernightHolds(boolean v) { this.avoidOvernightHolds = v; }
    public void setMarketRegimeFilterEnabled(boolean v) { this.marketRegimeFilterEnabled = v; }
    public boolean isUptrend() { return isMarketInUptrend(); }
    public void setEarningsCalendar(EarningsCalendar cal) { this.earningsCalendar = cal; }
    public void setEarningsBlackoutDays(int days) { this.earningsBlackoutDays = days; }

    @Override
    public void run() {
        try {
            ZonedDateTime now = clock.get();
            LocalTime time = now.toLocalTime();
            LocalDate today = now.toLocalDate();
            if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
                researchCallback.accept("Market closed. Next open: " + MARKET_OPEN + " ET.");
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
            if (!today.equals(lastDayTrackingDate)) {
                lastDayTrackingDate = today;
                // Prefer last_equity from the broker (equity at prior close) — the authoritative
                // start-of-day value. Fall back to computing balance + positions locally for
                // simulated brokers that don't provide it.
                double lastEquity = account.getLastEquity();
                if (lastEquity > 0) {
                    dayStartValue = lastEquity;
                } else {
                    double stockMV = account.getPositions().values().stream()
                            .mapToDouble(Position::getMarketValue).sum();
                    dayStartValue = account.getBalance() + stockMV;
                }
                account.setDailyLossHalted(false);
            }
            List<QuoteModel> quotes = dataClient.getQuotes(watchList);
            for (QuoteModel quote : quotes) {
                String symbol = quote.getSymbol();
                double price = quote.getPrice();
                double volume = quote.getVolume();
                lastKnownPrices.put(symbol, price);
                priceHistory.record(symbol, price, volume);
                // Gap 6: one daily bar per symbol per trading day — keeps indicators on daily cadence
                if (!today.equals(dailyBarLastRecorded.get(symbol))) {
                    priceHistory.recordDaily(symbol, price, volume);
                    dailyBarLastRecorded.put(symbol, today);
                }
                account.updatePositionPrice(symbol, price);
                if (!account.isDailyLossHalted() && dailyLossLimitPct > 0 && dayStartValue > 0) {
                    double optionsCostBasis = account.getOptionsPositions().values().stream()
                            .filter(p -> p.getContracts() > 0)
                            .mapToDouble(p -> p.getPremiumPaid() * 100 * p.getContracts())
                            .sum();
                    // Use market value (not just unrealized PnL) so that open stock positions
                    // don't appear as losses: buying $14k of stock reduces cash by $14k but
                    // adds $14k of market value — net portfolio value is unchanged.
                    double stockValue = account.getPositions().values().stream()
                            .mapToDouble(Position::getMarketValue).sum();
                    double currentValue = account.getBalance() + stockValue + optionsCostBasis;
                    if (currentValue < dayStartValue * (1 - dailyLossLimitPct)) {
                        account.setDailyLossHalted(true);
                        researchCallback.accept(String.format(
                                "DAILY LOSS LIMIT (%.0f%%) reached — no new positions for the rest of the session",
                                dailyLossLimitPct * 100));
                    }
                }
                // Gap 6: use daily-resolution prices for indicators; fall back to intraday if not yet seeded
                List<Double> dailyPs = priceHistory.getDailyPrices(symbol);
                List<Double> dailyVs = priceHistory.getDailyVolumes(symbol);
                List<Double> prices  = dailyPs.isEmpty() ? priceHistory.getPrices(symbol)  : dailyPs;
                List<Double> volumes = dailyVs.isEmpty() ? priceHistory.getVolumes(symbol) : dailyVs;
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

                // ── Multi-indicator ───────────────────────────────────────────────────
                if (trailingStop.check(symbol, price) && hasPosition) {
                    Position pos = account.getPositions().get(symbol);
                    brokerClient.submitSell(symbol, pos.getQuantity(), price, signalStr, "Trailing stop: 5% drawdown from peak");
                    account.removePosition(symbol);
                    trailingStop.reset(symbol);
                    uiRefreshCallback.run();
                } else if (weightedSells >= SIGNAL_THRESHOLD && hasPosition) {
                    Position pos = account.getPositions().get(symbol);
                    brokerClient.submitSell(symbol, pos.getQuantity(), price, signalStr, "Signals: " + sells + "/" + signals.size() + " SELL");
                    account.removePosition(symbol);
                    trailingStop.reset(symbol);
                    uiRefreshCallback.run();
                } else if (weightedBuys >= SIGNAL_THRESHOLD && !hasPosition) {
                    int daysToEarnings = earningsCalendar != null
                            ? earningsCalendar.daysUntilEarnings(symbol) : Integer.MAX_VALUE;
                    if (!isMarketInUptrend()) {
                        researchCallback.accept(symbol + " BUY skipped: SPY below 50-day MA (bear regime)");
                    } else if (daysToEarnings <= earningsBlackoutDays) {
                        researchCallback.accept(symbol + " BUY skipped: earnings in "
                                + daysToEarnings + " day" + (daysToEarnings == 1 ? "" : "s"));
                    } else if (account.totalExposureFraction() >= maxPortfolioExposure) {
                        researchCallback.accept(symbol + " BUY skipped: portfolio at capacity ("
                                + String.format("%.0f%%", account.totalExposureFraction() * 100)
                                + " deployed, limit " + String.format("%.0f%%", maxPortfolioExposure * 100) + ")");
                    } else if (account.isDailyLossHalted()) {
                        researchCallback.accept(symbol + " BUY skipped: daily loss limit active");
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
            // Gap 5: liquidate all equity positions at 15:45 to avoid overnight gap risk
            if (avoidOvernightHolds && !time.isBefore(PRE_CLOSE_CUTOFF)) {
                for (String sym : new ArrayList<>(account.getPositions().keySet())) {
                    Position pos = account.getPositions().get(sym);
                    if (pos == null) continue;
                    double closePrice = lastKnownPrices.getOrDefault(sym, pos.getCurrentPrice());
                    brokerClient.submitSell(sym, pos.getQuantity(), closePrice, "",
                            "Pre-close: avoid overnight hold");
                    trailingStop.reset(sym);
                    researchCallback.accept(sym + " closed at $" + String.format("%.2f", closePrice)
                            + ": pre-close overnight avoidance");
                }
            }
            uiRefreshCallback.run();
        } catch (Exception e) {
            researchCallback.accept("TradingLoop error: " + e.getMessage());
        }
    }

    private boolean isMarketInUptrend() {
        if (!marketRegimeFilterEnabled) return true;
        List<Double> spyPrices = priceHistory.getDailyPrices("SPY");
        if (spyPrices.size() < 50) return true; // insufficient data — don't block trades
        double ma50 = spyPrices.subList(spyPrices.size() - 50, spyPrices.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return spyPrices.get(spyPrices.size() - 1) >= ma50;
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
