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
    // When true, all open positions for a symbol are closed immediately when the daily loss limit fires.
    // Intended for backtest use only — live trading leaves this false.
    private boolean closePositionsOnHalt      = false;
    // Tracks the date on which we've already issued the broker-side EOD close-all, so we
    // don't repeat the Alpaca call on every subsequent price tick after pre-close time.
    private LocalDate brokerCloseAllDate      = null;
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
    public void setClosePositionsOnHalt(boolean v)            { this.closePositionsOnHalt = v; }
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

    /**
     * Restores per-symbol cooldowns and session stop-loss set from today's transaction log.
     * Call once on startup after syncAccount() so that re-entry guards survive app restarts.
     * Only options close records (CALL_SELL / PUT_SELL) from today are considered.
     */
    public void restoreSessionState(TransactionLog txLog) {
        List<com.tradingapp.account.TransactionRecord> closes = txLog.findTodaysCloseActions(ET);
        if (closes.isEmpty()) return;

        for (com.tradingapp.account.TransactionRecord r : closes) {
            String sym = r.getSymbol();
            String action = r.getAction().name();
            boolean isCall = "CALL_SELL".equals(action);
            boolean isPut  = "PUT_SELL".equals(action);
            if (!isCall && !isPut) continue;

            ZonedDateTime closeTime = java.time.Instant.ofEpochMilli(r.getTimestamp()).atZone(ET);
            String type = isCall ? "CALL" : "PUT";

            // lastAnyCloseTime: keep the most recent close per symbol
            lastAnyCloseTime.merge(sym, closeTime, (a, b) -> a.isAfter(b) ? a : b);

            // Restore directional cooldown for all strategy key variants
            for (String key : new String[]{
                    sym + "_" + type,
                    sym + "_HIGHDELTA_" + type,
                    sym + "_NEARTERM_" + type,
                    sym + "_BREAKOUT_" + type,
                    sym + "_STOCH_" + type,
                    sym + "_RS_" + type,
                    sym + "_MACD_" + type }) {
                lastDirectionalCloseTime.merge(key, closeTime, (a, b) -> a.isAfter(b) ? a : b);
            }

            // Restore sessionStopLossed for positions closed by stop-loss
            String reason = r.getReason() != null ? r.getReason().toLowerCase() : "";
            if (reason.contains("stop") || reason.contains("loss") || reason.contains("worthless")) {
                for (String key : new String[]{
                        sym + "_" + type,
                        sym + "_HIGHDELTA_" + type,
                        sym + "_NEARTERM_" + type,
                        sym + "_BREAKOUT_" + type,
                        sym + "_STOCH_" + type,
                        sym + "_RS_" + type,
                        sym + "_MACD_" + type }) {
                    sessionStopLossed.add(key);
                }
            }
        }

        long stopCount = sessionStopLossed.size();
        researchCallback.accept("[Startup] Restored session state from " + closes.size()
                + " today's close(s) — " + stopCount + " stop-loss cooldown(s) active.");
    }

    // ── Main evaluation ───────────────────────────────────────────────────────

    @Override
    public void evaluate(String symbol, double price, int buySignals, int sellSignals,
                         String signalStr, String featureCsv) {
        evaluateWithSignals(symbol, price, buySignals, sellSignals, signalStr, featureCsv, List.of());
    }

    @Override
    public void evaluateWithSignals(String symbol, double price, int buySignals, int sellSignals,
                                    String signalStr, String featureCsv,
                                    List<com.tradingapp.engine.SignalResult> rawSignals) {
        resetIfNewDay();

        // ── Pre-close: force-close all open options ───────────────────────────
        LocalTime time = clock.get().toLocalTime();
        if (avoidOvernightHolds && !time.isBefore(effectiveForceCloseTime)) {
            LocalDate today = clock.get().toLocalDate();
            if (today.equals(brokerCloseAllDate)) {
                return; // broker close-all already issued today
            }
            if (optExec.closeAllFromBroker()) {
                // Live broker: fetch Alpaca positions and DELETE each OCC symbol once per day
                brokerCloseAllDate = today;
                researchCallback.accept("EOD: issued close-all to broker for all open options positions");
            } else {
                // Paper trading: close this symbol's positions using local state + BS pricing
                forceCloseAllForSymbol(symbol, price);
            }
            return;
        }

        String callKey            = symbol + "_CALL";
        String putKey             = symbol + "_PUT";
        String highDeltaCallKey   = symbol + "_HIGHDELTA_CALL";
        String highDeltaPutKey    = symbol + "_HIGHDELTA_PUT";
        String nearTermCallKey    = symbol + "_NEARTERM_CALL";
        String nearTermPutKey     = symbol + "_NEARTERM_PUT";
        String zeroDteCallKey     = symbol + "_ZEROTE_CALL";
        String zeroDtePutKey      = symbol + "_ZEROTE_PUT";
        String breakoutCallKey    = symbol + "_BREAKOUT_CALL";
        String breakoutPutKey     = symbol + "_BREAKOUT_PUT";
        String stochCallKey       = symbol + "_STOCH_CALL";
        String stochPutKey        = symbol + "_STOCH_PUT";
        String rsCallKey          = symbol + "_RS_CALL";
        String rsPutKey           = symbol + "_RS_PUT";
        String macdCallKey        = symbol + "_MACD_CALL";
        String macdPutKey         = symbol + "_MACD_PUT";

        Map<String, OptionsPosition> opts = account.getOptionsPositions();

        // ── 1. Close existing positions ───────────────────────────────────────
        closeDirectionalLeg(opts, callKey,          true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, putKey,           false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, highDeltaCallKey, true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, highDeltaPutKey,  false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, nearTermCallKey,  true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, nearTermPutKey,   false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, breakoutCallKey,  true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, breakoutPutKey,   false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, stochCallKey,     true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, stochPutKey,      false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, rsCallKey,        true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, rsPutKey,         false, symbol, price, buySignals,  signalStr);
        closeDirectionalLeg(opts, macdCallKey,      true,  symbol, price, sellSignals, signalStr);
        closeDirectionalLeg(opts, macdPutKey,       false, symbol, price, buySignals,  signalStr);
        closeMultiLegIfNeeded(opts, zeroDteCallKey, zeroDtePutKey, symbol, price, "ZEROTE", signalStr);

        opts = account.getOptionsPositions();

        // ── 2. Guard checks ───────────────────────────────────────────────────
        List<Double> dailyPs = priceHistory.getDailyPrices(symbol);
        List<Double> prices  = dailyPs.size() >= 2 ? dailyPs : priceHistory.getPrices(symbol);
        if (prices.size() < 2) return;

        if (account.isDailyLossHalted()) {
            if (closePositionsOnHalt) forceCloseAllForSymbol(symbol, price);
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
                              || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey)
                              || opts.containsKey(breakoutCallKey)   || opts.containsKey(breakoutPutKey)
                              || opts.containsKey(stochCallKey)      || opts.containsKey(stochPutKey)
                              || opts.containsKey(rsCallKey)         || opts.containsKey(rsPutKey)
                              || opts.containsKey(macdCallKey)       || opts.containsKey(macdPutKey);
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

        // ── 8. New independent strategy entries ──────────────────────────────
        // These check named signals in rawSignals and use separate position keys,
        // so they don't conflict with signal-count thresholds above.

        // Re-read opts after any changes above.
        opts = account.getOptionsPositions();
        hasDirectional = opts.containsKey(callKey)          || opts.containsKey(putKey)
                      || opts.containsKey(highDeltaCallKey)  || opts.containsKey(highDeltaPutKey)
                      || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey)
                      || opts.containsKey(breakoutCallKey)   || opts.containsKey(breakoutPutKey)
                      || opts.containsKey(stochCallKey)      || opts.containsKey(stochPutKey)
                      || opts.containsKey(rsCallKey)         || opts.containsKey(rsPutKey)
                      || opts.containsKey(macdCallKey)       || opts.containsKey(macdPutKey);

        // OPENING_BREAKOUT: ORB confirms breakout, strong early-session momentum
        boolean inFirstHour = !time.isBefore(LocalTime.of(9, 35)) && time.isBefore(LocalTime.of(10, 30));
        if (inFirstHour && !hasDirectional && !hasMultiLeg && isStrategyEnabled("OPENING_BREAKOUT")) {
            boolean orbBuy  = rawSignals.stream().anyMatch(
                    s -> "ORB".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.BUY);
            boolean orbSell = rawSignals.stream().anyMatch(
                    s -> "ORB".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.SELL);
            LocalDate breakoutExpiry = bsEngine.selectNearTermExpiry();
            double breakoutT = bsEngine.timeToExpiry(breakoutExpiry);
            if (breakoutT <= 0) breakoutT = 5.0 / 365.0;
            if (orbBuy && buySignals >= 2 && callsAllowed && !inDowntrend) {
                tryOpenDirectional(symbol, price, true, breakoutCallKey, "OPENING-BREAKOUT",
                        breakoutExpiry, breakoutT, K, sigma, 0.05, 5, signalStr, featureCsv);
            } else if (orbSell && sellSignals >= 2 && putsAllowed && sellSignals >= putMin) {
                tryOpenDirectional(symbol, price, false, breakoutPutKey, "OPENING-BREAKOUT",
                        breakoutExpiry, breakoutT, K, sigma, 0.05, 5, signalStr, featureCsv);
            }
        }

        // Re-read opts in case OPENING_BREAKOUT opened
        opts = account.getOptionsPositions();
        hasDirectional = opts.containsKey(callKey)          || opts.containsKey(putKey)
                      || opts.containsKey(highDeltaCallKey)  || opts.containsKey(highDeltaPutKey)
                      || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey)
                      || opts.containsKey(breakoutCallKey)   || opts.containsKey(breakoutPutKey)
                      || opts.containsKey(stochCallKey)      || opts.containsKey(stochPutKey)
                      || opts.containsKey(rsCallKey)         || opts.containsKey(rsPutKey)
                      || opts.containsKey(macdCallKey)       || opts.containsKey(macdPutKey);

        // STOCHASTIC_REVERSAL: %K at extreme, at least 1 confirming signal
        if (!hasDirectional && !hasMultiLeg && isStrategyEnabled("STOCHASTIC_REVERSAL")) {
            boolean stochBuy  = rawSignals.stream().anyMatch(
                    s -> "STOCHASTIC".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.BUY);
            boolean stochSell = rawSignals.stream().anyMatch(
                    s -> "STOCHASTIC".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.SELL);
            LocalDate stochExpiry = bsEngine.selectNearTermExpiry();
            double stochT = bsEngine.timeToExpiry(stochExpiry);
            if (stochT <= 0) stochT = 5.0 / 365.0;
            if (stochBuy && buySignals >= 1 && callsAllowed && !inDowntrend) {
                tryOpenDirectional(symbol, price, true, stochCallKey, "STOCH-REVERSAL",
                        stochExpiry, stochT, K, sigma, 0.05, 5, signalStr, featureCsv);
            } else if (stochSell && sellSignals >= 1 && putsAllowed && sellSignals >= putMin) {
                tryOpenDirectional(symbol, price, false, stochPutKey, "STOCH-REVERSAL",
                        stochExpiry, stochT, K, sigma, 0.05, 5, signalStr, featureCsv);
            }
        }

        // Re-read opts in case STOCHASTIC_REVERSAL opened
        opts = account.getOptionsPositions();
        hasDirectional = opts.containsKey(callKey)          || opts.containsKey(putKey)
                      || opts.containsKey(highDeltaCallKey)  || opts.containsKey(highDeltaPutKey)
                      || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey)
                      || opts.containsKey(breakoutCallKey)   || opts.containsKey(breakoutPutKey)
                      || opts.containsKey(stochCallKey)      || opts.containsKey(stochPutKey)
                      || opts.containsKey(rsCallKey)         || opts.containsKey(rsPutKey)
                      || opts.containsKey(macdCallKey)       || opts.containsKey(macdPutKey);

        // RELATIVE_STRENGTH_DIVERGENCE: stock clearly out/underperforming SPY with confirmation
        if (!hasDirectional && !hasMultiLeg && isStrategyEnabled("RELATIVE_STRENGTH_DIVERGENCE")) {
            boolean rsBuy  = rawSignals.stream().anyMatch(
                    s -> "RELATIVE_STRENGTH".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.BUY);
            boolean rsSell = rawSignals.stream().anyMatch(
                    s -> "RELATIVE_STRENGTH".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.SELL);
            if (rsBuy && buySignals >= 2 && callsAllowed && !inDowntrend) {
                tryOpenDirectional(symbol, price, true, rsCallKey, "RS-DIVERGENCE",
                        expiry, T, K, sigma, 0.05, 5, signalStr, featureCsv);
            } else if (rsSell && sellSignals >= 2 && putsAllowed && sellSignals >= putMin) {
                tryOpenDirectional(symbol, price, false, rsPutKey, "RS-DIVERGENCE",
                        expiry, T, K, sigma, 0.05, 5, signalStr, featureCsv);
            }
        }

        // Re-read opts in case RS opened
        opts = account.getOptionsPositions();
        hasDirectional = opts.containsKey(callKey)          || opts.containsKey(putKey)
                      || opts.containsKey(highDeltaCallKey)  || opts.containsKey(highDeltaPutKey)
                      || opts.containsKey(nearTermCallKey)   || opts.containsKey(nearTermPutKey)
                      || opts.containsKey(breakoutCallKey)   || opts.containsKey(breakoutPutKey)
                      || opts.containsKey(stochCallKey)      || opts.containsKey(stochPutKey)
                      || opts.containsKey(rsCallKey)         || opts.containsKey(rsPutKey)
                      || opts.containsKey(macdCallKey)       || opts.containsKey(macdPutKey);

        // MACD_CROSSOVER: MACD line crosses its signal line with at least 1 confirming signal
        if (!hasDirectional && !hasMultiLeg && isStrategyEnabled("MACD_CROSSOVER")) {
            boolean macdBuy  = rawSignals.stream().anyMatch(
                    s -> "MACD".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.BUY);
            boolean macdSell = rawSignals.stream().anyMatch(
                    s -> "MACD".equals(s.getIndicatorName()) && s.getDirection() == com.tradingapp.engine.SignalResult.Direction.SELL);
            LocalDate macdExpiry = bsEngine.selectNearTermExpiry();
            double macdT = bsEngine.timeToExpiry(macdExpiry);
            if (macdT <= 0) macdT = 7.0 / 365.0;
            if (macdBuy && buySignals >= 1 && callsAllowed && !inDowntrend) {
                tryOpenDirectional(symbol, price, true, macdCallKey, "MACD-CROSSOVER",
                        macdExpiry, macdT, K, sigma, 0.05, 5, signalStr, featureCsv);
            } else if (macdSell && sellSignals >= 1 && putsAllowed && sellSignals >= putMin) {
                tryOpenDirectional(symbol, price, false, macdPutKey, "MACD-CROSSOVER",
                        macdExpiry, macdT, K, sigma, 0.05, 5, signalStr, featureCsv);
            }
        }
    }

    // ── Pre-close force-exit ──────────────────────────────────────────────────

    private void forceCloseAllForSymbol(String symbol, double stockPrice) {
        List<String> keys = account.getOptionsPositions().entrySet().stream()
                .filter(e -> symbol.equals(e.getValue().getSymbol()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
        double sigma = computeVol(symbol);
        for (String key : keys) {
            OptionsPosition pos = account.getOptionsPositions().get(key);
            if (pos == null) continue;
            boolean isCall = "CALL".equals(pos.getType());
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

        if (canPrice) pos.setCurrentMarketPrice(currentPremium);

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

    // ── Generic directional entry (used by new strategies) ───────────────────

    private void tryOpenDirectional(String symbol, double price, boolean isCall,
                                    String posKey, String labelName,
                                    LocalDate expiry, double T, double K, double sigma,
                                    double budgetFrac, int maxContracts,
                                    String signalStr, String featureCsv) {
        if (sessionStopLossed.contains(posKey)) {
            researchCallback.accept(symbol + " " + labelName + " skip: stop-loss cooldown");
            return;
        }
        ZonedDateTime epoch = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ET);
        if (Duration.between(lastDirectionalCloseTime.getOrDefault(posKey, epoch),
                clock.get()).toMinutes() < cooldownMinutes) {
            researchCallback.accept(symbol + " " + labelName + " skip: re-entry cooldown");
            return;
        }
        if (!isCall && uptrendSupplier != null && uptrendSupplier.getAsBoolean()) {
            researchCallback.accept(symbol + " " + labelName + " PUT skip: SPY uptrend");
            return;
        }
        double premium = resolvePremium(symbol, K, expiry, isCall, T, sigma, price);
        if (premium < MIN_PREMIUM) {
            researchCallback.accept(symbol + " " + labelName + " skip: premium too low");
            return;
        }
        int contracts = Math.min(maxContracts, (int) (account.getBalance() * budgetFrac / (premium * 100)));
        if (contracts < 1) return;
        if (isCall) optExec.buyCallAs(posKey, symbol, K, expiry, contracts, premium, signalStr, featureCsv);
        else        optExec.buyPutAs (posKey, symbol, K, expiry, contracts, premium, signalStr, featureCsv);
        if (!account.getOptionsPositions().containsKey(posKey)) return;
        lastAnyCloseTime.put(symbol, clock.get());
        GreeksResult g = bsEngine.greeks(price, K, RISK_FREE_RATE, T, sigma, isCall);
        researchCallback.accept(String.format("%s %s %s K=%.0f exp=%s x%d prem=%.2f | %s",
                symbol, labelName, isCall ? "CALL" : "PUT", K, expiry, contracts, premium, g));
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
