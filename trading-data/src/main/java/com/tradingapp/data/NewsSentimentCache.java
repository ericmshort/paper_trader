package com.tradingapp.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe daily cache of per-symbol news sentiment scores.
 * Populated once per morning by SentimentRefreshScheduler; read on every
 * trading-loop tick by TradingLoop to inject a NEWS_SENTIMENT signal.
 */
public class NewsSentimentCache {

    private final Map<String, SentimentScore> scores = new ConcurrentHashMap<>();
    private volatile LocalDate refreshedDate;

    public void refresh(List<SentimentScore> newScores, LocalDate date) {
        scores.clear();
        newScores.forEach(s -> scores.put(s.symbol(), s));
        refreshedDate = date;
    }

    /** Returns null when no score is available for the symbol. */
    public SentimentScore getScore(String symbol) {
        return scores.get(symbol);
    }

    public boolean isRefreshedToday() {
        return LocalDate.now().equals(refreshedDate);
    }

    public LocalDate getRefreshedDate() { return refreshedDate; }
    public int size() { return scores.size(); }
}
