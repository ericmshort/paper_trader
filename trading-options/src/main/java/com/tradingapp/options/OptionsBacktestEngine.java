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
    static final double SPREAD_WIDTH = 5.0;   // distance between strikes for spreads/strangle/butterfly
    static final int MAX_CONTRACTS = 5;
    static final double POSITION_SIZE_PCT = 0.05;
    static final double STOP_LOSS_FRACTION = 0.50;
    static final double PROFIT_TARGET_MULTIPLIER = 2.0;
    static final double SPREAD_PROFIT_TARGET_FRACTION = 0.75;
    static final double CONTRACT_FEE = 0.65;

    private final IndicatorEngine indicatorEngine;
    private final BlackScholesEngine bsEngine;
    private final FeeCalculator feeCalc;

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
        final int contracts;       // number of strategy units
        final double entrySigma;
        final double totalCostBasis; // net debit paid (positive = money out); for covered call: stock cost – premium received
        final double maxProfit;    // meaningful for spreads/butterfly/covered call; 0 = unlimited
        final List<Leg> legs;
        final int stockShares;     // > 0 only for covered call
        final double stockEntryPrice;

        OpenPosition(String symbol, LocalDate expiry, int contracts, double entrySigma,
                     double totalCostBasis, double maxProfit, List<Leg> legs,
                     int stockShares, double stockEntryPrice) {
            this.symbol = symbol;
            this.expiry = expiry;
            this.contracts = contracts;
            this.entrySigma = entrySigma;
            this.totalCostBasis = totalCostBasis;
            this.maxProfit = maxProfit;
            this.legs = legs;
            this.stockShares = stockShares;
            this.stockEntryPrice = stockEntryPrice;
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
                        cash += currentValue - CONTRACT_FEE * pos.totalOptionContracts();
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
                        // Credit spreads (totalCostBasis < 0): only need cash for fees
                        boolean canAfford = pos.totalCostBasis >= 0
                                ? cash >= pos.totalCostBasis + fees
                                : cash >= fees;
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
                cash += currentValue - CONTRACT_FEE * pos.totalOptionContracts();
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

    // Mark-to-market value of the entire position at the current price and time remaining
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

        if (pos.totalCostBasis > 0 && currentValue <= pos.totalCostBasis * STOP_LOSS_FRACTION) {
            return true;
        }

        if (pos.totalCostBasis > 0 && currentValue >= pos.totalCostBasis * PROFIT_TARGET_MULTIPLIER) return true;
        if (pos.maxProfit > 0
                && (currentValue - pos.totalCostBasis) >= pos.maxProfit * SPREAD_PROFIT_TARGET_FRACTION) return true;

        return switch (strategy) {
            case LONG_CALL, HIGH_DELTA_SCALP, MOMENTUM_NEAR_TERM -> sells >= 2;
            case LONG_PUT -> buys >= 2;
            case ZERO_DTE -> false;
        };
    }

    OpenPosition buildPosition(OptionsStrategy strategy, String symbol,
                               double price, double K, LocalDate expiry,
                               double T, double sigma, double cash) {
        return switch (strategy) {
            case LONG_CALL -> {
                double prem = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                int c = contracts(cash, prem);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, prem * 100 * c, 0,
                        List.of(new Leg(K, true, true, 1)), 0, 0);
            }
            case LONG_PUT -> {
                double prem = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                int c = contracts(cash, prem);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, prem * 100 * c, 0,
                        List.of(new Leg(K, false, true, 1)), 0, 0);
            }
            case HIGH_DELTA_SCALP -> {
                // Deep ITM call (buys ≥ 3) or put (sells ≥ 3); default to call in backtest
                double deepK = Math.max(1.0, K - SPREAD_WIDTH * 2);
                double prem = bsEngine.callPrice(price, deepK, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                int c = contracts(cash, prem);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, prem * 100 * c, 0,
                        List.of(new Leg(deepK, true, true, 1)), 0, 0);
            }
            case MOMENTUM_NEAR_TERM -> {
                // ATM call with near-term expiry
                double prem = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                if (prem <= 0) yield null;
                int c = contracts(cash, prem);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, prem * 100 * c, 0,
                        List.of(new Leg(K, true, true, 1)), 0, 0);
            }
            case ZERO_DTE -> {
                // Same-day straddle; T is already set to 7/365 (1 week) in the backtest for simplicity
                double callPrem = bsEngine.callPrice(price, K, RISK_FREE_RATE, T, sigma);
                double putPrem  = bsEngine.putPrice(price, K, RISK_FREE_RATE, T, sigma);
                double netDebit = callPrem + putPrem;
                if (netDebit <= 0) yield null;
                int c = contracts(cash, netDebit);
                if (c < 1) yield null;
                yield new OpenPosition(symbol, expiry, c, sigma, netDebit * 100 * c, 0,
                        List.of(new Leg(K, true, true, 1), new Leg(K, false, true, 1)), 0, 0);
            }
        };

    }

    private double effectiveSigma(OpenPosition pos, List<Double> prices) {
        double sigma = bsEngine.historicalVol(prices);
        return sigma > 0 ? sigma : pos.entrySigma;
    }

    private int contracts(double cash, double netDebitPerShare) {
        if (netDebitPerShare <= 0) return 0;
        return Math.min(MAX_CONTRACTS, (int) (cash * POSITION_SIZE_PCT / (netDebitPerShare * 100)));
    }
}
