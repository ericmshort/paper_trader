package com.tradingapp.options;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.engine.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Simulates options strategies on historical bar data using Black-Scholes pricing.
 * Each strategy is entered/exited using the same signal logic as the live TradingLoop.
 * Position sizing mirrors the equity engine: 5% of available cash per trade.
 */
public class OptionsBacktestEngine {

    static final double RISK_FREE_RATE = 0.04;
    static final int EXPIRY_DAYS = 35;        // synthetic expiry ~5 weeks from entry
    static final double SPREAD_WIDTH = 5.0;   // distance between strikes for non-credit spreads
    static final int MAX_CONTRACTS = 5;
    static final double POSITION_SIZE_PCT = 0.05;
    static final double STOP_LOSS_FRACTION = 0.50;
    static final double PROFIT_TARGET_MULTIPLIER = 2.0;
    static final double CONTRACT_FEE = 0.65;
    // Slippage model: half the typical bid-ask spread paid on each side of a round trip,
    // plus a markup for implied vol trading above historical vol at entry.
    static final double SLIPPAGE_HALF_SPREAD = 0.08;
    static final double SLIPPAGE_IV_PREMIUM  = 0.10;

    // Premium-selling strategy parameters
    static final double PREMIUM_DELTA_TARGET  = 0.20;   // target |delta| for the short strike
    static final double CREDIT_SPREAD_WIDTH   = 10.0;   // wider spread for better premium/risk ratio
    // IV typically runs 12-18% above historical vol for short-dated equity options.
    // Sellers capture this premium; we apply it only to the short leg to simulate realistic credits.
    static final double CREDIT_IV_PREMIUM     = 0.15;
    static final double CREDIT_MIN_CREDIT_PCT = 0.20;   // minimum credit as fraction of spread width
    static final double CREDIT_MONTHLY_PROFIT_TARGET = 0.50; // close at 50% of credit (30–45 DTE)

    private final IndicatorEngine indicatorEngine;
    private final BlackScholesEngine bsEngine;
    private final FeeCalculator feeCalc;

    // Mirrors OptionsSignalRouter.overnightMinPremiumFrac: close EOD if value < costBasis * frac.
    private double overnightMinPremiumFrac = 0.0;

    public void setOvernightMinPremiumFrac(double frac) { this.overnightMinPremiumFrac = frac; }

    public OptionsBacktestEngine(IndicatorEngine indicatorEngine,
                                  BlackScholesEngine bsEngine,
                                  FeeCalculator feeCalc) {
        this.indicatorEngine = indicatorEngine;
        this.bsEngine = bsEngine;
        this.feeCalc = feeCalc;
    }

    // Represents one option leg: long/short, call/put, at a given strike, with a quantity multiplier
    record Leg(double strike, boolean isCall, boolean isLong, int multiplier) {}

    // Open position tracking for one symbol
    static final class OpenPosition {
        final String symbol;
        final LocalDate expiry;
        final int contracts;
        final double entrySigma;
        final double totalCostBasis; // net debit paid (positive = money out); credit spreads: negative
        final double maxProfit;      // 0 = unlimited (long legs); positive for spreads / covered calls
        final List<Leg> legs;
        final int stockShares;       // > 0 only for covered call
        final double stockEntryPrice;
        final double profitTargetFraction; // fraction of maxProfit at which to close; 0 = not used
        final double requiredMargin;       // cash to reserve for credit trades; 0 = derive from totalCostBasis

        OpenPosition(String symbol, LocalDate expiry, int contracts, double entrySigma,
                     double totalCostBasis, double maxProfit, List<Leg> legs,
                     int stockShares, double stockEntryPrice,
                     double profitTargetFraction, double requiredMargin) {
            this.symbol = symbol;
            this.expiry = expiry;
            this.contracts = contracts;
            this.entrySigma = entrySigma;
            this.totalCostBasis = totalCostBasis;
            this.maxProfit = maxProfit;
            this.legs = legs;
            this.stockShares = stockShares;
            this.stockEntryPrice = stockEntryPrice;
            this.profitTargetFraction = profitTargetFraction;
            this.requiredMargin = requiredMargin;
        }

        // Total individual option contracts across all legs (for fee calculation)
        int totalOptionContracts() {
            return legs.stream().mapToInt(Leg::multiplier).sum() * contracts;
        }
    }

    public BacktestResult run(OptionsStrategy strategy, BacktestConfig config,
                               Map<String, List<HistoricalBar>> barsBySymbol) {
        if (barsBySymbol.isEmpty()) {
            return new BacktestResult(Collections.emptyList(), 0.0, 0.0, 0, 0, 0);
        }

        Map<String, Map<LocalDate, HistoricalBar>> index = new HashMap<>();
        Set<LocalDate> allDates = new TreeSet<>();
        for (var entry : barsBySymbol.entrySet()) {
            Map<LocalDate, HistoricalBar> dateMap = new HashMap<>();
            for (HistoricalBar bar : entry.getValue()) {
                dateMap.put(bar.getDate(), bar);
                allDates.add(bar.getDate());
            }
            index.put(entry.getKey(), dateMap);
        }

        List<LocalDate> tradingDays = new ArrayList<>(allDates);
        double cash = config.getStartingBalance();
        Map<String, OpenPosition> openPositions = new HashMap<>();
        Map<String, List<Double>> priceHistoryMap = new HashMap<>();
        Map<String, List<Double>> volumeHistoryMap = new HashMap<>();
        Map<String, Double> lastClose = new HashMap<>();

        int wins = 0, losses = 0, totalTrades = 0;
        List<BacktestDataPoint> equityCurve = new ArrayList<>();

        for (LocalDate date : tradingDays) {
            for (String symbol : config.getSymbols()) {
                Map<LocalDate, HistoricalBar> symbolIndex = index.get(symbol);
                if (symbolIndex == null) continue;
                HistoricalBar bar = symbolIndex.get(date);
                if (bar == null) continue;

                double close = bar.getClose();
                lastClose.put(symbol, close);

                List<Double> prices = priceHistoryMap.computeIfAbsent(symbol, k -> new ArrayList<>());
                List<Double> volumes = volumeHistoryMap.computeIfAbsent(symbol, k -> new ArrayList<>());
                prices.add(close);
                volumes.add((double) bar.getVolume());

                if (prices.size() < 30) continue;

                List<SignalResult> signals = indicatorEngine.evaluateAll(prices, volumes, close);
                int buys = indicatorEngine.countBuySignals(signals);
                int sells = indicatorEngine.countSellSignals(signals);

                // Exit check
                if (openPositions.containsKey(symbol)) {
                    OpenPosition pos = openPositions.get(symbol);
                    double sigma = effectiveSigma(pos, prices);
                    double T = Math.max(0, ChronoUnit.DAYS.between(date, pos.expiry) / 365.0);
                    double currentValue = computeCurrentValue(pos, close, T, sigma);

                    if (shouldExit(pos, currentValue, buys, sells, date, strategy, close)) {
                        double pnl = currentValue - pos.totalCostBasis;
                        cash += currentValue - exitSlippage(pos) - CONTRACT_FEE * pos.totalOptionContracts();
                        totalTrades++;
                        if (pnl > 0) wins++; else losses++;
                        openPositions.remove(symbol);
                    }
                }

                // Entry check
                if (!openPositions.containsKey(symbol) && entryCondition(strategy, buys, sells)) {
                    double sigma = bsEngine.historicalVol(prices);
                    if (sigma <= 0) continue;
                    int expDays = expiryDays(strategy);
                    LocalDate expiry = date.plusDays(expDays);
                    double T = expDays / 365.0;
                    double K = bsEngine.roundStrike(close);
                    OpenPosition pos = buildPosition(strategy, symbol, close, K, expiry, T, sigma, cash);
                    if (pos != null) {
                        double fees = CONTRACT_FEE * pos.totalOptionContracts();
                        boolean canAfford = pos.requiredMargin > 0
                                ? cash >= pos.requiredMargin + fees
                                : (pos.totalCostBasis >= 0
                                        ? cash >= pos.totalCostBasis + fees
                                        : cash >= fees);
                        if (canAfford) {
                            cash -= pos.totalCostBasis + fees; // credit spreads: cash increases
                            openPositions.put(symbol, pos);
                        }
                    }
                }
            }

            // Mark-to-market equity curve
            double holdingsValue = 0.0;
            for (var e : openPositions.entrySet()) {
                OpenPosition pos = e.getValue();
                double price = lastClose.getOrDefault(e.getKey(), 0.0);
                if (price > 0) {
                    List<Double> prices = priceHistoryMap.getOrDefault(e.getKey(), Collections.emptyList());
                    double sigma = effectiveSigma(pos, prices);
                    double T = Math.max(0, ChronoUnit.DAYS.between(date, pos.expiry) / 365.0);
                    holdingsValue += computeCurrentValue(pos, price, T, sigma);
                }
            }
            equityCurve.add(new BacktestDataPoint(date, cash + holdingsValue));
        }

        // Close remaining positions at intrinsic value (T=0)
        for (var e : openPositions.entrySet()) {
            OpenPosition pos = e.getValue();
            double price = lastClose.getOrDefault(e.getKey(), 0.0);
            if (price > 0) {
                List<Double> prices = priceHistoryMap.getOrDefault(e.getKey(), Collections.emptyList());
                double sigma = effectiveSigma(pos, prices);
                double currentValue = computeCurrentValue(pos, price, 0.0, sigma);
                cash += currentValue - exitSlippage(pos) - CONTRACT_FEE * pos.totalOptionContracts();
            }
        }

        double finalValue = equityCurve.isEmpty() ? config.getStartingBalance()
                : equityCurve.get(equityCurve.size() - 1).getPortfolioValue();
        double totalReturnPct = (finalValue - config.getStartingBalance()) / config.getStartingBalance() * 100.0;

        double maxDrawdownPct = 0.0;
        double peak = config.getStartingBalance();
        for (BacktestDataPoint pt : equityCurve) {
            if (pt.getPortfolioValue() > peak) peak = pt.getPortfolioValue();
            if (peak > 0) {
                double dd = (peak - pt.getPortfolioValue()) / peak * 100.0;
                if (dd > maxDrawdownPct) maxDrawdownPct = dd;
            }
        }

        return new BacktestResult(equityCurve, totalReturnPct, maxDrawdownPct, wins, losses, totalTrades);
    }

    // Mark-to-market value of the entire position at the current price and time remaining.
    // Credit spreads return a negative value (the cost to close). Cash received at entry is already
    // in the cash balance, so net equity = cash + holdingsValue is correct throughout.
    double computeCurrentValue(OpenPosition pos, double price, double T, double sigma) {
        double value = 0;
        for (Leg leg : pos.legs) {
            double premium;
            if (T <= 0) {
                // At expiry: intrinsic value only
                premium = leg.isCall() ? Math.max(0, price - leg.strike())
                                       : Math.max(0, leg.strike() - price);
            } else {
                premium = leg.isCall()
                        ? bsEngine.callPrice(price, leg.strike(), RISK_FREE_RATE, T, sigma)
                        : bsEngine.putPrice(price, leg.strike(), RISK_FREE_RATE, T, sigma);
            }
            value += (leg.isLong() ? 1 : -1) * premium * 100.0 * pos.contracts * leg.multiplier();
        }
        // Stock component (covered call only)
        if (pos.stockShares > 0) {
            value += pos.stockShares * price;
        }
        return value;
    }

    boolean entryCondition(OptionsStrategy strategy, int buys, int sells) {
        return switch (strategy) {
            case LONG_CALL -> buys >= 2;
            case LONG_PUT -> sells >= 2;
            case ZERO_DTE -> buys >= 2 || sells >= 2;
            case HIGH_DELTA_SCALP, MOMENTUM_NEAR_TERM -> buys >= 3 || sells >= 3;
            // Put sellers want a neutral-to-bullish market: more buys than sells, no strong downtrend
            case PUT_CREDIT_SPREAD, CASH_SECURED_PUT -> buys >= sells && sells < 2;
            // Call sellers want a neutral-to-bearish market: more sells than buys, no strong uptrend
            case CALL_CREDIT_SPREAD -> sells >= buys && buys < 2;
            // Iron condor wants a quiet, rangebound market
            case IRON_CONDOR -> buys <= 1 && sells <= 1;
            case COVERED_CALL -> buys >= 2;
        };
    }

    static int expiryDays(OptionsStrategy strategy) {
        return switch (strategy) {
            case HIGH_DELTA_SCALP, MOMENTUM_NEAR_TERM, ZERO_DTE -> 7;
            default -> EXPIRY_DAYS;
        };
    }

    boolean shouldExit(OpenPosition pos, double currentValue, int buys, int sells,
                       LocalDate currentDate, OptionsStrategy strategy, double currentPrice) {
        long daysLeft = ChronoUnit.DAYS.between(currentDate, pos.expiry);
        if (daysLeft < 3) return true;

        // Long position stop loss and profit target (unlimited upside: maxProfit == 0)
        if (pos.totalCostBasis > 0 && pos.maxProfit == 0) {
            if (currentValue <= pos.totalCostBasis * STOP_LOSS_FRACTION) return true;
            if (currentValue >= pos.totalCostBasis * PROFIT_TARGET_MULTIPLIER) return true;
        }

        // EOD floor: close if value has fallen below the overnight hold threshold (mirrors live app)
        if (overnightMinPremiumFrac > 0 && pos.totalCostBasis > 0
                && currentValue < pos.totalCostBasis * overnightMinPremiumFrac) {
            return true;
        }

        // Price-based stop for credit strategies: exit when the underlying closes through the short
        // strike. This mirrors real-world discipline — "close and take the loss" once price breaches
        // the strike — and limits losses to roughly spread_width - 2×credit rather than a full loss.
        switch (strategy) {
            case PUT_CREDIT_SPREAD, CASH_SECURED_PUT -> {
                double shortPut = findShortPutStrike(pos);
                if (shortPut > 0 && currentPrice < shortPut) return true;
            }
            case CALL_CREDIT_SPREAD -> {
                double shortCall = findShortCallStrike(pos);
                if (shortCall > 0 && currentPrice > shortCall) return true;
            }
            case IRON_CONDOR -> {
                double shortPut  = findShortPutStrike(pos);
                double shortCall = findShortCallStrike(pos);
                if ((shortPut  > 0 && currentPrice < shortPut) ||
                    (shortCall > 0 && currentPrice > shortCall)) return true;
            }
            default -> {}
        }

        // Covered call stop loss: exit if stock falls 10% below entry price
        if (pos.stockShares > 0 && currentPrice < pos.stockEntryPrice * 0.90) return true;

        // Profit target for all capped-profit strategies (credit spreads and covered call)
        if (pos.maxProfit > 0 && pos.profitTargetFraction > 0) {
            double pnl = currentValue - pos.totalCostBasis;
            if (pnl >= pos.maxProfit * pos.profitTargetFraction) return true;
        }

        // Signal-based exits for directional strategies only; premium sellers let theta work
        return switch (strategy) {
            case LONG_CALL, HIGH_DELTA_SCALP, MOMENTUM_NEAR_TERM -> sells >= 2;
            case LONG_PUT -> buys >= 2;
            case ZERO_DTE -> false;
            case PUT_CREDIT_SPREAD, CALL_CREDIT_SPREAD, IRON_CONDOR,
                 CASH_SECURED_PUT, COVERED_CALL -> false;
        };
    }

    OpenPosition buildPosition(OptionsStrategy strategy, String symbol,
                               double price, double K, LocalDate expiry,
                               double T, double sigma, double cash) {
        return switch (strategy) {
            case LONG_CALL -> {
                double prem = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                double slipped = prem * (1 + SLIPPAGE_IV_PREMIUM) + SLIPPAGE_HALF_SPREAD;
                int c = contracts(cash, slipped);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, slipped * 100 * c, 0,
                        List.of(new Leg(K, true, true, 1)), 0, 0, 0, 0);
            }
            case LONG_PUT -> {
                double prem = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                double slipped = prem * (1 + SLIPPAGE_IV_PREMIUM) + SLIPPAGE_HALF_SPREAD;
                int c = contracts(cash, slipped);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, slipped * 100 * c, 0,
                        List.of(new Leg(K, false, true, 1)), 0, 0, 0, 0);
            }
            case HIGH_DELTA_SCALP -> {
                double deepK = Math.max(1.0, K - SPREAD_WIDTH * 2);
                double prem = bsEngine.callPrice(price, deepK, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                double slipped = prem * (1 + SLIPPAGE_IV_PREMIUM) + SLIPPAGE_HALF_SPREAD;
                int c = contracts(cash, slipped);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, slipped * 100 * c, 0,
                        List.of(new Leg(deepK, true, true, 1)), 0, 0, 0, 0);
            }
            case MOMENTUM_NEAR_TERM -> {
                double prem = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                double slipped = prem * (1 + SLIPPAGE_IV_PREMIUM) + SLIPPAGE_HALF_SPREAD;
                int c = contracts(cash, slipped);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, slipped * 100 * c, 0,
                        List.of(new Leg(K, true, true, 1)), 0, 0, 0, 0);
            }
            case ZERO_DTE -> {
                double callPrem = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                double putPrem  = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma);
                double slippedCall = callPrem * (1 + SLIPPAGE_IV_PREMIUM) + SLIPPAGE_HALF_SPREAD;
                double slippedPut  = putPrem  * (1 + SLIPPAGE_IV_PREMIUM) + SLIPPAGE_HALF_SPREAD;
                double netDebit = slippedCall + slippedPut;
                if (netDebit <= 0) yield null;
                int c = contracts(cash, netDebit);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, netDebit * 100 * c, 0,
                        List.of(new Leg(K, true, true, 1), new Leg(K, false, true, 1)), 0, 0, 0, 0);
            }
            case PUT_CREDIT_SPREAD -> {
                // Short put near PREMIUM_DELTA_TARGET delta; long put CREDIT_SPREAD_WIDTH below.
                // The short leg receives CREDIT_IV_PREMIUM above BS fair value (IV > HV edge).
                double kShort = findOtmPutStrike(price, RISK_FREE_RATE, T, sigma, PREMIUM_DELTA_TARGET);
                double kLong  = kShort - CREDIT_SPREAD_WIDTH;
                if (kLong <= 0) yield null;
                double shortPrem = bsEngine.putPrice(price, kShort, RISK_FREE_RATE, T, sigma)
                                   * (1 + CREDIT_IV_PREMIUM);
                double longPrem  = bsEngine.putPrice(price, kLong,  RISK_FREE_RATE, T, sigma);
                double netCredit = shortPrem - longPrem - 2 * SLIPPAGE_HALF_SPREAD;
                if (netCredit < CREDIT_SPREAD_WIDTH * CREDIT_MIN_CREDIT_PCT) yield null;
                int c = creditContracts(cash, CREDIT_SPREAD_WIDTH - netCredit);
                if (c < 1) yield null;
                double totalCredit = netCredit * 100 * c;
                double maxLoss     = (CREDIT_SPREAD_WIDTH - netCredit) * 100 * c;
                yield new OpenPosition(symbol, expiry, c, sigma,
                        -totalCredit, totalCredit,
                        List.of(new Leg(kShort, false, false, 1), new Leg(kLong, false, true, 1)),
                        0, 0, CREDIT_MONTHLY_PROFIT_TARGET, maxLoss);
            }
            case CALL_CREDIT_SPREAD -> {
                // Short call near PREMIUM_DELTA_TARGET delta; long call CREDIT_SPREAD_WIDTH above.
                double kShort = findOtmCallStrike(price, RISK_FREE_RATE, T, sigma, PREMIUM_DELTA_TARGET);
                double kLong  = kShort + CREDIT_SPREAD_WIDTH;
                double shortPrem = bsEngine.callPrice(price, kShort, RISK_FREE_RATE, T, sigma)
                                   * (1 + CREDIT_IV_PREMIUM);
                double longPrem  = bsEngine.callPrice(price, kLong,  RISK_FREE_RATE, T, sigma);
                double netCredit = shortPrem - longPrem - 2 * SLIPPAGE_HALF_SPREAD;
                if (netCredit < CREDIT_SPREAD_WIDTH * CREDIT_MIN_CREDIT_PCT) yield null;
                int c = creditContracts(cash, CREDIT_SPREAD_WIDTH - netCredit);
                if (c < 1) yield null;
                double totalCredit = netCredit * 100 * c;
                double maxLoss     = (CREDIT_SPREAD_WIDTH - netCredit) * 100 * c;
                yield new OpenPosition(symbol, expiry, c, sigma,
                        -totalCredit, totalCredit,
                        List.of(new Leg(kShort, true, false, 1), new Leg(kLong, true, true, 1)),
                        0, 0, CREDIT_MONTHLY_PROFIT_TARGET, maxLoss);
            }
            case IRON_CONDOR -> {
                // Put wing below + call wing above; collect premium from both sides.
                double putShort  = findOtmPutStrike(price,  RISK_FREE_RATE, T, sigma, PREMIUM_DELTA_TARGET);
                double putLong   = putShort - CREDIT_SPREAD_WIDTH;
                double callShort = findOtmCallStrike(price, RISK_FREE_RATE, T, sigma, PREMIUM_DELTA_TARGET);
                double callLong  = callShort + CREDIT_SPREAD_WIDTH;
                if (putLong <= 0 || putShort >= callShort) yield null;
                double putCredit  = bsEngine.putPrice(price,  putShort,  RISK_FREE_RATE, T, sigma)
                                    * (1 + CREDIT_IV_PREMIUM)
                                  - bsEngine.putPrice(price,  putLong,   RISK_FREE_RATE, T, sigma);
                double callCredit = bsEngine.callPrice(price, callShort, RISK_FREE_RATE, T, sigma)
                                    * (1 + CREDIT_IV_PREMIUM)
                                  - bsEngine.callPrice(price, callLong,  RISK_FREE_RATE, T, sigma);
                double netCredit  = putCredit + callCredit - 4 * SLIPPAGE_HALF_SPREAD;
                if (netCredit < CREDIT_SPREAD_WIDTH * CREDIT_MIN_CREDIT_PCT) yield null;
                // Size on the worse-case single wing; only one side can reach max loss.
                double worstWingRisk = Math.max(CREDIT_SPREAD_WIDTH - putCredit,
                                                CREDIT_SPREAD_WIDTH - callCredit);
                int c = creditContracts(cash, worstWingRisk);
                if (c < 1) yield null;
                double totalCredit = netCredit * 100 * c;
                double maxLoss     = worstWingRisk * 100 * c;
                yield new OpenPosition(symbol, expiry, c, sigma,
                        -totalCredit, totalCredit,
                        List.of(new Leg(putShort,  false, false, 1), new Leg(putLong,  false, true, 1),
                                new Leg(callShort, true,  false, 1), new Leg(callLong, true,  true, 1)),
                        0, 0, CREDIT_MONTHLY_PROFIT_TARGET, maxLoss);
            }
            case CASH_SECURED_PUT -> {
                // Short OTM put at ~0.20 delta; reserve the full strike × 100 × c in cash.
                double kShort = findOtmPutStrike(price, RISK_FREE_RATE, T, sigma, PREMIUM_DELTA_TARGET);
                if (kShort <= 0) yield null;
                double putPrem   = bsEngine.putPrice(price, kShort, RISK_FREE_RATE, T, sigma)
                                   * (1 + CREDIT_IV_PREMIUM);
                double netCredit = putPrem - SLIPPAGE_HALF_SPREAD;
                if (netCredit < kShort * 0.005) yield null; // minimum 0.5% of strike as credit
                // 2% risk per trade; secured capital = kShort × 100 (capital-intensive)
                int c = Math.min(MAX_CONTRACTS, Math.max(1, (int)(cash * 0.02 / (kShort * 100))));
                double totalCredit = netCredit * 100 * c;
                double securedCash = kShort * 100 * c;
                yield new OpenPosition(symbol, expiry, c, sigma,
                        -totalCredit, totalCredit,
                        List.of(new Leg(kShort, false, false, 1)),
                        0, 0, CREDIT_MONTHLY_PROFIT_TARGET, securedCash);
            }
            case COVERED_CALL -> {
                // Buy 100 × c shares; sell an OTM call to collect income against the position.
                double kCall         = findOtmCallStrike(price, RISK_FREE_RATE, T, sigma, PREMIUM_DELTA_TARGET);
                double callPrem      = bsEngine.callPrice(price, kCall, RISK_FREE_RATE, T, sigma)
                                       * (1 + CREDIT_IV_PREMIUM);
                double netCallCredit = callPrem - SLIPPAGE_HALF_SPREAD;
                if (netCallCredit < 0.05) yield null;
                // 20% position size per unit — stock ownership is the dominant capital requirement
                int c = Math.min(MAX_CONTRACTS, Math.max(1, (int)(cash * 0.20 / (price * 100))));
                int shares = 100 * c;
                double netCostPerShare   = price - netCallCredit;
                double maxProfitPerShare = kCall - price + netCallCredit;
                if (maxProfitPerShare <= 0) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma,
                        netCostPerShare * 100 * c, maxProfitPerShare * 100 * c,
                        List.of(new Leg(kCall, true, false, 1)),
                        shares, price, CREDIT_MONTHLY_PROFIT_TARGET, 0);
            }
        };
    }

    // Scan downward from ATM to find the put strike where |delta| ≈ targetAbsDelta
    private double findOtmPutStrike(double S, double r, double T, double sigma, double targetAbsDelta) {
        double K = bsEngine.roundStrike(S);
        for (int i = 0; i < 20; i++) {
            GreeksResult g = bsEngine.greeks(S, K, r, T, sigma, false);
            if (Math.abs(g.delta) <= targetAbsDelta) break;
            K -= 5.0;
            if (K <= 0) return bsEngine.roundStrike(S * 0.85);
        }
        return Math.max(5.0, K);
    }

    // Scan upward from ATM to find the call strike where delta ≈ targetDelta
    private double findOtmCallStrike(double S, double r, double T, double sigma, double targetDelta) {
        double K = bsEngine.roundStrike(S);
        for (int i = 0; i < 20; i++) {
            GreeksResult g = bsEngine.greeks(S, K, r, T, sigma, true);
            if (g.delta <= targetDelta) break;
            K += 5.0;
        }
        return K;
    }

    // Find the short put strike from a position's legs (the sold put leg)
    private static double findShortPutStrike(OpenPosition pos) {
        for (Leg leg : pos.legs) {
            if (!leg.isCall() && !leg.isLong()) return leg.strike();
        }
        return -1;
    }

    // Find the short call strike from a position's legs (the sold call leg)
    private static double findShortCallStrike(OpenPosition pos) {
        for (Leg leg : pos.legs) {
            if (leg.isCall() && !leg.isLong()) return leg.strike();
        }
        return -1;
    }

    // Spread cost paid when closing: sell at bid (mid minus half-spread) per leg per contract.
    private double exitSlippage(OpenPosition pos) {
        return SLIPPAGE_HALF_SPREAD * 100.0 * pos.totalOptionContracts();
    }

    private double effectiveSigma(OpenPosition pos, List<Double> prices) {
        double sigma = bsEngine.historicalVol(prices);
        return sigma > 0 ? sigma : pos.entrySigma;
    }

    // Standard 5% position sizing for long strategies
    private int contracts(double cash, double netDebitPerShare) {
        if (netDebitPerShare <= 0) return 0;
        return Math.min(MAX_CONTRACTS, (int) (cash * POSITION_SIZE_PCT / (netDebitPerShare * 100)));
    }

    // 2% risk-based sizing for credit strategies (handbook: "no single trade > 1-2% of capital")
    private int creditContracts(double cash, double riskPerShare) {
        if (riskPerShare <= 0) return 0;
        return Math.min(MAX_CONTRACTS, Math.max(1, (int) (cash * 0.02 / (riskPerShare * 100))));
    }
}
