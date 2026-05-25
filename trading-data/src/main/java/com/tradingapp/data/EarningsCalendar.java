package com.tradingapp.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EarningsCalendar {

    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final HttpClient http;
    private final Map<String, LocalDate> earningsDateCache = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> lastFetchedDate = new ConcurrentHashMap<>();

    public EarningsCalendar() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Returns the next upcoming earnings date for symbol, or null if unknown. Result is cached per calendar day. */
    public LocalDate nextEarningsDate(String symbol) {
        LocalDate today = LocalDate.now(ET);
        LocalDate fetched = lastFetchedDate.get(symbol);
        if (fetched != null && fetched.equals(today)) {
            return earningsDateCache.get(symbol);
        }
        LocalDate result = fetchFromYahoo(symbol, today);
        lastFetchedDate.put(symbol, today);
        if (result != null) {
            earningsDateCache.put(symbol, result);
        } else {
            earningsDateCache.remove(symbol);
        }
        return result;
    }

    /** Days until next earnings. Returns Integer.MAX_VALUE when unknown (safe to trade). */
    public int daysUntilEarnings(String symbol) {
        LocalDate d = nextEarningsDate(symbol);
        if (d == null) return Integer.MAX_VALUE;
        return (int) LocalDate.now(ET).until(d, ChronoUnit.DAYS);
    }

    private LocalDate fetchFromYahoo(String symbol, LocalDate today) {
        try {
            String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + symbol
                    + "?modules=calendarEvents";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            JSONObject root = new JSONObject(resp.body());
            JSONObject qs = root.optJSONObject("quoteSummary");
            if (qs == null) return null;
            JSONArray results = qs.optJSONArray("result");
            if (results == null || results.isEmpty()) return null;
            JSONObject cal = results.getJSONObject(0).optJSONObject("calendarEvents");
            if (cal == null) return null;
            JSONObject earnings = cal.optJSONObject("earnings");
            if (earnings == null) return null;
            JSONArray dates = earnings.optJSONArray("earningsDate");
            if (dates == null) return null;
            for (int i = 0; i < dates.length(); i++) {
                JSONObject entry = dates.getJSONObject(i);
                long epochSec = entry.optLong("raw", 0L);
                if (epochSec <= 0) continue;
                LocalDate d = Instant.ofEpochSecond(epochSec).atZone(ET).toLocalDate();
                if (!d.isBefore(today)) return d;
            }
        } catch (Exception e) {
            System.err.println("EarningsCalendar fetch failed for " + symbol + ": " + e.getMessage());
        }
        return null;
    }
}
