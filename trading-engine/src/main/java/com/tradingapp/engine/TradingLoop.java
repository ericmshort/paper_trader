package com.tradingapp.engine;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.CandleBar;
import com.tradingapp.data.CandleHistory;
import com.tradingapp.data.EarningsCalendar;
import com.tradingapp.data.NewsSentimentCache;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.data.SentimentDirection;
import com.tradingapp.data.SentimentScore;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class TradingLoop implements Runnable {

    static final double SIGNAL_THRESHOLD = 2.5;
    // Sell threshold is higher than buy — signal sells had a 4% win rate at 2.5, meaning they
    // were cutting winners early. Requiring stronger consensus before exiting on signals alone.
    private double sellSignalThreshold = 4.0;
    private double maxPortfolioExposure = 0.60;
    private static final LocalTime MARKET_OPEN      = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE     = LocalTime.of(16, 0);
    private static final LocalTime PRE_CLOSE_CUTOFF = LocalTime.of(15, 55);
    // No new entries during the first 5 minutes — the opening range is still forming
    private static final LocalTime ORB_FORMATION_END = LocalTime.of(9, 35);
    // No new stock entries after 2:30 PM — gives every position at least 75 min of runway
    // before the 3:45 forced sweep. Entries after 2:30 have too little time to recover from a bad tick.
    private static final LocalTime LAST_ENTRY_TIME = LocalTime.of(14, 30);
    // Cap on simultaneous stock positions — configurable for backtesting; default mirrors live trading
    private int maxConcurrentStockPositions = 5;
    // VIX above this level means panic/spreads blow out — skip new directional entries
    private static final double VIX_BLOCK_THRESHOLD = 35.0;
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
    private OptionsEvaluator premiumSellerEvaluator = null;
    private final SignalWeightEvaluator weightEvaluator;
    private final Runnable afterMarketCallback;
    private LocalDate lastTrainingDate;
    private LocalDate lastMarketClosedLoggedDate;
    private double dailyLossLimitPct = 0.05;
    // When true, short options are valued at current market price (not ignored) in the circuit breaker.
    private boolean accurateOptionsValuation = false;
    private double dayStartValue = -1;
    private LocalDate lastDayTrackingDate;
    private TransactionLog transactionLog;
    private boolean avoidOvernightHolds = true;
    private boolean marketRegimeFilterEnabled = true;
    private int regimeMaDays = 5;
    // Extra weighted-buy signals required on top of SIGNAL_THRESHOLD to override the regime filter.
    // MAX_VALUE = strict (never override, matches live default). 1 = allow buy if weightedBuys >= SIGNAL_THRESHOLD+1, etc.
    private int regimeOverrideExtraBuys = Integer.MAX_VALUE;
    private Set<String> inverseEtfSymbols = Set.of();
    private boolean stockTradingEnabled = true;
    private EarningsCalendar earningsCalendar;
    private int earningsBlackoutDays = 3;
    private int minHoldMinutes = 15;
    private final int lossCooldownMinutes = 60;
    private final Map<String, Double> lastKnownPrices = new HashMap<>();
    private final Map<String, LocalDate> dailyBarLastRecorded = new HashMap<>();
    private final Map<String, Double> dailyOpenPrices = new HashMap<>();
    private final Map<String, ZonedDateTime> entryTimes = new HashMap<>();
    private final Map<String, Double> entryPrices = new HashMap<>();
    private final Map<String, ZonedDateTime> lossCooldowns = new HashMap<>();
    private final Map<String, Boolean> prevOrbBuy = new HashMap<>();
    private final Map<String, SignalResult.Direction> prevVwapSide = new HashMap<>();
    private double maxLossPerTradePct = 0.003;
    private double circuitBreakerPct = 0.02;
    private boolean circuitBreakerFired = false;
    // How many consecutive bars the portfolio must remain below the loss limit before the halt fires.
    // 0 = immediate (current behavior). 1 = give it 1 bar (~1 min) to recover first.
    private int lossLimitRecoveryBars = 0;
    private int lossLimitBreachCount  = 0;
    private final java.util.concurrent.atomic.AtomicBoolean dailyLossResetRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private Set<String> stockWatchlist   = null; // null = all symbols eligible
    private Set<String> optionsWatchlist = null; // null = all symbols eligible for options
    private CandleHistory candleHistory;
    private NewsSentimentCache sentimentCache;

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

    /** Thread-safe: may be called from any thread (e.g. FX button click). */
    public void resetDailyLossHalt() {
        account.setDailyLossHalted(false);
        dailyLossResetRequested.set(true);
    }
    public void setLossLimitRecoveryBars(int n)  { this.lossLimitRecoveryBars = n; }
    public void setAccurateOptionsValuation(boolean v) { this.accurateOptionsValuation = v; }
    public void setMaxPortfolioExposure(double fraction) { this.maxPortfolioExposure = fraction; }
    public void setTransactionLog(TransactionLog log) { this.transactionLog = log; }
    public void setAvoidOvernightHolds(boolean v) { this.avoidOvernightHolds = v; }
    public void setMarketRegimeFilterEnabled(boolean v) { this.marketRegimeFilterEnabled = v; }
    public void setRegimeMaDays(int days) { this.regimeMaDays = Math.max(2, days); }
    public void setRegimeOverrideExtraBuys(int extra) { this.regimeOverrideExtraBuys = extra; }
    public void setInverseEtfSymbols(Set<String> symbols) { this.inverseEtfSymbols = symbols; }
    public void setMaxConcurrentStockPositions(int n) { this.maxConcurrentStockPositions = n; }
    public void setStockTradingEnabled(boolean v) { this.stockTradingEnabled = v; }
    public boolean isUptrend() { return isMarketInUptrend(); }
    public void setEarningsCalendar(EarningsCalendar cal) { this.earningsCalendar = cal; }
    public void setEarningsBlackoutDays(int days) { this.earningsBlackoutDays = days; }
    public void setMinHoldMinutes(int minutes) { this.minHoldMinutes = minutes; }
    public void setCandleHistory(CandleHistory history) { this.candleHistory = history; }
    public void setNewsSentimentCache(NewsSentimentCache cache) { this.sentimentCache = cache; }
    public void setTrailingStopPct(double pct) { trailingStop.setTrailingStopPct(pct); }
    public void setMaxLossPerTradePct(double pct) { this.maxLossPerTradePct = pct; }
    public void setCircuitBreakerPct(double pct) { this.circuitBreakerPct = pct; }
    public void setSellSignalThreshold(double threshold) { this.sellSignalThreshold = threshold; }
    public void setPremiumSellerEvaluator(OptionsEvaluator e) { this.premiumSellerEvaluator = e; }

    public void setStockWatchlist(java.util.Collection<String> symbols) {
        this.stockWatchlist = symbols == null || symbols.isEmpty() ? null : new HashSet<>(symbols);
    }

    public void setOptionsWatchlist(java.util.Collection<String> symbols) {
        this.optionsWatchlist = symbols == null || symbols.isEmpty() ? null : new HashSet<>(symbols);
    }

    @Override
    public void run() {
        try {
            ZonedDateTime now = clock.get();
            LocalTime time = now.toLocalTime();
            LocalDate today = now.toLocalDate();
            if (time.isBefore(MARKET_OPEN) || time.isAfter(MARKET_CLOSE)) {
                // Log once per day — avoid flooding the research area every 5 seconds.
                if (!today.equals(lastMarketClosedLoggedDate)) {
                    lastMarketClosedLoggedDate = today;
                    researchCallback.accept("Market closed. Next open: " + MARKET_OPEN + " ET.");
                }
                if (!time.isBefore(MARKET_CLOSE) && afterMarketCallback != null
                        && !today.equals(lastTrainingDate)) {
                    lastTrainingDate = today;
                    afterMarketCallback.run();
                }
                uiRefreshCallback.run();
                return;
            }
            boolean orbFormationPeriod = time.isBefore(ORB_FORMATION_END);
            if (account.isTradingHalted()) {
                researchCallback.accept("TRADING HALTED — balance below $100.");
                return;
            }
            brokerClient.syncAccount(account);
            if (!account.isBrokerSyncComplete()) {
                researchCallback.accept("WARNING: Broker sync did not complete — trading suspended this tick.");
                uiRefreshCallback.run();
                return;
            }
            if (!today.equals(lastDayTrackingDate)) {
                lastDayTrackingDate = today;
                // Credit spreads (premium seller positions) are exempt from the daily loss limit.
                // Their cash flows are tracked in premiumCashBalance so that
                // (balance - premiumCash) + nonPremiumOptAdj strips them out entirely.
                double optAdj = account.getNonPremiumOptionsOptAdj();
                double stockMV = account.getPositions().values().stream()
                        .mapToDouble(Position::getMarketValue).sum();
                dayStartValue = (account.getBalance() - account.getPremiumCashBalance()) + optAdj + stockMV;
                account.setDailyLossHalted(false);
                lossLimitBreachCount = 0;
                lossCooldowns.clear();
                prevOrbBuy.clear();
                prevVwapSide.clear();
                circuitBreakerFired = false;
            }
            List<QuoteModel> quotes = dataClient.getQuotes(watchList);

            // Pass 1: update all stock prices and mark all options to market before the loss
            // check runs. Without this, the loss limit sees last tick's option prices for most
            // symbols, so per-position stop-losses can drain well beyond 5% before the halt
            // fires — the halt only catches it mid-loop when enough positions have been closed.
            for (QuoteModel quote : quotes) {
                String symbol = quote.getSymbol();
                double price = quote.getPrice();
                double volume = quote.getVolume();
                lastKnownPrices.put(symbol, price);
                priceHistory.record(symbol, price, volume);
                if (!today.equals(dailyBarLastRecorded.get(symbol))) {
                    dailyOpenPrices.put(symbol, price);
                    priceHistory.recordDaily(symbol, price, volume);
                    dailyBarLastRecorded.put(symbol, today);
                }
                account.updatePositionPrice(symbol, price);
                if (optionsEvaluator != null && !account.isDailyLossHalted()) {
                    optionsEvaluator.markPositionsToMarket(symbol, price);
                }
                if (premiumSellerEvaluator != null && !account.isDailyLossHalted()) {
                    premiumSellerEvaluator.markPositionsToMarket(symbol, price);
                }
            }

            // Honor a manual daily-loss reset requested from the UI (e.g. new paper account).
            if (dailyLossResetRequested.compareAndSet(true, false)) {
                account.setDailyLossHalted(false);
                lossLimitBreachCount = 0;
                double rstOptAdj = account.getNonPremiumOptionsOptAdj();
                double rstStockMV = account.getPositions().values().stream()
                        .mapToDouble(Position::getMarketValue).sum();
                dayStartValue = (account.getBalance() - account.getPremiumCashBalance()) + rstOptAdj + rstStockMV;
                // Sync the capacity baseline used by PremiumSellerRouter so it reflects the
                // new account state (e.g. after a paper account reset) rather than the old
                // last_equity from the previous account.
                account.setLastEquity(dayStartValue);
                researchCallback.accept(String.format(
                        "Daily loss limit reset — new baseline: $%.0f", dayStartValue));
            }

            // Daily loss check: credit spreads (premium seller positions) are fully exempt —
            // their cash flows are tracked in premiumCashBalance and subtracted from the
            // balance before comparison. Only stock unrealized P&L and non-premium option
            // cost-basis changes can trigger this limit.
            if (!account.isDailyLossHalted() && dailyLossLimitPct > 0 && dayStartValue > 0) {
                double optAdj = account.getNonPremiumOptionsOptAdj();
                double stockValue = account.getPositions().values().stream()
                        .mapToDouble(Position::getMarketValue).sum();
                double currentValue = (account.getBalance() - account.getPremiumCashBalance()) + optAdj + stockValue;
                if (currentValue < dayStartValue * (1 - dailyLossLimitPct)) {
                    lossLimitBreachCount++;
                    if (lossLimitBreachCount > lossLimitRecoveryBars) {
                        account.setDailyLossHalted(true);
                        researchCallback.accept(String.format(
                                "DAILY LOSS LIMIT (%.0f%%) reached — no new positions for the rest of the session",
                                dailyLossLimitPct * 100));
                    }
                } else {
                    lossLimitBreachCount = 0;
                }
            }

            // Circuit breaker: auto-liquidate all stock positions if portfolio drops circuitBreakerPct.
            if (!circuitBreakerFired && circuitBreakerPct > 0 && dayStartValue > 0
                    && !account.isDailyLossHalted()) {
                double cbOptValue = accurateOptionsValuation
                        ? account.getOptionsPositions().values().stream()
                                .mapToDouble(p -> {
                                    double mp = p.getCurrentMarketPrice();
                                    return (mp >= 0 ? mp : (p.getContracts() > 0 ? p.getPremiumPaid() : 0.0))
                                            * 100 * p.getContracts();
                                }).sum()
                        : account.getOptionsPositions().values().stream()
                                .filter(p -> p.getContracts() > 0)
                                .mapToDouble(p -> p.getPremiumPaid() * 100 * p.getContracts()).sum();
                double cbStkValue = account.getPositions().values().stream()
                        .mapToDouble(Position::getMarketValue).sum();
                double cbCurrentValue = account.getBalance() + cbStkValue + cbOptValue;
                if (cbCurrentValue < dayStartValue * (1 - circuitBreakerPct)) {
                    circuitBreakerFired = true;
                    account.setDailyLossHalted(true);
                    flattenAllStockPositions(String.format(
                            "Circuit breaker: portfolio down %.1f%% — all positions closed",
                            circuitBreakerPct * 100));
                    researchCallback.accept(String.format(
                            "⛔ CIRCUIT BREAKER (%.0f%%) — all stock positions flattened, trading halted",
                            circuitBreakerPct * 100));
                    uiRefreshCallback.run();
                }
            }

            // Pass 2: run per-symbol signal evaluation, entries/exits, and router logic.
            // Price history and mark-to-market were already updated in Pass 1.
            for (QuoteModel quote : quotes) {
                String symbol = quote.getSymbol();
                double price = quote.getPrice();
                double volume = quote.getVolume();
                // Gap 6: use daily-resolution prices for indicators; fall back to intraday if not yet seeded
                List<Double> dailyPs = priceHistory.getDailyPrices(symbol);
                List<Double> dailyVs = priceHistory.getDailyVolumes(symbol);
                List<Double> prices  = dailyPs.isEmpty() ? priceHistory.getPrices(symbol)  : dailyPs;
                List<Double> volumes = dailyVs.isEmpty() ? priceHistory.getVolumes(symbol) : dailyVs;
                List<SignalResult> signals;
                if (candleHistory != null) {
                    List<CandleBar> oneMin  = candleHistory.getOneMinBarsWithCurrent(symbol);
                    List<CandleBar> fiveMin = candleHistory.getFiveMinBarsWithCurrent(symbol);
                    signals = indicators.evaluateAllWithCandles(prices, volumes, price, oneMin, fiveMin);
                } else {
                    signals = indicators.evaluateAll(prices, volumes, price);
                }
                // Append today's news sentiment as an additional weighted signal.
                // Only use scores from today — stale scores from a prior session are ignored.
                if (sentimentCache != null && sentimentCache.isRefreshedToday()) {
                    SentimentScore sentiment = sentimentCache.getScore(symbol);
                    if (sentiment != null && sentiment.direction() != SentimentDirection.NEUTRAL
                            && sentiment.weight() > 0) {
                        SignalResult.Direction dir = sentiment.direction() == SentimentDirection.BULLISH
                                ? SignalResult.Direction.BUY : SignalResult.Direction.SELL;
                        signals.add(new SignalResult("NEWS_SENTIMENT", dir, sentiment.weight()));
                    }
                }
                // Relative strength vs SPY: stock out/underperforming SPY by 1.5%+ intraday
                // signals institutional flow independent of broad market direction.
                if (!"SPY".equals(symbol)) {
                    Double symOpen = dailyOpenPrices.get(symbol);
                    Double spyOpen = dailyOpenPrices.get("SPY");
                    double spyPrice = lastKnownPrices.getOrDefault("SPY", 0.0);
                    if (symOpen != null && spyOpen != null && symOpen > 0 && spyOpen > 0 && spyPrice > 0) {
                        double symPct = (price - symOpen) / symOpen;
                        double spyPct = (spyPrice - spyOpen) / spyOpen;
                        double divergence = symPct - spyPct;
                        if (divergence >= 0.015)
                            signals.add(SignalResult.buy("RELATIVE_STRENGTH", divergence));
                        else if (divergence <= -0.015)
                            signals.add(SignalResult.sell("RELATIVE_STRENGTH", divergence));
                    }
                }
                // VWAP cross: only count the signal on the tick where price crosses the VWAP line.
                // Stripping it on non-crossing ticks prevents VWAP from adding 1 vote every tick
                // above/below (which biases entries in trending markets).
                SignalResult vwapSignal = signals.stream()
                        .filter(s -> "VWAP".equals(s.getIndicatorName())).findFirst().orElse(null);
                if (vwapSignal != null) {
                    SignalResult.Direction prevSide = prevVwapSide.get(symbol);
                    boolean isCrossing = prevSide != null
                            && prevSide != vwapSignal.getDirection()
                            && vwapSignal.getDirection() != SignalResult.Direction.NEUTRAL;
                    if (!isCrossing) {
                        signals = signals.stream()
                                .filter(s -> !"VWAP".equals(s.getIndicatorName()))
                                .collect(Collectors.toList());
                    }
                    prevVwapSide.put(symbol, vwapSignal.getDirection());
                }

                // Candlestick is useful for confirming entries but too noisy as an exit trigger —
                // a single red candle should not close a position. Exclude it from sell scoring.
                List<SignalResult> sellSignals = signals.stream()
                        .filter(s -> !"Candlestick".equals(s.getIndicatorName()))
                        .collect(Collectors.toList());

                int buys = indicators.countBuySignals(signals);
                int sells = indicators.countSellSignals(sellSignals);
                double weightedBuys = weightEvaluator != null
                        ? weightEvaluator.weightedBuyScore(signals) : (double) buys;
                double weightedSells = weightEvaluator != null
                        ? weightEvaluator.weightedSellScore(sellSignals) : (double) sells;
                String signalStr = signals.stream().map(SignalResult::toString).collect(Collectors.joining(", "));
                String featureCsv = extractFeatureCsv(signals);
                boolean hasPosition = account.isStockVerified(symbol);

                // ── Multi-indicator ───────────────────────────────────────────────────
                if (trailingStop.check(symbol, price) && hasPosition) {
                    // Trailing stop always fires regardless of hold time — it's a loss-protection rule.
                    Position pos = account.getPositions().get(symbol);
                    brokerClient.submitSell(symbol, pos.getQuantity(), price, signalStr,
                            String.format("Trailing stop: %.0f%% drawdown from peak", trailingStop.getTrailingStopPct() * 100));
                    account.removePosition(symbol);
                    trailingStop.reset(symbol);
                    entryTimes.remove(symbol);
                    Double ep = entryPrices.remove(symbol);
                    lossCooldowns.put(symbol, now);
                    researchCallback.accept(symbol + " on 60-min re-entry cooldown after trailing stop (price $"
                            + String.format("%.2f", price) + (ep != null ? " < peak, entry was $" + String.format("%.2f", ep) : "") + ")");
                    uiRefreshCallback.run();
                } else if (weightedSells >= sellSignalThreshold && hasPosition) {
                    ZonedDateTime entryTime = entryTimes.get(symbol);
                    long heldMinutes = entryTime != null
                            ? Duration.between(entryTime, now).toMinutes() : Long.MAX_VALUE;
                    if (heldMinutes < minHoldMinutes) {
                        researchCallback.accept(symbol + " SELL skipped: min hold ("
                                + heldMinutes + "/" + minHoldMinutes + "min)");
                    } else {
                        Position pos = account.getPositions().get(symbol);
                        brokerClient.submitSell(symbol, pos.getQuantity(), price, signalStr,
                                "Signals: " + sells + "/" + sellSignals.size() + " SELL");
                        account.removePosition(symbol);
                        trailingStop.reset(symbol);
                        entryTimes.remove(symbol);
                        // Trigger re-entry cooldown if this was a losing trade opened this session.
                        Double ep = entryPrices.remove(symbol);
                        if (ep != null && price < ep) {
                            lossCooldowns.put(symbol, now);
                            researchCallback.accept(symbol + " on 60-min re-entry cooldown (sold $"
                                    + String.format("%.2f", price) + " < entry $" + String.format("%.2f", ep) + ")");
                        }
                        uiRefreshCallback.run();
                    }
                }
                // Premium seller evaluates first so multi-day spreads have first claim on
                // portfolio capacity. Options router runs second. Stock entries run last.
                // Filter to the options watchlist but still run exits for any symbol with
                // open positions (in case the allowlist changed after a position was entered).
                boolean inOptionsWatchlist = optionsWatchlist == null || optionsWatchlist.contains(symbol);
                boolean hasOpenOptionsForSymbol = !inOptionsWatchlist
                        && account.getOptionsPositions().keySet().stream()
                                .anyMatch(k -> k.startsWith(symbol + "_"));
                if (inOptionsWatchlist || hasOpenOptionsForSymbol) {
                    if (premiumSellerEvaluator != null) {
                        premiumSellerEvaluator.evaluateWithSignals(symbol, price, buys, sells, signalStr, featureCsv, signals);
                    }
                    if (optionsEvaluator != null) {
                        optionsEvaluator.evaluateWithSignals(symbol, price, buys, sells, signalStr, featureCsv, signals);
                    }
                }
                if (!hasPosition && !orbFormationPeriod && !time.isAfter(LAST_ENTRY_TIME)
                        && (stockWatchlist == null || stockWatchlist.contains(symbol))
                        && (weightedBuys >= SIGNAL_THRESHOLD
                                || (signals.stream().anyMatch(s -> "ORB".equals(s.getIndicatorName())
                                        && s.getDirection() == SignalResult.Direction.BUY)
                                    && prevOrbBuy.getOrDefault(symbol, false)))) {
                    int daysToEarnings = earningsCalendar != null
                            ? earningsCalendar.daysUntilEarnings(symbol) : Integer.MAX_VALUE;
                    boolean inCooldown = lossCooldowns.containsKey(symbol)
                            && Duration.between(lossCooldowns.get(symbol), now).toMinutes() < lossCooldownMinutes;
                    boolean orbBuyNow = signals.stream().anyMatch(
                            s -> "ORB".equals(s.getIndicatorName())
                                    && s.getDirection() == SignalResult.Direction.BUY);
                    // ORB standalone: confirmed on 2 consecutive ticks — no other signals required.
                    // Multi-signal path: require ORB confirmation only when ORB is actively voting BUY.
                    boolean orbStandaloneEntry = orbBuyNow && prevOrbBuy.getOrDefault(symbol, false);
                    boolean orbConfirmedTwice = !orbBuyNow || prevOrbBuy.getOrDefault(symbol, false);
                    // Skip entry if RSI is active and overbought — avoids buying into exhausted moves.
                    boolean rsiOverbought = signals.stream()
                            .anyMatch(s -> "RSI".equals(s.getIndicatorName())
                                    && s.getDirection() == SignalResult.Direction.SELL);
                    if (inCooldown) {
                        researchCallback.accept(symbol + " BUY skipped: 60-min re-entry cooldown after loss");
                    } else if (!orbStandaloneEntry && !orbConfirmedTwice) {
                        // Silent — fires on nearly every ORB-false-breakout tick; logging would flood the feed.
                    } else if (rsiOverbought) {
                        researchCallback.accept(symbol + " BUY skipped: RSI overbought");
                    } else if ((inverseEtfSymbols.contains(symbol) ? isMarketInUptrend() : !isMarketInUptrend())
                            && weightedBuys < SIGNAL_THRESHOLD + regimeOverrideExtraBuys) {
                        researchCallback.accept(symbol + (inverseEtfSymbols.contains(symbol)
                                ? " BUY skipped: SPY in uptrend (inverse ETF — needs downtrend)"
                                : " BUY skipped: SPY below " + regimeMaDays + "-day MA (short-term downtrend)"));
                    } else if (daysToEarnings <= earningsBlackoutDays) {
                        researchCallback.accept(symbol + " BUY skipped: earnings in "
                                + daysToEarnings + " day" + (daysToEarnings == 1 ? "" : "s"));
                    } else if (account.totalExposureFraction() >= maxPortfolioExposure) {
                        researchCallback.accept(symbol + " BUY skipped: portfolio at capacity ("
                                + String.format("%.0f%%", account.totalExposureFraction() * 100)
                                + " deployed, limit " + String.format("%.0f%%", maxPortfolioExposure * 100) + ")");
                    } else if (account.isDailyLossHalted()) {
                        researchCallback.accept(symbol + " BUY skipped: daily loss limit active");
                    } else if (account.getPositions().size() >= maxConcurrentStockPositions) {
                        researchCallback.accept(symbol + " BUY skipped: max concurrent positions ("
                                + maxConcurrentStockPositions + ") already open");
                    } else if (isVixSpiking()) {
                        researchCallback.accept(symbol + " BUY skipped: VIX spike above "
                                + (int) VIX_BLOCK_THRESHOLD + " — widened spreads, panic conditions");
                    } else if (stockTradingEnabled) {
                        double portfolioValue = account.getBalance()
                                + account.getPositions().values().stream()
                                        .mapToDouble(Position::getMarketValue).sum();
                        int shares = fees.riskBasedShares(portfolioValue, price,
                                trailingStop.getTrailingStopPct(), maxLossPerTradePct);
                        if (shares == 0) shares = fees.maxShares(account.getBalance(), price);
                        if (shares > 0) {
                            String entryReason = orbStandaloneEntry && weightedBuys < SIGNAL_THRESHOLD
                                    ? "ORB breakout (standalone)"
                                    : "Signals: " + buys + "/" + signals.size() + " BUY";
                            brokerClient.submitBuy(symbol, shares, price, signalStr,
                                    entryReason, featureCsv);
                            entryTimes.put(symbol, now);
                            entryPrices.put(symbol, price);
                            // Reset peak to entry price so the 2% trailing stop is measured
                            // from where we entered, not from an earlier intraday high.
                            trailingStop.reset(symbol);
                            uiRefreshCallback.run();
                        }
                    }
                }
                trailingStop.updatePeak(symbol, price);
                // Track ORB state for 2-tick confirmation on the next evaluation cycle.
                prevOrbBuy.put(symbol, signals.stream().anyMatch(
                        s -> "ORB".equals(s.getIndicatorName())
                                && s.getDirection() == SignalResult.Direction.BUY));
                researchCallback.accept(time + " | " + symbol + " $" + String.format("%.2f", price)
                        + " | " + signalStr + " | BUY=" + buys + " SELL=" + sells);
            }
            // Gap 5: liquidate all equity positions at 15:45 to avoid overnight gap risk
            if (avoidOvernightHolds && !time.isBefore(PRE_CLOSE_CUTOFF)) {
                for (String sym : new ArrayList<>(account.getPositions().keySet())) {
                    if (!account.isStockVerified(sym)) continue;
                    Position pos = account.getPositions().get(sym);
                    if (pos == null) continue;
                    double closePrice = lastKnownPrices.getOrDefault(sym, pos.getCurrentPrice());
                    brokerClient.submitSell(sym, pos.getQuantity(), closePrice, "",
                            "Pre-close: avoid overnight hold");
                    trailingStop.reset(sym);
                    entryTimes.remove(sym);
                    entryPrices.remove(sym);
                    researchCallback.accept(sym + " closed at $" + String.format("%.2f", closePrice)
                            + ": pre-close overnight avoidance");
                }
            }
            uiRefreshCallback.run();
        } catch (Exception e) {
            researchCallback.accept("TradingLoop error: " + e.getMessage());
        }
    }

    private void flattenAllStockPositions(String reason) {
        for (String sym : new ArrayList<>(account.getPositions().keySet())) {
            Position pos = account.getPositions().get(sym);
            if (pos == null) continue;
            double price = lastKnownPrices.getOrDefault(sym, pos.getAverageCost());
            brokerClient.submitSell(sym, pos.getQuantity(), price, "", reason);
            trailingStop.reset(sym);
            entryTimes.remove(sym);
            entryPrices.remove(sym);
        }
    }

    private boolean isMarketInUptrend() {
        if (!marketRegimeFilterEnabled) return true;
        List<Double> spyPrices = priceHistory.getDailyPrices("SPY");
        if (spyPrices.size() < regimeMaDays) return true;
        double ma = spyPrices.subList(spyPrices.size() - regimeMaDays, spyPrices.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return spyPrices.get(spyPrices.size() - 1) >= ma;
    }

    private boolean isVixSpiking() {
        double vix = lastKnownPrices.getOrDefault("^VIX", lastKnownPrices.getOrDefault("VIX", 0.0));
        return vix > 0 && vix > VIX_BLOCK_THRESHOLD;
    }

    private static final String[] FEATURE_NAMES = {
        "RSI", "BollingerBands", "VolumeSurge", "VWAP", "ORB", "Candlestick",
        "MACD", "STOCHASTIC", "RELATIVE_STRENGTH"
    };

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
