package com.tradingapp.broker;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.OptionsQuote;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AlpacaQuoteProvider implements QuoteProvider {

    private final AppConfig config;
    private final HttpClient http;

    // Cache options chains for 60 s — each symbol can be evaluated by multiple strategies
    // per tick (iron condor alone calls resolvePremium 4×), causing burst 429s without caching.
    private static final long CHAIN_CACHE_TTL_MS = 60_000;
    private final Map<String, OptionsChain> chainCache = new ConcurrentHashMap<>();
    private final Map<String, Long> chainCacheTime = new ConcurrentHashMap<>();

    public AlpacaQuoteProvider(AppConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public QuoteModel getQuote(String symbol) {
        try {
            String url = config.getAlpacaDataUrl() + "/v2/stocks/" + symbol + "/trades/latest";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JSONObject json = new JSONObject(resp.body());
                JSONObject trade = json.optJSONObject("trade");
                if (trade != null) {
                    double price = trade.optDouble("p", 0.0);
                    long size = trade.optLong("s", 0L);
                    if (price > 0.0) return QuoteModel.fromLive(symbol, price, size, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            System.err.println("Alpaca quote failed for " + symbol + ": " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<QuoteModel> getQuotes(List<String> symbols) {
        List<QuoteModel> results = new ArrayList<>();
        for (String symbol : symbols) {
            QuoteModel q = getQuote(symbol);
            if (q != null) results.add(q);
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return results;
    }

    @Override
    public OptionsChain getOptionsChain(String symbol, LocalDate expiry) {
        String cacheKey = symbol + "|" + expiry;
        Long cachedAt = chainCacheTime.get(cacheKey);
        if (cachedAt != null && System.currentTimeMillis() - cachedAt < CHAIN_CACHE_TTL_MS) {
            return chainCache.get(cacheKey);
        }

        Map<Double, OptionsQuote> calls = new HashMap<>();
        Map<Double, OptionsQuote> puts  = new HashMap<>();
        String expiryStr = expiry.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String pageToken = null;

        try {
            do {
                String url = config.getAlpacaDataUrl()
                        + "/v1beta1/options/snapshots/" + symbol
                        + "?feed=indicative&limit=1000"
                        + "&expiration_date=" + expiryStr
                        + (pageToken != null ? "&page_token=" + pageToken : "");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                        .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    System.err.println("Alpaca options snapshot failed for " + symbol
                            + " exp=" + expiryStr + ": HTTP " + resp.statusCode());
                    break;
                }
                JSONObject body = new JSONObject(resp.body());
                JSONObject snapshots = body.optJSONObject("snapshots");
                if (snapshots == null) break;

                for (String contractSymbol : snapshots.keySet()) {
                    JSONObject snap = snapshots.getJSONObject(contractSymbol);

                    // OCC symbol: {underlying}{YYMMDD}{C|P}{8-digit strike x 1000}
                    // e.g. AAPL260717C00110000 → Call, strike $110.00
                    if (contractSymbol.length() < 9) continue;
                    char typeChar = contractSymbol.charAt(contractSymbol.length() - 9);
                    String contractType;
                    if (typeChar == 'C') contractType = "call";
                    else if (typeChar == 'P') contractType = "put";
                    else continue;

                    double strike;
                    try {
                        strike = Long.parseLong(contractSymbol.substring(contractSymbol.length() - 8)) / 1000.0;
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (strike <= 0) continue;

                    JSONObject quote    = snap.optJSONObject("latestQuote");
                    JSONObject trade    = snap.optJSONObject("latestTrade");
                    JSONObject dailyBar = snap.optJSONObject("dailyBar");
                    double bid  = quote != null ? quote.optDouble("bp", 0.0) : 0.0;
                    double ask  = quote != null ? quote.optDouble("ap", 0.0) : 0.0;
                    double last = trade != null ? trade.optDouble("p", 0.0) : 0.0;
                    long   vol  = dailyBar != null ? dailyBar.optLong("v", 0L) : 0L;
                    long   oi   = snap.optLong("openInterest", 0L);

                    OptionsQuote optQuote = new OptionsQuote(bid, ask, last, vol, oi);
                    if ("call".equalsIgnoreCase(contractType)) {
                        calls.put(strike, optQuote);
                    } else if ("put".equalsIgnoreCase(contractType)) {
                        puts.put(strike, optQuote);
                    }
                }
                pageToken = body.isNull("nextPageToken") ? null : body.optString("nextPageToken", null);
            } while (pageToken != null && !pageToken.isEmpty());
        } catch (Exception e) {
            System.err.println("Alpaca options chain failed for " + symbol + ": " + e.getMessage());
        }

        OptionsChain result = new OptionsChain(calls, puts);
        chainCache.put(cacheKey, result);
        chainCacheTime.put(cacheKey, System.currentTimeMillis());
        return result;
    }

    @Override
    public List<HistoricalBar> getHistoricalBars(String symbol, LocalDate start, LocalDate end) {
        List<HistoricalBar> bars = new ArrayList<>();
        try {
            String url = config.getAlpacaDataUrl() + "/v2/stocks/" + symbol + "/bars"
                    + "?timeframe=1Day"
                    + "&start=" + start.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    + "&end=" + end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    + "&limit=1000";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JSONObject json = new JSONObject(resp.body());
                JSONArray barsArray = json.optJSONArray("bars");
                if (barsArray != null) {
                    for (int i = 0; i < barsArray.length(); i++) {
                        JSONObject b = barsArray.getJSONObject(i);
                        LocalDate date = LocalDate.parse(b.getString("t").substring(0, 10));
                        bars.add(new HistoricalBar(symbol, date,
                                b.optDouble("o", 0.0),
                                b.optDouble("h", 0.0),
                                b.optDouble("l", 0.0),
                                b.optDouble("c", 0.0),
                                b.optLong("v", 0L)));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Alpaca bars failed for " + symbol + ": " + e.getMessage());
        }
        return bars;
    }

    @Override
    public String getName() { return "Alpaca"; }

    /** Alpaca historical market data is available from roughly the start of 2016. */
    @Override
    public LocalDate getEarliestBacktestDate() { return LocalDate.of(2016, 1, 4); }
}
