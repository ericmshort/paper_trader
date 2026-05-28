package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.Position;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.engine.BrokerClient;
import com.tradingapp.options.MultiLegOrder;
import com.tradingapp.options.OptionsSubmitter;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class AlpacaBroker implements BrokerClient, OptionsSubmitter {

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

    /** OptionsSubmitter.submit — delegates to the single-leg options order path. */
    @Override
    public String submit(String symbol, String optionType, double strike, LocalDate expiry, int contracts, String side) {
        return submitOptionsOrder(symbol, optionType, strike, expiry, contracts, side);
    }

    /**
     * OptionsSubmitter.submitMultiLeg — submits an all-or-nothing multi-leg order via Alpaca's
     * {@code order_class: "mleg"} endpoint. All legs are resolved to OCC symbols before submission;
     * if any lookup fails the whole order is aborted and null is returned.
     */
    @Override
    public String submitMultiLeg(List<MultiLegOrder> legs, int contracts) {
        try {
            JSONArray legsArray = new JSONArray();
            for (MultiLegOrder leg : legs) {
                String occSymbol = lookupBestContract(leg.symbol(), leg.optionType(), leg.strike(), leg.expiry());
                if (occSymbol == null) {
                    LOG.warning("Multi-leg: no contract found for " + leg.symbol()
                            + " " + leg.optionType() + " K=" + leg.strike() + " exp=" + leg.expiry());
                    return null;
                }
                legsArray.put(new JSONObject()
                        .put("symbol", occSymbol)
                        .put("side", leg.side())
                        .put("ratio_qty", 1)
                        .put("position_intent", leg.positionIntent()));
            }

            JSONObject body = new JSONObject()
                    .put("order_class", "mleg")
                    .put("type", "market")
                    .put("time_in_force", "day")
                    .put("qty", String.valueOf(contracts))
                    .put("legs", legsArray);

            HttpResponse<String> resp = post("/orders", body.toString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                System.err.println("Alpaca mleg order rejected (" + resp.statusCode() + "): " + resp.body());
                return null;
            }

            JSONObject order = new JSONObject(resp.body());
            String orderId = order.optString("id");
            LOG.info("Alpaca mleg order accepted: " + orderId + " (" + legs.size() + " legs x" + contracts + ")");
            return orderId;
        } catch (Exception e) {
            System.err.println("Alpaca mleg order failed: " + e.getMessage());
            return null;
        }
    }

    /** Submits a single-leg options order to Alpaca using OCC symbol format. */
    public String submitOptionsOrder(String symbol, String optionType, double strike, LocalDate expiry, int contracts, String side) {
        try {
            String occSymbol = lookupBestContract(symbol, optionType, strike, expiry);
            if (occSymbol == null) {
                System.err.println("Alpaca options: no listed contract found near " + symbol
                        + " " + optionType + " K=" + strike + " exp=" + expiry);
                return null;
            }
            LOG.info("Alpaca options: resolved contract " + occSymbol + " (target K=" + strike + " exp=" + expiry + ")");

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

    /**
     * Queries Alpaca's listed options contracts and returns the OCC symbol of the contract
     * whose strike is closest to {@code targetStrike} and whose expiry is within 14 days of
     * {@code targetExpiry}. Returns null if no contract is found.
     */
    private String lookupBestContract(String symbol, String optionType, double targetStrike, LocalDate targetExpiry) {
        try {
            String type = optionType.equalsIgnoreCase("CALL") ? "call" : "put";
            LocalDate expiryFrom = targetExpiry.minusDays(14);
            LocalDate expiryTo   = targetExpiry.plusDays(14);
            double strikeFrom = targetStrike * 0.85;
            double strikeTo   = targetStrike * 1.15;

            String path = "/options/contracts"
                    + "?underlying_symbols=" + symbol
                    + "&type=" + type
                    + "&status=active"
                    + "&expiration_date_gte=" + expiryFrom
                    + "&expiration_date_lte=" + expiryTo
                    + "&strike_price_gte=" + String.format("%.2f", strikeFrom)
                    + "&strike_price_lte=" + String.format("%.2f", strikeTo)
                    + "&limit=50";

            JSONObject resp = getJson(path);
            JSONArray contracts = resp != null ? resp.optJSONArray("option_contracts") : null;
            if (contracts == null || contracts.length() == 0) {
                // Widen to full strike range in case price has moved significantly
                path = "/options/contracts?underlying_symbols=" + symbol
                        + "&type=" + type + "&status=active"
                        + "&expiration_date_gte=" + expiryFrom
                        + "&expiration_date_lte=" + expiryTo
                        + "&limit=50";
                resp = getJson(path);
                contracts = resp != null ? resp.optJSONArray("option_contracts") : null;
            }
            if (contracts == null || contracts.length() == 0) return null;

            // Pick the contract with the closest strike to targetStrike
            String bestSymbol = null;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < contracts.length(); i++) {
                JSONObject c = contracts.getJSONObject(i);
                double k = c.optDouble("strike_price", Double.MAX_VALUE);
                double dist = Math.abs(k - targetStrike);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestSymbol = c.optString("symbol");
                }
            }
            return bestSymbol;
        } catch (Exception e) {
            LOG.warning("lookupBestContract failed: " + e.getMessage());
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
                double bp = alpacaAccount.optDouble("buying_power", cash);
                account.setBuyingPower(bp);
                double lastEquity = alpacaAccount.optDouble("last_equity", 0.0);
                if (lastEquity > 0) account.setLastEquity(lastEquity);
            }

            JSONArray positions = getJsonArray("/positions");
            if (positions != null) {
                // Build fingerprint → (key, existing position) map before clearing.
                // This preserves strategy-specific keys (e.g. NKE_STRADDLE_CALL,
                // BRZE_BULLPUTSPREAD_SHORT) across sync ticks. Without this, every sync
                // collapses multi-leg positions into SYMBOL_CALL / SYMBOL_PUT, causing
                // closeDirectionalLeg to fire on straddle and credit-spread legs.
                Map<String, String> keyByFingerprint = new HashMap<>();
                Map<String, com.tradingapp.account.OptionsPosition> posByFingerprint = new HashMap<>();
                for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e : account.getOptionsPositions().entrySet()) {
                    com.tradingapp.account.OptionsPosition p = e.getValue();
                    String fp = p.getSymbol() + "|" + p.getType() + "|" + p.getStrike() + "|" + p.getExpiry();
                    keyByFingerprint.put(fp, e.getKey());
                    posByFingerprint.put(fp, p);
                }

                account.getPositions().keySet().stream().toList().forEach(account::removePosition);
                account.getOptionsPositions().keySet().stream().toList().forEach(account::removeOptionsPosition);
                for (int i = 0; i < positions.length(); i++) {
                    JSONObject pos = positions.getJSONObject(i);
                    String symbol = pos.getString("symbol");
                    int qty = (int) pos.optDouble("qty", 0);
                    double avgCost = pos.optDouble("avg_entry_price", 0.0);
                    double currentPrice = pos.optDouble("current_price", avgCost);
                    if (qty <= 0) continue;
                    if (isOccSymbol(symbol)) {
                        OccComponents occ = parseOcc(symbol);
                        if (occ != null) {
                            String fp = occ.underlying + "|" + occ.type + "|" + occ.strike + "|" + occ.expiry;
                            String posKey = keyByFingerprint.getOrDefault(fp, occ.underlying + "_" + occ.type);
                            // Reuse the existing position object to preserve negative contracts on short
                            // legs and the original premiumPaid. Fall back to a new object only for
                            // positions that appeared in Alpaca but weren't locally tracked.
                            com.tradingapp.account.OptionsPosition existing = posByFingerprint.get(fp);
                            com.tradingapp.account.OptionsPosition optPos = (existing != null) ? existing :
                                    new com.tradingapp.account.OptionsPosition(
                                            occ.underlying, occ.type, occ.strike, occ.expiry, qty, avgCost);
                            account.addOptionsPosition(posKey, optPos);
                        }
                    } else {
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
     * Fetches all Alpaca order IDs and removes any local transaction records
     * that have no matching broker order. Should be called once at broker setup,
     * not on every sync tick.
     */
    public void reconcileTransactionLog() {
        if (log == null) return;
        Set<String> knownIds = fetchAllOrderIds();
        log.purgeUnmatched(knownIds);
        LOG.info("Reconciled transaction log — " + knownIds.size() + " known Alpaca order IDs retained.");
    }

    private Set<String> fetchAllOrderIds() {
        Set<String> ids = new HashSet<>();
        String after = null;
        for (int page = 0; page < 20; page++) { // cap at 10 000 orders (500 × 20)
            String path = "/orders?status=all&limit=500&direction=desc"
                    + (after != null ? "&after=" + after : "");
            JSONArray batch = getJsonArray(path);
            if (batch == null || batch.length() == 0) break;
            for (int i = 0; i < batch.length(); i++) {
                ids.add(batch.getJSONObject(i).optString("id"));
            }
            if (batch.length() < 500) break;
            // Use the last order's submitted_at for the next page cursor
            after = batch.getJSONObject(batch.length() - 1).optString("submitted_at");
        }
        return ids;
    }

    /**
     * Imports any filled Alpaca stock orders that are missing from the local transaction log,
     * keyed by the Alpaca order ID stored in external_id.
     */
    private void syncOrderHistory() {
        String after = null;
        for (int page = 0; page < 20; page++) {
            String path = "/orders?status=filled&limit=500&direction=desc"
                    + (after != null ? "&after=" + after : "");
            JSONArray orders = getJsonArray(path);
            if (orders == null || orders.length() == 0) break;

            for (int i = 0; i < orders.length(); i++) {
                JSONObject o = orders.getJSONObject(i);
                String orderId = o.optString("id");
                String symbol = o.optString("symbol");
                String side = o.optString("side");
                int qty = (int) o.optDouble("filled_qty", 0);
                double fillPrice = o.optDouble("filled_avg_price", 0.0);
                if (qty <= 0 || fillPrice <= 0) continue;

                if (log.existsByExternalId(orderId)) {
                    // Correct any records logged at theoretical (BS) price instead of actual fill
                    log.updateFillPrice(orderId, fillPrice);
                    continue;
                }

                long ts = parseAlpacaTimestamp(o.optString("filled_at", o.optString("submitted_at")));

                TransactionRecord r = new TransactionRecord();
                r.setTimestamp(ts);
                r.setQuantity(qty);
                r.setPricePerUnit(fillPrice);
                r.setFeeCharged(0.0);
                r.setBalanceAfter(account != null ? account.getBalance() : 0.0);
                r.setSignals("");
                r.setExternalId(orderId);

                if (isOccSymbol(symbol)) {
                    OccComponents occ = parseOcc(symbol);
                    if (occ == null) continue;
                    r.setSymbol(occ.underlying);
                    r.setReason(occ.type + " K=" + occ.strike + " exp=" + occ.expiry + " (imported)");
                    if ("buy".equals(side)) {
                        r.setAction(occ.type.equals("CALL")
                                ? TransactionRecord.TransactionAction.CALL_BUY
                                : TransactionRecord.TransactionAction.PUT_BUY);
                    } else {
                        r.setAction(occ.type.equals("CALL")
                                ? TransactionRecord.TransactionAction.CALL_SELL
                                : TransactionRecord.TransactionAction.PUT_SELL);
                    }
                } else {
                    r.setSymbol(symbol);
                    r.setReason("Imported from Alpaca history");
                    r.setAction("buy".equals(side)
                            ? TransactionRecord.TransactionAction.BUY
                            : TransactionRecord.TransactionAction.SELL);
                }

                tryInsertLog(r);
            }

            if (orders.length() < 500) break;
            after = orders.getJSONObject(orders.length() - 1).optString("submitted_at");
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
        return symbol != null && symbol.matches("[A-Z.]+\\d{6}[CP]\\d{8}");
    }

    private record OccComponents(String underlying, String type, double strike, LocalDate expiry) {}

    private static OccComponents parseOcc(String symbol) {
        try {
            // Find where the 6-digit date begins (first run of digits)
            int dateStart = -1;
            for (int i = 0; i < symbol.length(); i++) {
                if (Character.isDigit(symbol.charAt(i))) { dateStart = i; break; }
            }
            if (dateStart < 0 || dateStart + 15 > symbol.length()) return null;
            String underlying = symbol.substring(0, dateStart);
            int yy = Integer.parseInt(symbol.substring(dateStart, dateStart + 2));
            int mm = Integer.parseInt(symbol.substring(dateStart + 2, dateStart + 4));
            int dd = Integer.parseInt(symbol.substring(dateStart + 4, dateStart + 6));
            char typeChar = symbol.charAt(dateStart + 6);
            long strikeRaw = Long.parseLong(symbol.substring(dateStart + 7));
            return new OccComponents(
                    underlying,
                    typeChar == 'C' ? "CALL" : "PUT",
                    strikeRaw / 1000.0,
                    LocalDate.of(2000 + yy, mm, dd));
        } catch (Exception e) {
            return null;
        }
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
