package com.tradingapp.data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe rolling window of intraday candle bars per symbol.
 * Written by the WebSocket feed thread; read by the trading loop thread.
 */
public class CandleHistory {

    private static final int MAX_CANDLES = 200;
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Current (incomplete) bar being built from live ticks
    private final ConcurrentHashMap<String, CandleBar> currentOneMin  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CandleBar> currentFiveMin = new ConcurrentHashMap<>();

    // Completed bars (oldest at head)
    private final ConcurrentHashMap<String, ArrayDeque<CandleBar>> oneMinBars  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayDeque<CandleBar>> fiveMinBars = new ConcurrentHashMap<>();

    /**
     * Record a trade tick. Aggregates into the current 1-min and 5-min bars,
     * sealing the previous bar when the period rolls over.
     */
    public void recordTick(String symbol, double price, double size, Instant tradeTime) {
        if (!Double.isFinite(price) || price <= 0) return;

        ZonedDateTime zdt = tradeTime.atZone(ET);
        Instant oneMinPeriod = zdt.truncatedTo(ChronoUnit.MINUTES).toInstant();
        Instant fiveMinPeriod = zdt
                .withMinute((zdt.getMinute() / 5) * 5)
                .truncatedTo(ChronoUnit.MINUTES)
                .toInstant();

        updateBar(symbol, price, size, oneMinPeriod,  CandleBar.Interval.ONE_MIN,
                currentOneMin, oneMinBars);
        updateBar(symbol, price, size, fiveMinPeriod, CandleBar.Interval.FIVE_MIN,
                currentFiveMin, fiveMinBars);
    }

    private void updateBar(String symbol, double price, double size,
                           Instant period, CandleBar.Interval interval,
                           ConcurrentHashMap<String, CandleBar> current,
                           ConcurrentHashMap<String, ArrayDeque<CandleBar>> completed) {
        CandleBar bar = current.get(symbol);
        if (bar == null || !bar.getPeriodStart().equals(period)) {
            // Seal old bar
            if (bar != null) {
                ArrayDeque<CandleBar> deque = completed.computeIfAbsent(symbol, k -> new ArrayDeque<>());
                synchronized (deque) {
                    deque.addLast(bar);
                    if (deque.size() > MAX_CANDLES) deque.pollFirst();
                }
            }
            // Open new bar
            current.put(symbol, new CandleBar(symbol, period, interval, price, size));
        } else {
            bar.update(price, size);
        }
    }

    /** Returns completed 1-min candles (oldest first). Does NOT include the current open bar. */
    public List<CandleBar> getOneMinBars(String symbol) {
        ArrayDeque<CandleBar> deque = oneMinBars.get(symbol);
        if (deque == null) return List.of();
        synchronized (deque) { return new ArrayList<>(deque); }
    }

    /** Returns completed 5-min candles (oldest first). Does NOT include the current open bar. */
    public List<CandleBar> getFiveMinBars(String symbol) {
        ArrayDeque<CandleBar> deque = fiveMinBars.get(symbol);
        if (deque == null) return List.of();
        synchronized (deque) { return new ArrayList<>(deque); }
    }

    /** Current (in-progress) 1-min bar, or null if no tick received yet for this period. */
    public CandleBar getCurrentOneMin(String symbol) {
        return currentOneMin.get(symbol);
    }

    /** Current (in-progress) 5-min bar, or null if no tick received yet for this period. */
    public CandleBar getCurrentFiveMin(String symbol) {
        return currentFiveMin.get(symbol);
    }

    /**
     * Returns all 1-min bars including the current open bar (useful for VWAP/ORB which
     * need the latest price even from an incomplete bar).
     */
    public List<CandleBar> getOneMinBarsWithCurrent(String symbol) {
        List<CandleBar> list = new ArrayList<>(getOneMinBars(symbol));
        CandleBar cur = currentOneMin.get(symbol);
        if (cur != null) list.add(cur);
        return list;
    }

    public List<CandleBar> getFiveMinBarsWithCurrent(String symbol) {
        List<CandleBar> list = new ArrayList<>(getFiveMinBars(symbol));
        CandleBar cur = currentFiveMin.get(symbol);
        if (cur != null) list.add(cur);
        return list;
    }

    public void clear(String symbol) {
        currentOneMin.remove(symbol);
        currentFiveMin.remove(symbol);
        oneMinBars.remove(symbol);
        fiveMinBars.remove(symbol);
    }
}
