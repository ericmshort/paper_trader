package com.tradingapp.sentiment;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches today's news headlines from Yahoo Finance RSS feeds.
 * No API key required. Returns up to 5 headlines per symbol.
 */
public class NewsHeadlineFetcher {

    private static final String RSS_URL = "https://feeds.finance.yahoo.com/rss/2.0/headline?s=%s&region=US&lang=en-US";
    private static final int TIMEOUT_MS = 8_000;
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);

    public Map<String, List<String>> fetchHeadlines(List<String> symbols) {
        Map<String, List<String>> result = new HashMap<>();
        for (String symbol : symbols) {
            try {
                List<String> headlines = fetchForSymbol(symbol);
                if (!headlines.isEmpty()) result.put(symbol, headlines);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private List<String> fetchForSymbol(String symbol) throws Exception {
        URL url = new URL(String.format(RSS_URL, symbol));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        List<String> headlines = new ArrayList<>();
        try (InputStream in = conn.getInputStream()) {
            String xml = new String(in.readAllBytes());
            Matcher m = TITLE_PATTERN.matcher(xml);
            boolean first = true;
            while (m.find() && headlines.size() < 5) {
                if (first) { first = false; continue; } // skip feed-level title
                String title = m.group(1).strip();
                if (!title.isBlank()) headlines.add(title);
            }
        } finally {
            conn.disconnect();
        }
        return headlines;
    }
}
