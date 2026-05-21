package com.tradingapp.broker;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.OptionsChain;
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
import java.util.List;

public class AlpacaQuoteProvider implements QuoteProvider {

    private final AppConfig config;
    private final HttpClient http;

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
        return OptionsChain.empty();
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
}
