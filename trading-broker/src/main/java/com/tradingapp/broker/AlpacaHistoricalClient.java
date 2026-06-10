package com.tradingapp.broker;

import com.tradingapp.engine.IntradayBar;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class AlpacaHistoricalClient {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // Per-day cache: ~/.tradingapp/bar-cache/1min/{SYMBOL}/{YYYY-MM-DD}.json
    // Empty array [] is written for no-data days (holidays) to avoid re-fetching.
    private static final Path CACHE_ROOT = Path.of(System.getProperty("user.home"),
            ".tradingapp", "bar-cache", "1min");

    private final AppConfig config;
    private final HttpClient http;

    public AlpacaHistoricalClient(AppConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetches 1-minute bars for [start, end] inclusive.
     * Each trading day is cached independently under CACHE_ROOT/{symbol}/{date}.json,
     * so re-running with a different date range reuses every day already on disk.
     */
    public List<IntradayBar> fetchMinuteBars(String symbol, LocalDate start, LocalDate end,
                                              Consumer<String> progress) throws Exception {
        // Collect days that still need fetching from Alpaca
        List<LocalDate> missingDays = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            if (!Files.exists(dayFile(symbol, d))) missingDays.add(d);
        }

        if (!missingDays.isEmpty()) {
            LocalDate fetchStart = missingDays.get(0);
            LocalDate fetchEnd   = missingDays.get(missingDays.size() - 1);
            if (progress != null) progress.accept(symbol + ": fetching " + missingDays.size()
                    + " uncached days from Alpaca...");

            List<IntradayBar> fetched = fetchFromAlpaca(symbol, fetchStart, fetchEnd, true);
            if (fetched.isEmpty()) {
                if (progress != null) progress.accept(symbol + ": IEX empty, retrying without feed filter");
                fetched = fetchFromAlpaca(symbol, fetchStart, fetchEnd, false);
            }

            // Split fetched bars by day and cache each day individually
            java.util.Map<LocalDate, List<IntradayBar>> byDay = new java.util.TreeMap<>();
            for (IntradayBar bar : fetched) {
                byDay.computeIfAbsent(bar.time().toLocalDate(), d -> new ArrayList<>()).add(bar);
            }

            // Write cache for every missing day (empty array for holidays/no-data days)
            for (LocalDate d : missingDays) {
                List<IntradayBar> dayBars = byDay.getOrDefault(d, List.of());
                writeDayCache(dayBars, symbol, d);
            }

            if (progress != null) progress.accept(symbol + ": cached "
                    + byDay.values().stream().mapToInt(List::size).sum() + " bars across "
                    + byDay.size() + " days");
        } else {
            if (progress != null) progress.accept(symbol + ": loading from cache");
        }

        // Load all days from cache
        List<IntradayBar> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            Path f = dayFile(symbol, d);
            if (Files.exists(f)) result.addAll(loadDayCache(symbol, f));
        }
        return result;
    }

    private Path dayFile(String symbol, LocalDate date) {
        return CACHE_ROOT.resolve(symbol).resolve(date + ".json");
    }

    private List<IntradayBar> loadDayCache(String symbol, Path file) throws Exception {
        String content = Files.readString(file);
        JSONArray arr = new JSONArray(content);
        List<IntradayBar> bars = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject b = arr.getJSONObject(i);
            ZonedDateTime time = ZonedDateTime.parse(b.getString("t"), ISO).withZoneSameInstant(ET);
            bars.add(new IntradayBar(symbol, time,
                    b.getDouble("o"), b.getDouble("h"),
                    b.getDouble("l"), b.getDouble("c"),
                    b.optLong("v", 0L)));
        }
        return bars;
    }

    private void writeDayCache(List<IntradayBar> bars, String symbol, LocalDate date) throws Exception {
        Path file = dayFile(symbol, date);
        Files.createDirectories(file.getParent());
        JSONArray arr = new JSONArray();
        DateTimeFormatter fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        for (IntradayBar bar : bars) {
            JSONObject b = new JSONObject();
            b.put("t", bar.time().format(fmt));
            b.put("o", bar.open());
            b.put("h", bar.high());
            b.put("l", bar.low());
            b.put("c", bar.close());
            b.put("v", bar.volume());
            arr.put(b);
        }
        Files.writeString(file, arr.toString());
    }

    private List<IntradayBar> fetchFromAlpaca(String symbol, LocalDate start, LocalDate end,
                                               boolean useIexFeed) throws Exception {
        List<IntradayBar> bars = new ArrayList<>();
        String nextToken = null;
        String baseUrl = config.getAlpacaDataUrl() + "/v2/stocks/" + symbol + "/bars";

        do {
            StringBuilder url = new StringBuilder(baseUrl)
                    .append("?timeframe=1Min")
                    .append("&start=").append(URLEncoder.encode(start + "T00:00:00Z", StandardCharsets.UTF_8))
                    .append("&end=").append(URLEncoder.encode(end + "T23:59:59Z", StandardCharsets.UTF_8))
                    .append("&limit=10000");
            if (useIexFeed) url.append("&feed=iex");
            if (nextToken != null) url.append("&page_token=").append(URLEncoder.encode(nextToken, StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new Exception("Alpaca bars HTTP " + resp.statusCode() + " for " + symbol + ": " + resp.body());
            }

            JSONObject json = new JSONObject(resp.body());
            JSONArray barsArr = json.optJSONArray("bars");
            if (barsArr != null) {
                for (int i = 0; i < barsArr.length(); i++) {
                    JSONObject b = barsArr.getJSONObject(i);
                    ZonedDateTime time = ZonedDateTime.parse(b.getString("t"), ISO).withZoneSameInstant(ET);
                    bars.add(new IntradayBar(symbol, time,
                            b.getDouble("o"), b.getDouble("h"),
                            b.getDouble("l"), b.getDouble("c"),
                            b.optLong("v", 0L)));
                }
            }

            nextToken = json.optString("next_page_token", null);
            if (nextToken != null && nextToken.isEmpty()) nextToken = null;

        } while (nextToken != null);

        return bars;
    }
}
