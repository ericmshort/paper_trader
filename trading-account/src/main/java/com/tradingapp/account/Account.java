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
    private final Map<String, Position> positions;
    private final Map<String, OptionsPosition> optionsPositions;
    private volatile double totalRealizedPnL;
    private volatile boolean tradingHalted;
    private volatile boolean dailyLossHalted;
    private final Set<String> verifiedOptionsKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean brokerSyncComplete = false;

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
    public void setTradingHalted(boolean tradingHalted) { this.tradingHalted = tradingHalted; }

    public synchronized void addRealizedPnL(double amount) { this.totalRealizedPnL += amount; }

    public double totalExposureFraction() {
        double equity = positions.values().stream().mapToDouble(Position::getMarketValue).sum();
        // allOptions includes short legs (negative contracts) for accurate portfolio value.
        double allOptions = optionsPositions.values().stream()
                .mapToDouble(p -> p.getPremiumPaid() * 100 * p.getContracts()).sum();
        // Only long (debit) positions consumed cash and count toward the deployment cap.
        // Short legs have negative contracts and would otherwise reduce the numerator,
        // letting credit spreads punch a hole in the 60% cap.
        double deployedOptions = optionsPositions.values().stream()
                .filter(p -> p.getContracts() > 0)
                .mapToDouble(p -> p.getPremiumPaid() * 100 * p.getContracts()).sum();
        double totalPortfolioValue = balance + equity + allOptions;
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
    }

    public void removeOptionsPosition(String key) {
        optionsPositions.remove(key);
    }

    public synchronized void reset(double startingBalance) {
        this.balance = startingBalance;
        this.positions.clear();
        this.optionsPositions.clear();
        this.totalRealizedPnL = 0.0;
        this.tradingHalted = false;
        this.dailyLossHalted = false;
        this.brokerSyncComplete = false;
        this.verifiedOptionsKeys.clear();
    }
}
