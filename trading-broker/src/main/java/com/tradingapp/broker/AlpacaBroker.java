package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.engine.BrokerClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class AlpacaBroker implements BrokerClient {

    private static final Logger LOG = Logger.getLogger(AlpacaBroker.class.getName());

    private final AppConfig config;
    private final Account account;
    private final TransactionLog log;
    private final HttpClient http;

    public AlpacaBroker(AppConfig config, Account account, TransactionLog log) {
        this.config = config;
        this.account = account;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public TransactionRecord submitBuy(String symbol, int shares, double price, String signals, String reason, String features) {
        if (account.isTradingHalted() || account.getBalance() < shares * price) return null;
        try {
            JSONObject body = new JSONObject()
                    .put("symbol", symbol)
                    .put("qty", shares)
                    .put("side", "buy")
                    .put("type", "market")
                    .put("time_in_force", "day");

            HttpResponse<String> resp = post("/orders", body.toString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                System.err.println("Alpaca buy rejected (" + resp.statusCode() + "): " + resp.body());
                return null;
            }

            JSONObject order = new JSONObject(resp.body());
            String orderId = order.optString("id");
            double fillPrice = order.optDouble("filled_avg_price", 0.0);
            if (fillPrice <= 0.0) fillPrice = price;

            account.addOrUpdatePosition(symbol, shares, fillPrice, Position.PositionType.STOCK);
            account.setBalance(account.getBalance() - shares * fillPrice);
            if (account.getBalance() <= 100.0) account.setTradingHalted(true);

            TransactionRecord r = new TransactionRecord(symbol, TransactionRecord.TransactionAction.BUY,
                    shares, fillPrice, 0.0, account.getBalance(), reason, signals);
            r.setFeatures(features);
            r.setExternalId(orderId);
            tryInsertLog(r);
            return r;
        } catch (Exception e) {
            System.err.println("Alpaca buy failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public TransactionRecord submitSell(String symbol, int shares, double price, String signals, String reason) {
        try {
            JSONObject body = new JSONObject()
                    .put("symbol", symbol)
                    .put("qty", shares)
                    .put("side", "sell")
                    .put("type", "market")
                    .put("time_in_force", "day");

            HttpResponse<String> resp = post("/orders", body.toString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                System.err.println("Alpaca sell rejected (" + resp.statusCode() + "): " + resp.body());
                return null;
            }

            JSONObject order = new JSONObject(resp.body());
            String orderId = order.optString("id");
            double fillPrice = order.optDouble("filled_avg_price", 0.0);
            if (fillPrice <= 0.0) fillPrice = price;

            account.removePosition(symbol);
            account.setBalance(account.getBalance() + shares * fillPrice);

            TransactionRecord r = new TransactionRecord(symbol, TransactionRecord.TransactionAction.SELL,
                    shares, fillPrice, 0.0, account.getBalance(), reason, signals);
            r.setExternalId(orderId);
            tryInsertLog(r);
            return r;
        } catch (Exception e) {
            System.err.println("Alpaca sell failed: " + e.getMessage());
            return null;
        }
    }

    /** Submits an options order to Alpaca using OCC symbol format. Used as an OptionsSubmitter lambda. */
    public String submitOptionsOrder(String symbol, String optionType, double strike, LocalDate expiry, int contracts, String side) {
        try {
            String occSymbol = buildOccSymbol(symbol, optionType, strike, expiry);
            JSONObject body = new JSONObject()
                    .put("symbol", occSymbol)
                    .put("qty", contracts)
                    .put("side", side)
                    .put("type", "market")
                    .put("time_in_force", "day");

            HttpResponse<String> resp = post("/orders", body.toString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                System.err.println("Alpaca options order rejected (" + resp.statusCode() + "): " + resp.body());
                return null;
            }

            JSONObject order = new JSONObject(resp.body());
            return order.optString("id");
        } catch (Exception e) {
            System.err.println("Alpaca options order failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void syncAccount(Account account) {
        try {
            JSONObject alpacaAccount = getJson("/account");
            if (alpacaAccount != null) {
                double cash = alpacaAccount.optDouble("cash", account.getBalance());
                account.setBalance(cash);
                if (cash <= 100.0) account.setTradingHalted(true);
            }

            JSONArray positions = getJsonArray("/positions");
            if (positions != null) {
                account.getPositions().keySet().stream().toList().forEach(account::removePosition);
                for (int i = 0; i < positions.length(); i++) {
                    JSONObject pos = positions.getJSONObject(i);
                    String symbol = pos.getString("symbol");
                    int qty = (int) pos.optDouble("qty", 0);
                    double avgCost = pos.optDouble("avg_entry_price", 0.0);
                    double currentPrice = pos.optDouble("current_price", avgCost);
                    if (qty > 0) {
                        account.addOrUpdatePosition(symbol, qty, avgCost, Position.PositionType.STOCK);
                        account.updatePositionPrice(symbol, currentPrice);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Alpaca account sync failed: " + e.getMessage());
        }

        if (log != null) syncOrderHistory();
    }

    /**
     * Imports any filled Alpaca stock orders that are missing from the local transaction log,
     * keyed by the Alpaca order ID stored in external_id.
     */
    private void syncOrderHistory() {
        JSONArray orders = getJsonArray("/orders?status=filled&limit=50&direction=desc");
        if (orders == null) return;

        for (int i = 0; i < orders.length(); i++) {
            JSONObject o = orders.getJSONObject(i);
            String orderId = o.optString("id");
            String symbol = o.optString("symbol");

            // Skip options (OCC symbols are longer and contain a date+type pattern)
            if (isOccSymbol(symbol)) continue;

            if (log.existsByExternalId(orderId)) continue;

            String side = o.optString("side");
            int qty = (int) o.optDouble("filled_qty", 0);
            double fillPrice = o.optDouble("filled_avg_price", 0.0);
            if (qty <= 0 || fillPrice <= 0) continue;

            long ts = parseAlpacaTimestamp(o.optString("filled_at", o.optString("submitted_at")));
            TransactionRecord.TransactionAction action = "buy".equals(side)
                    ? TransactionRecord.TransactionAction.BUY
                    : TransactionRecord.TransactionAction.SELL;

            TransactionRecord r = new TransactionRecord();
            r.setTimestamp(ts);
            r.setSymbol(symbol);
            r.setAction(action);
            r.setQuantity(qty);
            r.setPricePerUnit(fillPrice);
            r.setFeeCharged(0.0);
            r.setBalanceAfter(account.getBalance());
            r.setReason("Imported from Alpaca history");
            r.setSignals("");
            r.setExternalId(orderId);
            tryInsertLog(r);
        }
    }

    public JSONObject testConnection() {
        return getJson("/account");
    }

    @Override
    public String getName() {
        return config.getBrokerType() == AppConfig.BrokerType.ALPACA_LIVE ? "Alpaca Live" : "Alpaca Paper";
    }

    private void tryInsertLog(TransactionRecord r) {
        if (log == null) return;
        try {
            log.insert(r);
        } catch (Exception e) {
            LOG.warning("Order placed but failed to write to local log: " + e.getMessage());
        }
    }

    private static String buildOccSymbol(String underlying, String optionType, double strike, LocalDate expiry) {
        String date = String.format("%02d%02d%02d",
                expiry.getYear() % 100, expiry.getMonthValue(), expiry.getDayOfMonth());
        String typeChar = optionType.equalsIgnoreCase("CALL") ? "C" : "P";
        long strikeInt = Math.round(strike * 1000);
        return String.format("%s%s%s%08d", underlying, date, typeChar, strikeInt);
    }

    private static boolean isOccSymbol(String symbol) {
        // OCC symbols are e.g. AAPL260619C00200000 — underlying + 6 digits + C/P + 8 digits
        return symbol != null && symbol.length() > 10 && symbol.matches(".*\\d{6}[CP]\\d{8}");
    }

    private static long parseAlpacaTimestamp(String iso) {
        if (iso == null || iso.isBlank()) return System.currentTimeMillis();
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getAlpacaBaseUrl() + path))
                .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(15))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JSONObject getJson(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getAlpacaBaseUrl() + path))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new JSONObject(resp.body());
            System.err.println("Alpaca GET " + path + " returned " + resp.statusCode());
        } catch (Exception e) {
            System.err.println("Alpaca GET " + path + " failed: " + e.getMessage());
        }
        return null;
    }

    private JSONArray getJsonArray(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getAlpacaBaseUrl() + path))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new JSONArray(resp.body());
            System.err.println("Alpaca GET " + path + " returned " + resp.statusCode());
        } catch (Exception e) {
            System.err.println("Alpaca GET " + path + " failed: " + e.getMessage());
        }
        return null;
    }
}
