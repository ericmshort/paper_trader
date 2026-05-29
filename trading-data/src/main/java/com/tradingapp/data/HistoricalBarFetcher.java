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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HistoricalBarFetcher {

    private static final String BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {500L, 1000L, 2000L};

    private final HttpClient httpClient;

    public HistoricalBarFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<HistoricalBar> fetchDailyBars(String symbol, LocalDate startDate, LocalDate endDate) {
        return fetchBarsWithInterval(symbol, startDate, endDate, "1d");
    }

    public List<HistoricalBar> fetchBarsWithInterval(String symbol, LocalDate startDate,
                                                      LocalDate endDate, String interval) {
        long period1 = startDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = endDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String url = BASE_URL + symbol + "?interval=" + interval
                + "&period1=" + period1 + "&period2=" + period2;

        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429) {
                    lastException = new DataUnavailableException("Rate limited (429) for " + symbol);
                    if (attempt < MAX_RETRIES - 1) Thread.sleep(BACKOFF_MS[attempt]);
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new DataUnavailableException("HTTP " + response.statusCode() + " for " + symbol);
                }

                return parseBars(symbol, response.body());

            } catch (DataUnavailableException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DataUnavailableException(symbol, e);
            } catch (Exception e) {
                lastException = e;
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

    private List<HistoricalBar> parseBars(String symbol, String json) {
        JSONObject root = new JSONObject(json);
        JSONObject chart = root.getJSONObject("chart");
        JSONArray results = chart.getJSONArray("result");
        if (results.isEmpty()) {
            throw new DataUnavailableException("No chart data returned for " + symbol);
        }

        JSONObject result = results.getJSONObject(0);
        JSONArray timestamps = result.getJSONArray("timestamp");
        JSONObject indicators = result.getJSONObject("indicators");
        JSONObject quote = indicators.getJSONArray("quote").getJSONObject(0);
        JSONArray opens = quote.optJSONArray("open");
        JSONArray highs = quote.optJSONArray("high");
        JSONArray lows = quote.optJSONArray("low");
        JSONArray closes = quote.getJSONArray("close");
        JSONArray volumes = quote.getJSONArray("volume");

        // Extract split/dividend-adjusted close when available
        JSONArray adjCloses = null;
        if (indicators.has("adjclose")) {
            JSONArray adjCloseOuter = indicators.getJSONArray("adjclose");
            if (!adjCloseOuter.isEmpty()) {
                adjCloses = adjCloseOuter.getJSONObject(0).optJSONArray("adjclose");
            }
        }

        List<HistoricalBar> bars = new ArrayList<>();
        for (int i = 0; i < timestamps.length(); i++) {
            if (closes.isNull(i)) continue;
            long epochSec = timestamps.getLong(i);
            LocalDate date = Instant.ofEpochSecond(epochSec).atZone(ZoneOffset.UTC).toLocalDate();
            double close = closes.getDouble(i);
            double open = (opens != null && !opens.isNull(i)) ? opens.getDouble(i) : close;
            double high = (highs != null && !highs.isNull(i)) ? highs.getDouble(i) : close;
            double low = (lows != null && !lows.isNull(i)) ? lows.getDouble(i) : close;
            long volume = volumes.isNull(i) ? 0L : volumes.getLong(i);
            double adjClose = (adjCloses != null && !adjCloses.isNull(i))
                    ? adjCloses.getDouble(i) : close;
            bars.add(new HistoricalBar(symbol, date, open, high, low, close, adjClose, volume));
        }

        bars.sort(Comparator.comparing(HistoricalBar::getDate));
        return bars;
    }
}
