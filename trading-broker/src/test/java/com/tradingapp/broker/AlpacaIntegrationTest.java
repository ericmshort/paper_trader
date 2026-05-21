package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test — hits the real Alpaca paper trading API.
 * Skipped automatically when no credentials are configured.
 *
 * Run with: mvn test -pl trading-broker -Dtest=AlpacaIntegrationTest
 */
class AlpacaIntegrationTest {

    @Test
    void buyOneShareOfAAPLAndVerifyInHistory() throws Exception {
        AppConfig config = AppConfig.load();
        Assumptions.assumeTrue(config.isAlpacaBroker(),
                "Skipping: broker is not configured as Alpaca");
        Assumptions.assumeFalse(config.getAlpacaApiKey().isBlank(),
                "Skipping: no Alpaca API key configured");

        Account account = new Account();
        java.nio.file.Path tmpDb = java.nio.file.Files.createTempFile("alpaca-test-", ".db");
        tmpDb.toFile().deleteOnExit();
        TransactionLog log = new TransactionLog(tmpDb.toString());
        AlpacaBroker broker = new AlpacaBroker(config, account, log);

        // Sync real account balance from Alpaca before trading
        broker.syncAccount(account);
        System.out.println("Account balance after sync: $" + String.format("%.2f", account.getBalance()));
        assertTrue(account.getBalance() > 0, "Alpaca account balance should be > 0 after sync");

        double estimatedPrice = 200.0; // conservative estimate; Alpaca uses market price
        TransactionRecord record = broker.submitBuy("AAPL", 1, estimatedPrice, "integration-test", "AlpacaIntegrationTest", "");

        assertNotNull(record, "submitBuy returned null — order was rejected. Check stderr for the Alpaca error response.");
        System.out.println("Order submitted. Fill price: $" + String.format("%.2f", record.getPricePerUnit()));

        // Poll Alpaca orders endpoint for up to 10 seconds to confirm the order appears
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String ordersUrl = config.getAlpacaBaseUrl() + "/orders?status=all&limit=5";
        boolean found = false;
        for (int i = 0; i < 5; i++) {
            Thread.sleep(2000);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ordersUrl))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "Alpaca /orders returned HTTP " + resp.statusCode() + ": " + resp.body());

            JSONArray orders = new JSONArray(resp.body());
            System.out.println("Alpaca reports " + orders.length() + " recent order(s):");
            for (int j = 0; j < orders.length(); j++) {
                JSONObject o = orders.getJSONObject(j);
                System.out.println("  " + o.optString("symbol") + " qty=" + o.optString("qty")
                        + " side=" + o.optString("side") + " status=" + o.optString("status")
                        + " submitted=" + o.optString("submitted_at"));
                if ("AAPL".equals(o.optString("symbol")) && "buy".equals(o.optString("side"))) {
                    found = true;
                }
            }
            if (found) break;
        }

        assertTrue(found, "AAPL buy order was NOT found in Alpaca order history after 10 seconds. "
                + "Orders are being recorded locally but not reaching Alpaca.");
    }
}
