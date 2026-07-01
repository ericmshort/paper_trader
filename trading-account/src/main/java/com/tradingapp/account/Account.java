package com.tradingapp.account;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Account {

    public static final double STARTING_BALANCE = 100_000.0;

    private volatile double balance;
    private volatile double buyingPower;
    private volatile double lastEquity;
    private volatile double brokerPortfolioValue = -1.0;
    private final Map<String, Position> positions;
    private final Map<String, OptionsPosition> optionsPositions;
    private volatile double totalRealizedPnL;
    private volatile boolean tradingHalted;
    private volatile boolean dailyLossHalted;
    private final Set<String> verifiedOptionsKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> optionAddTimestamps = new ConcurrentHashMap<>();
    private volatile boolean brokerSyncComplete = false;
    // Running total of all cash flows attributable to premium seller positions
    // (credits received on open, debits paid on close). Used to exclude premium
    // spread activity from the daily loss limit calculation.
    private volatile double premiumCashBalance = 0.0;

    public Account() {
        this.balance = STARTING_BALANCE;
        this.positions = new ConcurrentHashMap<>();
        this.optionsPositions = new ConcurrentHashMap<>();
        this.totalRealizedPnL = 0.0;
        this.tradingHalted = false;
    }

    public double getBalance() { return balance; }
    public double getBuyingPower() { return buyingPower; }
    public double getLastEquity() { return lastEquity; }
    public double getBrokerPortfolioValue() { return brokerPortfolioValue; }
    public Map<String, Position> getPositions() { return Collections.unmodifiableMap(positions); }
    public Map<String, OptionsPosition> getOptionsPositions() { return Collections.unmodifiableMap(optionsPositions); }
    public double getTotalRealizedPnL() { return totalRealizedPnL; }
    public boolean isTradingHalted() { return tradingHalted; }
    public boolean isDailyLossHalted() { return dailyLossHalted; }
    public void setDailyLossHalted(boolean v) { this.dailyLossHalted = v; }
    public boolean isBrokerSyncComplete() { return brokerSyncComplete; }
    public void setBrokerSyncComplete(boolean v) { this.brokerSyncComplete = v; }

    public void setBalance(double balance) { this.balance = balance; }
    public void setBuyingPower(double buyingPower) { this.buyingPower = buyingPower; }
    public void setLastEquity(double lastEquity) { this.lastEquity = lastEquity; }
    public void setBrokerPortfolioValue(double v) { this.brokerPortfolioValue = v; }
    public void setTradingHalted(boolean tradingHalted) { this.tradingHalted = tradingHalted; }

    public synchronized void addRealizedPnL(double amount) { this.totalRealizedPnL += amount; }

    public double getPremiumCashBalance() { return premiumCashBalance; }
    public synchronized void addPremiumCash(double amount) { premiumCashBalance += amount; }

    /**
     * Returns the options cost-basis adjustment for NON-premium positions only.
     * Premium seller positions are excluded so that credit-spread cash effects
     * (which are tracked separately in premiumCashBalance) don't double-count.
     * Must stay in sync with the key patterns in PremiumSellerRouter.isPremiumKey().
     */
    public double getNonPremiumOptionsOptAdj() {
        return optionsPositions.entrySet().stream()
                .filter(e -> !isPremiumKey(e.getKey()))
                .mapToDouble(e -> e.getValue().getPremiumPaid() * 100 * e.getValue().getContracts())
                .sum();
    }

    private static boolean isPremiumKey(String key) {
        return key.contains("_PUTSPREAD_") || key.contains("_CALLSPREAD_")
            || key.contains("_IRONCONDOR_") || key.contains("_CSP_")
            || key.endsWith("_CC_CALL");
    }

    public double getTotalPortfolioValue() {
        double equity = positions.values().stream().mapToDouble(Position::getMarketValue).sum();
        double allOptions = optionsPositions.values().stream()
                .mapToDouble(p -> p.getPremiumPaid() * 100 * p.getContracts()).sum();
        return balance + equity + allOptions;
    }

    public double totalExposureFraction() {
        return totalExposureFraction(key -> false);
    }

    public double totalExposureFraction(java.util.function.Predicate<String> excludeKey) {
        // Only long (debit) positions consumed cash and count toward the deployment cap.
        // Short legs have negative contracts and would otherwise reduce the numerator,
        // letting credit spreads punch a hole in the cap.
        double equity = positions.values().stream().mapToDouble(Position::getMarketValue).sum();
        double deployedOptions = optionsPositions.entrySet().stream()
                .filter(e -> !excludeKey.test(e.getKey()))
                .filter(e -> e.getValue().getContracts() > 0)
                .mapToDouble(e -> e.getValue().getPremiumPaid() * 100 * e.getValue().getContracts())
                .sum();
        double totalPortfolioValue = getTotalPortfolioValue();
        if (totalPortfolioValue <= 0) return 1.0;
        return (equity + deployedOptions) / totalPortfolioValue;
    }

    public double getTotalUnrealizedPnL() {
        return positions.values().stream()
                .mapToDouble(Position::getUnrealizedPnL)
                .sum();
    }

    public void updatePositionPrice(String symbol, double currentPrice) {
        Position pos = positions.get(symbol);
        if (pos != null) {
            pos.setCurrentPrice(currentPrice);
        }
    }

    public void markAllUnverified() {
        brokerSyncComplete = false;
        positions.values().forEach(p -> p.setBrokerVerified(false));
        verifiedOptionsKeys.clear();
    }

    public void markStockVerified(String key) {
        Position pos = positions.get(key);
        if (pos != null) pos.setBrokerVerified(true);
    }

    public void markOptionVerified(String key) { verifiedOptionsKeys.add(key); }
    public boolean isStockVerified(String key) {
        Position pos = positions.get(key);
        return pos != null && pos.isBrokerVerified();
    }
    public boolean isOptionVerified(String key) { return verifiedOptionsKeys.contains(key); }

    public synchronized void setPositionFromBroker(String symbol, int quantity, double avgCost, Position.PositionType type) {
        positions.put(symbol, new Position(symbol, quantity, avgCost, type));
    }

    public synchronized void addOrUpdatePosition(String symbol, int quantity, double price, Position.PositionType type) {
        positions.merge(symbol,
                new Position(symbol, quantity, price, type),
                (existing, newPos) -> { existing.addShares(quantity, price); return existing; });
    }

    public void removePosition(String symbol) {
        positions.remove(symbol);
    }

    public synchronized void addOptionsPosition(String key, OptionsPosition pos) {
        optionsPositions.put(key, pos);
        optionAddTimestamps.put(key, System.currentTimeMillis());
    }

    public void removeOptionsPosition(String key) {
        optionsPositions.remove(key);
        // Preserve the timestamp so isOptionRecentlyAdded still returns true during the 90s
        // window after removal. This prevents PremiumSellerRouter from immediately re-entering
        // a position whose Alpaca order was rejected and rolled back.
    }

    /** Returns true if this option key was added to local state within the last 90 seconds.
     *  Used by BrokerSync to avoid evicting positions whose Alpaca fills are still settling. */
    public boolean isOptionRecentlyAdded(String key) {
        Long ts = optionAddTimestamps.get(key);
        return ts != null && System.currentTimeMillis() - ts < 90_000L;
    }

    public synchronized void reset(double startingBalance) {
        this.balance = startingBalance;
        this.buyingPower = 0.0;
        this.lastEquity = 0.0;
        this.brokerPortfolioValue = -1.0;
        this.positions.clear();
        this.optionsPositions.clear();
        this.totalRealizedPnL = 0.0;
        this.tradingHalted = false;
        this.dailyLossHalted = false;
        this.brokerSyncComplete = false;
        this.verifiedOptionsKeys.clear();
        this.optionAddTimestamps.clear();
    }
}
