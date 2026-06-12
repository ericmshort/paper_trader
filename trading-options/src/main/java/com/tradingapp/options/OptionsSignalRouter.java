package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.OptionsQuote;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.engine.OptionsEvaluator;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    private static final ZoneId ET = ZoneId.of("America/New_York");
    // All open options positions are force-closed at this time to avoid overnight holds.
    private static final LocalTime PRE_CLOSE_CUTOFF = LocalTime.of(15, 45);

    private final BlackScholesEngine bsEngine;
    private final OptionsOrderExecutor optExec;
    private Account account;
    private PriceHistory priceHistory;
    private final Consumer<String> researchCallback;
    private final QuoteProvider dataClient;

    private static final double RISK_FREE_RATE  = 0.04;
    private static final double MIN_PREMIUM     = 1.50;  // $150/contract minimum to cover fees
    private double maxPortfolioExposure         = 0.60;
    private static final double IV_SURGE_THRESHOLD = 1.2;  // skip if recent vol > 1.2x long-term (IV crush guard)
    private static final int    IV_WINDOW          = 20;
    // Exit when premium reaches this multiple of entry (configurable; default 2x).
    private double profitTarget                     = 2.0;
    // Exit when premium drops to this fraction of entry (configurable; default 50%).
    private double stopLossFrac                    = 0.50;
    private static final double DEEP_ITM_OFFSET    = 10.0;

    // Cooldown re-entry window (virtual time, works in both live and backtest)
    private long cooldownMinutes = 15L;
    // If set, no new options entries are opened before this time (e.g. skip first 30 min).
    private LocalTime entryStartTime = null;

    private final Set<String> sessionStopLossed = new HashSet<>();
    private final Map<String, ZonedDateTime> lastMultiLegCloseTime    = new HashMap<>();
    private final Map<String, ZonedDateTime> lastDirectionalCloseTime = new HashMap<>();
    private final Map<String, ZonedDateTime> lastAnyCloseTime         = new HashMap<>();
    private final Map<String, Integer> reversalConsecutive = new HashMap<>();

    private LocalDate stopLossResetDate;
    private BooleanSupplier uptrendSupplier;
    private Set<String> enabledStrategies    = new HashSet<>();
    // If set, no new options entries are opened at or after this time (closes still run).
    private LocalTime entryCutoff             = null;
    // If non-empty, only these symbols may open new options positions.
    private Set<String> optionsAllowlist      = new HashSet<>();
    // Symbols in this set may open puts but not calls.
    private Set<String> callsDisabledSymbols  = new HashSet<>();
    // Symbols in this set may open calls but not puts.
    private Set<String> putsDisabledSymbols   = new HashSet<>();
    private int downtrendPutMinSignals        = 4;
    private boolean avoidOvernightHolds      = true;
    // Effective force-close time — overridden to 12:30 on market half-days.
    private LocalTime effectiveForceCloseTime = PRE_CLOSE_CUTOFF;
    // Saved normal entry cutoff so it can be restored after a half-day session.
    private LocalTime normalEntryCutoff       = null;
    // When false, the half-day early-close guard is disabled (for A/B comparison only).
    private boolean holidayGuardEnabled       = true;
    // When set, called at the start of each day to update the options allowlist dynamically.
    private java.util.function.Function<java.time.LocalDate, Set<String>> dailyAllowlistProvider = null;

    // Virtual clock: ET in live trading; virtual clock in backtest.
    private Supplier<ZonedDateTime> clock = () -> ZonedDateTime.now(ET);

    public void setUptrendSupplier(BooleanSupplier s) { this.uptrendSupplier = s; }
    public void setMaxPortfolioExposure(double fraction) { this.maxPortfolioExposure = fraction; }
    public void setEnabledStrategies(Set<String> strategies) { this.enabledStrategies = new HashSet<>(strategies); }
    public void setClock(Supplier<ZonedDateTime> clock) { this.clock = clock; }
    public void setStopLossFrac(double frac)           { this.stopLossFrac = frac; }
    public void setProfitTarget(double multiple)       { this.profitTarget = multiple; }
    public void setCooldownMinutes(long minutes)       { this.cooldownMinutes = minutes; }
    public void setEntryStartTime(LocalTime time)      { this.entryStartTime = time; }
    public void setAvoidOvernightHolds(boolean v)      { this.avoidOvernightHolds = v; }
    public void setEntryCutoff(LocalTime time)         { this.entryCutoff = time; this.normalEntryCutoff = time; }
    public void setHolidayGuardEnabled(boolean v)      { this.holidayGuardEnabled = v; }
    public void setDailyAllowlistProvider(java.util.function.Function<java.time.LocalDate, Set<String>> p) {
        this.dailyAllowlistProvider = p;
    }
    public void setOptionsAllowlist(Set<String> symbols) { this.optionsAllowlist = new HashSet<>(symbols); }
    public void setCallsDisabledSymbols(Set<String> symbols) { this.callsDisabledSymbols = new HashSet<>(symbols); }
    public void setPutsDisabledSymbols(Set<String> symbols)  { this.putsDisabledSymbols  = new HashSet<>(symbols); }
    public void setDowntrendPutMinSignals(int n)              { this.downtrendPutMinSignals = n; }
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

    // ── Backtest lifecycle ────────────────────────────────────────────────────

    @Override
    public void onBacktestInit(TransactionLog sharedLog, Account sharedAccount,
                               PriceHistory sharedHistory, Supplier<ZonedDateTime> clock) {
        optExec.setTransactionLog(sharedLog);
        optExec.setAccount(sharedAccount);
        this.account = sharedAccount;
        this.priceHistory = sharedHistory;
        this.clock = clock;
        // Gate puts/calls using SPY's 50-bar intraday MA.
        // SPY intraday price < 50-bar MA → short-term downtrend → allow puts, block calls.
        this.uptrendSupplier = () -> {
            List<Double> spy = sharedHistory.getPrices("SPY");
            if (spy.size() < 50) return true; // insufficient history — assume uptrend (bias toward calls)
            double ma50 = spy.subList(spy.size() - 50, spy.size()).stream()
                    .mapToDouble(Double::doubleValue).average().orElse(spy.get(spy.size() - 1));
            return spy.get(spy.size() - 1) >= ma50;
        };
    }

    @Override
    public void resetForDay(LocalDate date) {
        bsEngine.setReferenceDate(date);
        sessionStopLossed.clear();
        lastMultiLegCloseTime.clear();
        lastDirectionalCloseTime.clear();
        lastAnyCloseTime.clear();
        reversalConsecutive.clear();
        stopLossResetDate = date;
        if (dailyAllowlistProvider != null) {
            optionsAllowlist = new java.util.HashSet<>(dailyAllowlistProvider.apply(date));
        }
        // On market half-days (early close 1pm ET), force-close at 12:30 and block entries after 11:30
        if (holidayGuardEnabled && MarketCalendar.isHalfDay(date)) {
            effectiveForceCloseTime = MarketCalendar.HALF_DAY_FORCE_CLOSE;
            entryCutoff = MarketCalendar.HALF_DAY_ENTRY_CUTOFF;
        } else {
            effectiveForceCloseTime = PRE_CLOSE_CUTOFF;
            entryCutoff = normalEntryCutoff;
        }
    }

    // ── Main evaluation ───────────────────────────────────────────────────────

    @Override
    public void evaluate(String symbol, double price, int buySignals, int sellSignals,
                         String signalStr, String featureCsv) {
        resetIfNewDay();

        // ── Pre-close: force-close all open options for this symbol ───────────
        LocalTime time = clock.get().toLocalTime();
        if (avoidOvernightHolds && !time.isBefore(effectiveForceCloseTime)) {
            forceCloseAllForSymbol(symbol, price);
            return;
        }

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
        // Require more sell signals to open a put in a bull market — loosens in a bear market
        int putMin = inDowntrend ? downtrendPutMinSignals : 5;
        String marketRegime = inDowntrend ? "bear" : "bull";

        boolean hasDirectional = opts.containsKey(callKey)          || opts.containsKey(putKey)
                              || opts.containsKey(highDeltaCallKey)  || opts.containsKey(highDeltaPutKey)
                              || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey);
        boolean hasMultiLeg   = opts.containsKey(zeroDteCallKey)    || opts.containsKey(zeroDtePutKey);

        // ── 5. Cooldown check ─────────────────────────────────────────────────
        ZonedDateTime now = clock.get();
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        ZonedDateTime lastClose = lastAnyCloseTime.getOrDefault(symbol, epoch);
        if (Duration.between(lastClose, now).toMinutes() < cooldownMinutes) {
            researchCallback.accept(symbol + " skip: options cooldown ("
                    + Duration.between(lastClose, now).toMinutes() + "m elapsed, need " + cooldownMinutes + "m)");
            return;
        }

        // ── 6. Entry timing guards ───────────────────────────────────────────
        if (entryStartTime != null && time.isBefore(entryStartTime)) {
            return;
        }
        if (entryCutoff != null && !time.isBefore(entryCutoff)) {
            return;
        }

        // ── 7. Entry (per-symbol options filters) ────────────────────────────
        boolean optionsAllowed = optionsAllowlist.isEmpty() || optionsAllowlist.contains(symbol);
        if (!optionsAllowed) {
            researchCallback.accept(symbol + " options skip: not in options allowlist");
            return;
        }
        boolean callsAllowed = !callsDisabledSymbols.contains(symbol);
        boolean putsAllowed  = !putsDisabledSymbols.contains(symbol);

        if (extremeBullish && !hasDirectional && !hasMultiLeg) {
            if (!callsAllowed)
                researchCallback.accept(symbol + " CALL skip: calls disabled for symbol");
            else if (inDowntrend)
                researchCallback.accept(symbol + " CALL skip: SPY downtrend");
            else if (isStrategyEnabled("HIGH_DELTA_SCALP"))
                tryOpenHighDeltaScalp(symbol, price, K, expiry, T, sigma, true, signalStr, featureCsv);

        } else if (veryStrongBull && !hasDirectional && !hasMultiLeg) {
            if (!callsAllowed)
                researchCallback.accept(symbol + " CALL skip: calls disabled for symbol");
            else if (inDowntrend)
                researchCallback.accept(symbol + " CALL skip: SPY downtrend");
            else if (isStrategyEnabled("MOMENTUM_NEAR_TERM"))
                tryOpenMomentumNearTerm(symbol, price, true, sigma, signalStr, featureCsv);

        } else if (purelyBullish && !hasDirectional && !hasMultiLeg) {
            if (!callsAllowed)
                researchCallback.accept(symbol + " CALL skip: calls disabled for symbol");
            else if (inDowntrend)
                researchCallback.accept(symbol + " CALL skip: SPY downtrend");
            else if (isStrategyEnabled("LONG_CALL"))
                tryOpenLongCall(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, callKey, opts);

        } else if (extremeBearish && !hasDirectional && !hasMultiLeg) {
            if (!putsAllowed)
                researchCallback.accept(symbol + " PUT skip: puts disabled for symbol");
            else if (sellSignals < putMin)
                researchCallback.accept(symbol + " PUT skip: need " + putMin + "+ signals in " + marketRegime + " (have " + sellSignals + ")");
            else if (isStrategyEnabled("HIGH_DELTA_SCALP"))
                tryOpenHighDeltaScalp(symbol, price, K, expiry, T, sigma, false, signalStr, featureCsv);

        } else if (veryStrongBear && !hasDirectional && !hasMultiLeg) {
            if (!putsAllowed)
                researchCallback.accept(symbol + " PUT skip: puts disabled for symbol");
            else if (sellSignals < putMin)
                researchCallback.accept(symbol + " PUT skip: need " + putMin + "+ signals in " + marketRegime + " (have " + sellSignals + ")");
            else if (isStrategyEnabled("MOMENTUM_NEAR_TERM"))
                tryOpenMomentumNearTerm(symbol, price, false, sigma, signalStr, featureCsv);

        } else if (purelyBearish && !hasDirectional && !hasMultiLeg) {
            if (!putsAllowed)
                researchCallback.accept(symbol + " PUT skip: puts disabled for symbol");
            else if (sellSignals < putMin)
                researchCallback.accept(symbol + " PUT skip: need " + putMin + "+ signals in " + marketRegime + " (have " + sellSignals + ")");
            else if (isStrategyEnabled("LONG_PUT"))
                tryOpenLongPut(symbol, price, K, expiry, T, sigma, signalStr, featureCsv, putKey, sellSignals, opts);

        } else if (mixedStrong && !hasDirectional && !hasMultiLeg) {
            if (callsAllowed && putsAllowed && isZeroDteDay() && isStrategyEnabled("ZERO_DTE"))
                tryOpenZeroDTE(symbol, price, K, sigma, signalStr, featureCsv);
        }
    }

    // ── Pre-close force-exit ──────────────────────────────────────────────────

    private void forceCloseAllForSymbol(String symbol, double stockPrice) {
        String[] keys = {
            symbol + "_CALL",          symbol + "_PUT",
            symbol + "_HIGHDELTA_CALL", symbol + "_HIGHDELTA_PUT",
            symbol + "_NEARTERM_CALL",  symbol + "_NEARTERM_PUT",
            symbol + "_ZEROTE_CALL",    symbol + "_ZEROTE_PUT"
        };
        LocalDate virtualDate = clock.get().toLocalDate();
        for (String key : keys) {
            OptionsPosition pos = account.getOptionsPositions().get(key);
            if (pos == null) continue;
            boolean isCall = "CALL".equals(pos.getType());
            double sigma = computeVol(symbol);
            double T = bsEngine.timeToExpiry(pos.getExpiry());
            double premium;
            if (sigma > 0 && T > 0) {
                premium = isCall
                    ? bsEngine.callPrice(stockPrice, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                    : bsEngine.putPrice(stockPrice, pos.getStrike(), RISK_FREE_RATE, T, sigma);
            } else {
                premium = isCall
                    ? Math.max(0, stockPrice - pos.getStrike())
                    : Math.max(0, pos.getStrike() - stockPrice);
            }
            optExec.closePosition(key, premium, "Pre-close: avoid overnight hold");
            lastAnyCloseTime.put(symbol, clock.get());
            researchCallback.accept(symbol + (isCall ? " CALL" : " PUT")
                    + " closed pre-close prem=" + String.format("%.2f", premium));
        }
    }

    // ── Directional close ──────────────────────────────────────────────────────

    private void closeDirectionalLeg(Map<String, OptionsPosition> opts, String posKey,
                                     boolean isCall, String symbol, double price,
                                     int reversalSignals, String signalStr) {
        OptionsPosition pos = opts.get(posKey);
        if (pos == null) return;

        LocalDate virtualDate = clock.get().toLocalDate();
        double T     = bsEngine.timeToExpiry(pos.getExpiry());
        double sigma = computeVol(symbol);
        boolean canPrice = sigma > 0 && T > 0;
        double currentPremium = canPrice
                ? (isCall ? bsEngine.callPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma)
                          : bsEngine.putPrice(price, pos.getStrike(), RISK_FREE_RATE, T, sigma))
                : 0.0;

        // If sigma is unavailable but the option hasn't expired, skip evaluation —
        // a dry quote feed does not mean the position is worthless.
        if (!canPrice && T > 0) return;

        boolean premiumStop      = canPrice && currentPremium <= pos.getPremiumPaid() * stopLossFrac;
        boolean hitProfitTarget  = currentPremium >= pos.getPremiumPaid() * profitTarget;
        boolean nearExpiry       = pos.daysToExpiry(virtualDate) < 3;

        // Require 4+ opposing signals for 3 consecutive ticks before exiting on reversal —
        // same entry-strength bar to trigger exit, prevents churn on intraday noise.
        int consecutive;
        if (reversalSignals >= 4) {
            consecutive = reversalConsecutive.merge(posKey, 1, Integer::sum);
        } else {
            reversalConsecutive.remove(posKey);
            consecutive = 0;
        }
        boolean reversal = consecutive >= 3;

        // Close as worthless only when the option has actually expired (T <= 0).
        boolean worthless = !canPrice && pos.getPremiumPaid() > 0;

        if (!premiumStop && !hitProfitTarget && !reversal && !nearExpiry && !worthless) return;

        reversalConsecutive.remove(posKey);
        String reason;
        if (nearExpiry)             reason = "Expiry <3 days";
        else if (hitProfitTarget)   reason = String.format("Profit target: %.2f >= %.1fx of %.2f",
                                            currentPremium, profitTarget, pos.getPremiumPaid());
        else if (premiumStop)  reason = String.format("Premium stop-loss: %.2f <= %.0f%% of %.2f",
                                        currentPremium, stopLossFrac * 100, pos.getPremiumPaid());
        else if (worthless)    reason = "Premium collapsed to zero";
        else                   reason = "Signal reversal (3 bars): " + (isCall ? "SELL" : "BUY");

        if (premiumStop || worthless) sessionStopLossed.add(posKey);
        lastDirectionalCloseTime.put(posKey, clock.get());
        lastAnyCloseTime.put(symbol, clock.get());
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

        LocalDate virtualDate = clock.get().toLocalDate();
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

        boolean premiumStop     = totalPaid > 0 && totalCurrent <= totalPaid * stopLossFrac;
        boolean hitProfitTarget = totalPaid > 0 && totalCurrent >= totalPaid * profitTarget;
        boolean nearExpiry   = strategyName.equals("ZEROTE")
                ? callPos.daysToExpiry(virtualDate) < 1
                : callPos.daysToExpiry(virtualDate) < 3;

        if (!premiumStop && !hitProfitTarget && !nearExpiry) return;

        String reason;
        if (nearExpiry)             reason = "Expiry <" + (strategyName.equals("ZEROTE") ? "1 day" : "3 days");
        else if (hitProfitTarget)   reason = String.format("Profit target: combined %.2f >= %.1fx of %.2f",
                                            totalCurrent, profitTarget, totalPaid);
        else                   reason = String.format("Premium stop-loss: combined %.2f <= %.0f%% of %.2f",
                                        totalCurrent, stopLossFrac * 100, totalPaid);

        if (premiumStop) sessionStopLossed.add(symbol + "_MULTILEG");
        lastMultiLegCloseTime.put(symbol, clock.get());
        lastAnyCloseTime.put(symbol, clock.get());
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
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        ZonedDateTime callLastClose = lastDirectionalCloseTime.getOrDefault(callKey, epoch);
        if (Duration.between(callLastClose, clock.get()).toMinutes() < cooldownMinutes) {
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
        lastAnyCloseTime.put(symbol, clock.get());
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
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        ZonedDateTime putLastClose = lastDirectionalCloseTime.getOrDefault(putKey, epoch);
        if (Duration.between(putLastClose, clock.get()).toMinutes() < cooldownMinutes) {
            researchCallback.accept(symbol + " PUT skip: re-entry cooldown");
            return;
        }
        if (uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " PUT skip: SPY above 20-bar MA (uptrend)");
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
        lastAnyCloseTime.put(symbol, clock.get());
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
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        ZonedDateTime hdLastClose = lastDirectionalCloseTime.getOrDefault(posKey, epoch);
        if (Duration.between(hdLastClose, clock.get()).toMinutes() < cooldownMinutes) {
            researchCallback.accept(symbol + (isCall ? " HIGH-DELTA CALL" : " HIGH-DELTA PUT") + " skip: re-entry cooldown");
            return;
        }
        if (isCall && account.getPositions().containsKey(symbol)) {
            researchCallback.accept(symbol + " HIGH-DELTA CALL skip: equity position already open");
            return;
        }
        if (!isCall && uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " HIGH-DELTA PUT skip: SPY above 20-bar MA (uptrend)");
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
        // 5+ signal entry — highest conviction; allocate 8% of balance, up to 8 contracts.
        int contracts = Math.min(8, (int) (account.getBalance() * 0.08 / (premium * 100)));
        if (contracts < 1) return;

        if (isCall) optExec.buyCallAs(posKey, symbol, deepK, nearExpiry, contracts, premium, signalStr, featureCsv);
        else        optExec.buyPutAs (posKey, symbol, deepK, nearExpiry, contracts, premium, signalStr, featureCsv);
        if (!account.getOptionsPositions().containsKey(posKey)) return;
        lastAnyCloseTime.put(symbol, clock.get());

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
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        ZonedDateTime ntLastClose = lastDirectionalCloseTime.getOrDefault(posKey, epoch);
        if (Duration.between(ntLastClose, clock.get()).toMinutes() < cooldownMinutes) {
            researchCallback.accept(symbol + (isCall ? " NEARTERM CALL" : " NEARTERM PUT") + " skip: re-entry cooldown");
            return;
        }
        if (isCall && account.getPositions().containsKey(symbol)) {
            researchCallback.accept(symbol + " NEARTERM CALL skip: equity position already open");
            return;
        }
        if (!isCall && uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " NEARTERM PUT skip: SPY above 20-bar MA (uptrend)");
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
        // 4-signal entry — strong conviction; allocate 8% of balance, up to 8 contracts.
        int contracts = Math.min(8, (int) (account.getBalance() * 0.08 / (premium * 100)));
        if (contracts < 1) return;

        if (isCall) optExec.buyCallAs(posKey, symbol, K, nearExpiry, contracts, premium, signalStr, featureCsv);
        else        optExec.buyPutAs (posKey, symbol, K, nearExpiry, contracts, premium, signalStr, featureCsv);
        if (!account.getOptionsPositions().containsKey(posKey)) return;
        lastAnyCloseTime.put(symbol, clock.get());

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
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        ZonedDateTime lastClose = lastMultiLegCloseTime.getOrDefault(symbol, epoch);
        if (Duration.between(lastClose, clock.get()).toMinutes() < cooldownMinutes) {
            researchCallback.accept(symbol + " ZERO-DTE skip: re-entry cooldown");
            return;
        }

        LocalDate today = clock.get().toLocalDate();
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
        lastAnyCloseTime.put(symbol, clock.get());

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
        return clock.get().toLocalDate().getDayOfWeek() == DayOfWeek.FRIDAY;
    }

    private double computeVol(String symbol) {
        List<Double> daily  = priceHistory.getDailyPrices(symbol);
        List<Double> prices = daily.size() >= 2 ? daily : priceHistory.getPrices(symbol);
        return prices.size() < 2 ? 0.0 : bsEngine.historicalVol(prices);
    }

    private void resetIfNewDay() {
        LocalDate today = clock.get().toLocalDate();
        if (!today.equals(stopLossResetDate)) {
            sessionStopLossed.clear();
            lastMultiLegCloseTime.clear();
            lastDirectionalCloseTime.clear();
            reversalConsecutive.clear();
            stopLossResetDate = today;
        }
    }
}
