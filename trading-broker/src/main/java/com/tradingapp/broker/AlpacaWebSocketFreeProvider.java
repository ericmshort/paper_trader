package com.tradingapp.broker;

import com.tradingapp.data.CandleHistory;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.OptionsChain;
import com.tradingapp.data.QuoteModel;
import com.tradingapp.data.QuoteProvider;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Real-time quote provider using Alpaca's free IEX WebSocket feed.
 * Limited to 30 symbols. Maintains a quote cache updated on every trade tick,
 * and aggregates ticks into CandleHistory for pattern detection.
 *
 * Usage:
 *   provider.start();   // begins WebSocket connection on a daemon thread
 *   provider.stop();    // graceful shutdown
 */
public class AlpacaWebSocketFreeProvider implements QuoteProvider {

    private static final String WS_URL = "wss://stream.data.alpaca.markets/v2/iex";
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final AppConfig config;
    private final CandleHistory candleHistory;
    private final HttpClient http;
    private final Consumer<String> logCallback;

    // Latest quote per symbol — written by WS thread, read by trading loop
    private final ConcurrentHashMap<String, QuoteModel> quoteCache = new ConcurrentHashMap<>();

    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean authed   = new AtomicBoolean(false);
    private volatile WebSocket ws;

    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alpaca-ws-reconnect");
        t.setDaemon(true);
        return t;
    });

    public AlpacaWebSocketFreeProvider(AppConfig config, CandleHistory candleHistory) {
        this(config, candleHistory, null);
    }

    public AlpacaWebSocketFreeProvider(AppConfig config, CandleHistory candleHistory,
                                       Consumer<String> logCallback) {
        this.config        = config;
        this.candleHistory = candleHistory;
        this.logCallback   = logCallback != null ? logCallback : msg -> {};
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Connects and starts the WebSocket feed. Safe to call multiple times. */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        connect();
    }

    /** Disconnects and shuts down. */
    public void stop() {
        running.set(false);
        reconnectScheduler.shutdownNow();
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    private void connect() {
        if (!running.get()) return;
        authed.set(false);
        try {
            http.newWebSocketBuilder()
                    .buildAsync(URI.create(WS_URL), new Listener())
                    .whenComplete((socket, ex) -> {
                        if (ex != null) {
                            log("WebSocket connect failed: " + ex.getMessage() + " — retrying in 5s");
                            scheduleReconnect();
                        } else {
                            ws = socket;
                        }
                    });
        } catch (Exception e) {
            log("WebSocket connect error: " + e.getMessage() + " — retrying in 5s");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        reconnectScheduler.schedule(this::connect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void handleMessage(String raw) {
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject msg = arr.getJSONObject(i);
                String type = msg.optString("T", "");
                switch (type) {
                    case "success" -> {
                        String m = msg.optString("msg", "");
                        if ("connected".equals(m)) {
                            sendAuth();
                        } else if ("authenticated".equals(m)) {
                            authed.set(true);
                            sendSubscribe();
                            log("AlpacaWS authenticated — subscribed to " + DayTraderWatchList.SYMBOLS.size() + " symbols");
                        }
                    }
                    case "error" -> log("AlpacaWS error: " + msg.optString("msg", raw));
                    case "t" -> handleTrade(msg);
                    case "q" -> handleQuote(msg);
                    default -> {} // subscription confirms, heartbeats, etc.
                }
            }
        } catch (Exception e) {
            log("AlpacaWS parse error: " + e.getMessage());
        }
    }

    private void sendAuth() {
        JSONObject auth = new JSONObject();
        auth.put("action", "auth");
        auth.put("key", config.getAlpacaApiKey());
        auth.put("secret", config.getAlpacaApiSecret());
        WebSocket w = ws;
        if (w != null) w.sendText(auth.toString(), true);
    }

    private void sendSubscribe() {
        JSONObject sub = new JSONObject();
        sub.put("action", "subscribe");
        sub.put("trades", new JSONArray(DayTraderWatchList.SYMBOLS));
        sub.put("quotes", new JSONArray(DayTraderWatchList.SYMBOLS));
        WebSocket w = ws;
        if (w != null) w.sendText(sub.toString(), true);
    }

    private void handleTrade(JSONObject msg) {
        String symbol = msg.optString("S", "");
        double price  = msg.optDouble("p", 0.0);
        long   size   = msg.optLong("s", 0L);
        String ts     = msg.optString("t", "");
        if (symbol.isEmpty() || price <= 0) return;

        Instant tradeTime;
        try {
            tradeTime = Instant.parse(ts);
        } catch (Exception e) {
            tradeTime = Instant.now();
        }

        // Update quote cache
        QuoteModel existing = quoteCache.get(symbol);
        double bid = existing != null ? existing.getBid() : price * 0.9995;
        double ask = existing != null ? existing.getAsk() : price * 1.0005;
        quoteCache.put(symbol, QuoteModel.withTimestamp(symbol, price, bid, ask,
                (existing != null ? existing.getVolume() : 0L) + size,
                tradeTime.toEpochMilli()));

        // Update candle history
        candleHistory.recordTick(symbol, price, size, tradeTime);
    }

    private void handleQuote(JSONObject msg) {
        String symbol = msg.optString("S", "");
        double bid    = msg.optDouble("bp", 0.0);
        double ask    = msg.optDouble("ap", 0.0);
        if (symbol.isEmpty()) return;

        // Blend quote bid/ask into the cached price (if we have a price already)
        QuoteModel existing = quoteCache.get(symbol);
        if (existing != null && bid > 0 && ask > 0) {
            double mid = (bid + ask) / 2.0;
            quoteCache.put(symbol, QuoteModel.withTimestamp(symbol, mid, bid, ask,
                    existing.getVolume(), System.currentTimeMillis()));
        }
    }

    // ── QuoteProvider implementation ──────────────────────────────────────────

    @Override
    public QuoteModel getQuote(String symbol) {
        return quoteCache.get(symbol);
    }

    @Override
    public List<QuoteModel> getQuotes(List<String> symbols) {
        List<QuoteModel> result = new ArrayList<>(symbols.size());
        for (String sym : symbols) {
            QuoteModel q = quoteCache.get(sym);
            if (q != null) result.add(q);
        }
        return result;
    }

    /** Not used for real-time trading — options chains still fetched via REST. */
    @Override
    public OptionsChain getOptionsChain(String symbol, LocalDate expiry) {
        return new OptionsChain(java.util.Map.of(), java.util.Map.of());
    }

    /** Falls back to Alpaca REST for historical bars (used for seeding). */
    @Override
    public List<HistoricalBar> getHistoricalBars(String symbol, LocalDate start, LocalDate end) {
        List<HistoricalBar> bars = new ArrayList<>();
        try {
            String url = config.getAlpacaDataUrl() + "/v2/stocks/" + symbol + "/bars"
                    + "?timeframe=1Day"
                    + "&start=" + start.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    + "&end="   + end.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    + "&limit=1000";
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID",    config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build();
            var resp = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JSONObject json = new JSONObject(resp.body());
                JSONArray barsArr = json.optJSONArray("bars");
                if (barsArr != null) {
                    for (int i = 0; i < barsArr.length(); i++) {
                        JSONObject b = barsArr.getJSONObject(i);
                        LocalDate date = LocalDate.parse(b.getString("t").substring(0, 10));
                        bars.add(new HistoricalBar(symbol, date,
                                b.optDouble("o", 0.0), b.optDouble("h", 0.0),
                                b.optDouble("l", 0.0), b.optDouble("c", 0.0),
                                b.optLong("v", 0L)));
                    }
                }
            }
        } catch (Exception e) {
            log("AlpacaWS bars fallback failed for " + symbol + ": " + e.getMessage());
        }
        return bars;
    }

    @Override
    public String getName() { return "AlpacaWebSocket_Free"; }

    @Override
    public LocalDate getEarliestBacktestDate() { return LocalDate.of(2016, 1, 4); }

    public boolean isConnected() { return authed.get(); }

    private void log(String msg) { logCallback.accept(msg); }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            // Save reference before request(1) so ws is non-null when the server's
            // "connected" message arrives and sendAuth() is called from onText().
            ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                handleMessage(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log("AlpacaWS closed (" + statusCode + " " + reason + ") — reconnecting");
            authed.set(false);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log("AlpacaWS error: " + error.getMessage() + " — reconnecting");
            authed.set(false);
            scheduleReconnect();
        }
    }
}
