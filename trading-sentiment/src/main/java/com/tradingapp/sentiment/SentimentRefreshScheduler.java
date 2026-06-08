package com.tradingapp.sentiment;

import com.tradingapp.data.NewsSentimentCache;
import com.tradingapp.data.SentimentDirection;
import com.tradingapp.data.SentimentScore;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs once at 9:00 AM ET each trading day (before market open) to populate
 * NewsSentimentCache. Also fires immediately on startup if today's data is stale.
 */
public class SentimentRefreshScheduler {

    private static final LocalTime TARGET_TIME = LocalTime.of(9, 0);
    private static final ZoneId    ET          = ZoneId.of("America/New_York");

    private final NewsSentimentCache  cache;
    private final NewsHeadlineFetcher fetcher;
    private final ClaudeNewsAnalyzer  analyzer;
    private final List<String>        symbols;
    private final Consumer<String>    log;
    private final ScheduledExecutorService scheduler;

    public SentimentRefreshScheduler(NewsSentimentCache cache, String claudeApiKey,
                                     List<String> symbols, Consumer<String> log) {
        this.cache     = cache;
        this.fetcher   = new NewsHeadlineFetcher();
        this.analyzer  = new ClaudeNewsAnalyzer(claudeApiKey);
        this.symbols   = symbols;
        this.log       = log;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sentiment-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduleNextDailyRun();
        // Fire immediately if we don't have fresh data (startup after 9 AM, or first run ever)
        scheduler.submit(this::refreshIfStale);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void scheduleNextDailyRun() {
        ZonedDateTime now     = ZonedDateTime.now(ET);
        ZonedDateTime nextRun = now.toLocalDate().atTime(TARGET_TIME).atZone(ET);
        if (!now.toLocalTime().isBefore(TARGET_TIME)) nextRun = nextRun.plusDays(1);
        long delayMs = Duration.between(now, nextRun).toMillis();
        scheduler.schedule(() -> { refresh(); scheduleNextDailyRun(); }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void refreshIfStale() {
        if (!cache.isRefreshedToday()) refresh();
    }

    private void refresh() {
        try {
            log.accept("Sentiment: fetching headlines for " + symbols.size() + " symbols...");
            Map<String, List<String>> headlines = fetcher.fetchHeadlines(symbols);
            if (headlines.isEmpty()) {
                log.accept("Sentiment: no headlines available (market may be closed).");
                return;
            }
            log.accept("Sentiment: analyzing " + headlines.size() + " symbols with Claude...");
            List<SentimentScore> scores = analyzer.analyze(headlines);
            if (scores.isEmpty()) {
                log.accept("Sentiment: analysis returned no scores — check Claude API key in Settings.");
                return;
            }
            cache.refresh(scores, LocalDate.now());

            long bullish = scores.stream().filter(s -> s.direction() == SentimentDirection.BULLISH).count();
            long bearish = scores.stream().filter(s -> s.direction() == SentimentDirection.BEARISH).count();
            long neutral = scores.size() - bullish - bearish;
            log.accept(String.format("Sentiment loaded: %d bullish, %d neutral, %d bearish",
                    bullish, neutral, bearish));

            // Log strong signals so they appear prominently in the research area
            scores.stream()
                  .filter(s -> s.weight() >= 1.5)
                  .forEach(s -> log.accept(String.format("  %-6s [%-8s w=%.1f]: %s",
                          s.symbol(), s.direction(), s.weight(), s.summary())));
        } catch (Exception e) {
            log.accept("Sentiment refresh failed: " + e.getMessage());
        }
    }
}
