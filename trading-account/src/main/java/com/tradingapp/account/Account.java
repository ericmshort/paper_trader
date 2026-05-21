package com.tradingapp.account;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Account {

    private volatile double balance;
    private final Map<String, Position> positions;
    private final Map<String, OptionsPosition> optionsPositions;
    private volatile double totalRealizedPnL;
    private volatile boolean tradingHalted;

    public Account() {
        this.balance = 100_000.0;
        this.positions = new ConcurrentHashMap<>();
        this.optionsPositions = new ConcurrentHashMap<>();
        this.totalRealizedPnL = 0.0;
        this.tradingHalted = false;
    }

    public double getBalance() { return balance; }
    public Map<String, Position> getPositions() { return Collections.unmodifiableMap(positions); }
    public Map<String, OptionsPosition> getOptionsPositions() { return Collections.unmodifiableMap(optionsPositions); }
    public double getTotalRealizedPnL() { return totalRealizedPnL; }
    public boolean isTradingHalted() { return tradingHalted; }

    public void setBalance(double balance) { this.balance = balance; }
    public void setTradingHalted(boolean tradingHalted) { this.tradingHalted = tradingHalted; }

    public synchronized void addRealizedPnL(double amount) { this.totalRealizedPnL += amount; }

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
    }
}
