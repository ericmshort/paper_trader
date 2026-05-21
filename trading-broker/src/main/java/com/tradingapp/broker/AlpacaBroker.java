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

public class AlpacaBroker implements BrokerClient {

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
            double fillPrice = order.optDouble("filled_avg_price", 0.0);
            if (fillPrice <= 0.0) fillPrice = price;

            account.addOrUpdatePosition(symbol, shares, fillPrice, Position.PositionType.STOCK);
            account.setBalance(account.getBalance() - shares * fillPrice);
            if (account.getBalance() <= 100.0) account.setTradingHalted(true);

            TransactionRecord r = new TransactionRecord(symbol, TransactionRecord.TransactionAction.BUY,
                    shares, fillPrice, 0.0, account.getBalance(), reason, signals);
            r.setFeatures(features);
            log.insert(r);
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
            double fillPrice = order.optDouble("filled_avg_price", 0.0);
            if (fillPrice <= 0.0) fillPrice = price;

            account.removePosition(symbol);
            account.setBalance(account.getBalance() + shares * fillPrice);

            TransactionRecord r = new TransactionRecord(symbol, TransactionRecord.TransactionAction.SELL,
                    shares, fillPrice, 0.0, account.getBalance(), reason, signals);
            log.insert(r);
            return r;
        } catch (Exception e) {
            System.err.println("Alpaca sell failed: " + e.getMessage());
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
    }

    public JSONObject testConnection() {
        return getJson("/account");
    }

    @Override
    public String getName() {
        return config.getBrokerType() == AppConfig.BrokerType.ALPACA_LIVE ? "Alpaca Live" : "Alpaca Paper";
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
