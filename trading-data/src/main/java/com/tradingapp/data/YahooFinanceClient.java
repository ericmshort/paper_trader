package com.tradingapp.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class YahooFinanceClient {

    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {500L, 1000L, 2000L};

    private final HttpClient httpClient;

    public YahooFinanceClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public QuoteModel getQuote(String symbol) {
        String url = BASE_URL + symbol + "?interval=1m&range=1d";
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    throw new IOException("Rate limited (429) for " + symbol);
                }
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + symbol);
                }

                return parseChartResponse(symbol, response.body());

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new DataUnavailableException(symbol, e);
                }
                lastException = e;
                System.err.println("Quote fetch attempt " + (attempt + 1) + " failed for " + symbol + ": " + e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(BACKOFF_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new DataUnavailableException(symbol, ie);
                    }
                }
            }
        }
        throw new DataUnavailableException(symbol, lastException);
    }

    public List<QuoteModel> getQuotes(List<String> symbols) {
        List<QuoteModel> results = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                QuoteModel quote = getQuote(symbol);
                if (quote != null) {
                    results.add(quote);
                }
                Thread.sleep(200);
            } catch (DataUnavailableException e) {
                System.err.println("Skipping " + symbol + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return results;
    }

    private QuoteModel parseChartResponse(String symbol, String json) {
        JSONObject root = new JSONObject(json);
        JSONObject chart = root.getJSONObject("chart");
        JSONArray results = chart.getJSONArray("result");
        if (results.isEmpty()) {
            throw new DataUnavailableException("No chart data returned for " + symbol);
        }
        JSONObject meta = results.getJSONObject(0).getJSONObject("meta");
        double price = meta.optDouble("regularMarketPrice", 0.0);
        long volume = meta.optLong("regularMarketVolume", 0L);
        long fetchedAt = System.currentTimeMillis();
        return QuoteModel.fromLive(symbol, price, volume, fetchedAt);
    }
}
