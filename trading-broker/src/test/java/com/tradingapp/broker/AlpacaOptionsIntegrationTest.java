package com.tradingapp.broker;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test — submits a real options order to Alpaca paper trading.
 * Skipped automatically when no Alpaca credentials are configured.
 *
 * Run with: mvn test -pl trading-broker -Dtest=AlpacaOptionsIntegrationTest
 */
class AlpacaOptionsIntegrationTest {

    @Test
    void buyOneAaplCallAndVerifyInHistory() throws Exception {
        AppConfig config = AppConfig.load();
        Assumptions.assumeTrue(config.isAlpacaBroker(), "Skipping: broker is not Alpaca");
        Assumptions.assumeFalse(config.getAlpacaApiKey().isBlank(), "Skipping: no API key configured");

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // Query Alpaca options chain to find a real available AAPL call contract
        String chainUrl = config.getAlpacaBaseUrl()
                + "/options/contracts?underlying_symbols=AAPL&type=call&status=active&limit=10";
        HttpRequest chainReq = HttpRequest.newBuilder()
                .uri(URI.create(chainUrl))
                .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> chainResp = http.send(chainReq, HttpResponse.BodyHandlers.ofString());

        System.out.println("Options chain response (" + chainResp.statusCode() + "): "
                + chainResp.body().substring(0, Math.min(300, chainResp.body().length())));

        Assumptions.assumeTrue(chainResp.statusCode() == 200,
                "Options chain endpoint returned " + chainResp.statusCode()
                + " — options may not be enabled on this Alpaca account. Body: " + chainResp.body());

        JSONObject chainBody = new JSONObject(chainResp.body());
        JSONArray contracts = chainBody.optJSONArray("option_contracts");
        Assumptions.assumeTrue(contracts != null && contracts.length() > 0,
                "No AAPL call contracts returned — options may require account enablement.");

        JSONObject contract = contracts.getJSONObject(0);
        String occSymbol = contract.getString("symbol");
        double strike = contract.optDouble("strike_price", 0.0);
        String expiryStr = contract.optString("expiration_date");
        LocalDate expiry = LocalDate.parse(expiryStr);

        System.out.println("Using contract: " + occSymbol + " K=" + strike + " exp=" + expiry);

        AlpacaBroker broker = new AlpacaBroker(config, null, null);
        String orderId = broker.submitOptionsOrder("AAPL", "CALL", strike, expiry, 1, "buy");

        assertNotNull(orderId,
                "Options order was rejected by Alpaca. Check stderr — possible causes: "
                + "options not enabled on account, market closed, or invalid strike/expiry.");
        System.out.println("Order accepted. Alpaca order ID: " + orderId);

        // Poll for up to 10 seconds to confirm the order appears
        String ordersUrl = config.getAlpacaBaseUrl() + "/orders?status=all&limit=10";
        boolean found = false;
        for (int i = 0; i < 5; i++) {
            Thread.sleep(2000);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ordersUrl))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET().timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "Unexpected status from /orders: " + resp.body());

            JSONArray orders = new JSONArray(resp.body());
            System.out.println("Alpaca reports " + orders.length() + " recent order(s):");
            for (int j = 0; j < orders.length(); j++) {
                JSONObject o = orders.getJSONObject(j);
                System.out.println("  id=" + o.optString("id").substring(0, 8) + "..."
                        + " symbol=" + o.optString("symbol")
                        + " side=" + o.optString("side")
                        + " status=" + o.optString("status"));
                if (orderId.equals(o.optString("id"))) found = true;
            }
            if (found) break;
        }

        assertTrue(found, "Options order id=" + orderId + " was not found in Alpaca order history.");
    }

    private static LocalDate nextMonthlyExpiry() {
        // Third Friday of next month
        LocalDate today = LocalDate.now();
        LocalDate firstOfNextMonth = today.withDayOfMonth(1).plusMonths(1);
        LocalDate firstFriday = firstOfNextMonth;
        while (firstFriday.getDayOfWeek().getValue() != 5) firstFriday = firstFriday.plusDays(1);
        return firstFriday.plusWeeks(2); // third Friday
    }
}
