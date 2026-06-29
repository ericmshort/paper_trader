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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class AlpacaBroker implements BrokerClient, OptionsSubmitter {

    private static final Logger LOG = Logger.getLogger(AlpacaBroker.class.getName());

    private final AppConfig config;
    private final Account account;
    private final TransactionLog log;
    private final HttpClient http;
    private Consumer<String> logCallback = msg -> {};

    public AlpacaBroker(AppConfig config, Account account, TransactionLog log) {
        this.config = config;
        this.account = account;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback != null ? callback : msg -> {};
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
        return submitOptionsOrder(symbol, optionType, strike, expiry, contracts, side, null);
    }

    @Override
    public String submit(String symbol, String optionType, double strike, LocalDate expiry,
                         int contracts, String side, String positionIntent) {
        return submitOptionsOrder(symbol, optionType, strike, expiry, contracts, side, positionIntent);
    }

    @Override
    public String submitDirect(String occSymbol, int contracts, String side, String positionIntent) {
        try {
            LOG.info("Alpaca options close (direct): " + occSymbol + " side=" + side + " intent=" + positionIntent);
            // Use DELETE /positions/{symbol} rather than POST /orders with position_intent.
            // Alpaca rejects sell_to_close/buy_to_close via the orders endpoint with 403
            // ("account not eligible to trade uncovered option contracts") when it can't
            // confirm the position on its side, treating the sell as a naked write.
            // The DELETE endpoint closes an existing position directly and bypasses that check.
            HttpResponse<String> resp = deletePosition(occSymbol);
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                JSONObject order = new JSONObject(resp.body());
                return order.optString("id");
            }

            // Alpaca requires a limit order when there is no available market quote.
            // Fall back to a limit order priced at the current bid (sell) or ask (buy).
            String body403 = resp.body();
            if (resp.statusCode() == 403 && body403.contains("no available quote")) {
                double limitPrice = fetchLimitPriceForClose(occSymbol, side);
                if (limitPrice <= 0) {
                    System.err.println("Alpaca options direct close rejected (" + resp.statusCode() + "): " + body403
                            + " — could not determine limit price, giving up");
                    return null;
                }
                LOG.info("Alpaca options close falling back to limit order: " + occSymbol
                        + " side=" + side + " limit=" + limitPrice);
                JSONObject limitBody = new JSONObject()
                        .put("symbol", occSymbol)
                        .put("qty", contracts)
                        .put("side", side)
                        .put("type", "limit")
                        .put("time_in_force", "day")
                        .put("limit_price", String.format("%.2f", limitPrice));
                if (positionIntent != null) {
                    limitBody.put("position_intent", positionIntent);
                }
                HttpResponse<String> limitResp = post("/orders", limitBody.toString());
                if (limitResp.statusCode() != 200 && limitResp.statusCode() != 201) {
                    System.err.println("Alpaca options limit close rejected (" + limitResp.statusCode() + "): " + limitResp.body());
                    return null;
                }
                JSONObject order = new JSONObject(limitResp.body());
                return order.optString("id");
            }

            System.err.println("Alpaca options direct close rejected (" + resp.statusCode() + "): " + body403);
            return null;
        } catch (Exception e) {
            System.err.println("Alpaca options direct close failed: " + e.getMessage());
            return null;
        }
    }

    /** Fetches the mid price (bid+ask)/2 for an OCC symbol; returns 0 if unavailable. */
    private double fetchMidPrice(String occSymbol) {
        try {
            String url = config.getAlpacaDataUrl()
                    + "/v1beta1/options/snapshots/" + java.net.URLEncoder.encode(occSymbol, java.nio.charset.StandardCharsets.UTF_8)
                    + "?feed=indicative";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return 0;
            JSONObject body = new JSONObject(resp.body());
            JSONObject snapshots = body.optJSONObject("snapshots");
            if (snapshots == null || !snapshots.has(occSymbol)) return 0;
            JSONObject quote = snapshots.getJSONObject(occSymbol).optJSONObject("latestQuote");
            if (quote == null) return 0;
            double bid = quote.optDouble("bp", 0.0);
            double ask = quote.optDouble("ap", 0.0);
            if (bid > 0 && ask > 0) return (bid + ask) / 2.0;
            return Math.max(bid, ask);
        } catch (Exception e) {
            LOG.warning("Could not fetch mid price for " + occSymbol + ": " + e.getMessage());
            return 0;
        }
    }

    /** Fetches the current bid (for sells) or ask (for buys) for an OCC symbol from the snapshot API. */
    private double fetchLimitPriceForClose(String occSymbol, String side) {
        try {
            String url = config.getAlpacaDataUrl()
                    + "/v1beta1/options/snapshots/" + java.net.URLEncoder.encode(occSymbol, java.nio.charset.StandardCharsets.UTF_8)
                    + "?feed=indicative";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                    .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.warning("Snapshot fetch failed for " + occSymbol + ": HTTP " + resp.statusCode());
                return 0;
            }
            JSONObject body = new JSONObject(resp.body());
            JSONObject snapshots = body.optJSONObject("snapshots");
            if (snapshots == null || !snapshots.has(occSymbol)) return 0;
            JSONObject quote = snapshots.getJSONObject(occSymbol).optJSONObject("latestQuote");
            if (quote == null) return 0;
            double bid = quote.optDouble("bp", 0.0);
            double ask = quote.optDouble("ap", 0.0);
            // Sell to close: use bid (aggressive — we want out). Buy to close: use ask.
            boolean isSell = side.startsWith("sell");
            double price = isSell ? bid : ask;
            // If the spread is completely absent, use the other side or last trade as fallback.
            if (price <= 0) {
                JSONObject trade = snapshots.getJSONObject(occSymbol).optJSONObject("latestTrade");
                price = trade != null ? trade.optDouble("p", 0.0) : 0.0;
            }
            return price;
        } catch (Exception e) {
            LOG.warning("Could not fetch snapshot for " + occSymbol + ": " + e.getMessage());
            return 0;
        }
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
                // Use pinned OCC symbol when available (close orders); look up for new opens.
                String occSymbol = leg.occSymbol();
                if (occSymbol == null) {
                    occSymbol = lookupBestContract(leg.symbol(), leg.optionType(), leg.strike(), leg.expiry());
                    if (occSymbol == null) {
                        LOG.warning("Multi-leg: no contract found for " + leg.symbol()
                                + " " + leg.optionType() + " K=" + leg.strike() + " exp=" + leg.expiry());
                        return null;
                    }
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
    public String submitOptionsOrder(String symbol, String optionType, double strike, LocalDate expiry,
                                     int contracts, String side, String positionIntent) {
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
                    .put("time_in_force", "day");
            // Use limit orders on entry buys to avoid overpaying on the ask.
            // Closes still use DELETE /positions which routes as market.
            if ("buy".equals(side)) {
                double mid = fetchMidPrice(occSymbol);
                if (mid > 0) {
                    body.put("type", "limit").put("limit_price", String.format("%.2f", mid));
                    LOG.info("Alpaca options buy limit: " + occSymbol + " mid=" + String.format("%.2f", mid));
                } else {
                    body.put("type", "market");
                    LOG.info("Alpaca options buy market (no mid quote): " + occSymbol);
                }
            } else {
                body.put("type", "market");
            }
            if (positionIntent != null) {
                body.put("position_intent", positionIntent);
            }

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
    String lookupBestContract(String symbol, String optionType, double targetStrike, LocalDate targetExpiry) {
        try {
            String type = optionType.equalsIgnoreCase("CALL") ? "call" : "put";

            // Query strategy: always start from the exact target expiry and widen outward.
            // SPY has $1-wide strikes, so a ±14-day window returns 14+ expirations × ~45
            // in-range strikes = 600+ contracts. With limit=50 the nearest expiry fills
            // the result set entirely and the target expiry never appears.
            // By fixing the expiry window first we ensure the 50-result page contains
            // the contracts we actually want.
            double tightFrom = targetStrike * 0.97;
            double tightTo   = targetStrike * 1.03;
            double wideFrom  = targetStrike * 0.85;
            double wideTo    = targetStrike * 1.15;

            // Pass 1: exact expiry ± 2 days, tight strike (covers almost all cases)
            JSONArray contracts = queryContracts(symbol, type,
                    targetExpiry.minusDays(2), targetExpiry.plusDays(2),
                    tightFrom, tightTo);

            // Pass 2: exact expiry ± 7 days, tight strike (nearby weekly alternative)
            if (empty(contracts))
                contracts = queryContracts(symbol, type,
                        targetExpiry.minusDays(7), targetExpiry.plusDays(7),
                        tightFrom, tightTo);

            // Pass 3: exact expiry ± 14 days, wide strike (price moved far from target)
            if (empty(contracts))
                contracts = queryContracts(symbol, type,
                        targetExpiry.minusDays(14), targetExpiry.plusDays(14),
                        wideFrom, wideTo);

            // Pass 4: exact expiry ± 14 days, no strike filter (last resort)
            if (empty(contracts))
                contracts = queryContracts(symbol, type,
                        targetExpiry.minusDays(14), targetExpiry.plusDays(14),
                        Double.NaN, Double.NaN);

            if (empty(contracts)) return null;

            // Pick the contract with the closest strike to targetStrike.
            // Break ties by expiry closest to targetExpiry.
            String bestSymbol = null;
            double bestStrike = Double.MAX_VALUE;
            double bestStrikeDist = Double.MAX_VALUE;
            long bestExpiryDist = Long.MAX_VALUE;
            for (int i = 0; i < contracts.length(); i++) {
                JSONObject c = contracts.getJSONObject(i);
                double k = c.optDouble("strike_price", Double.MAX_VALUE);
                double strikeDist = Math.abs(k - targetStrike);
                long expiryDist = Long.MAX_VALUE;
                try {
                    expiryDist = Math.abs(ChronoUnit.DAYS.between(
                            LocalDate.parse(c.optString("expiration_date")), targetExpiry));
                } catch (Exception ignored) {}
                if (strikeDist < bestStrikeDist
                        || (strikeDist == bestStrikeDist && expiryDist < bestExpiryDist)) {
                    bestStrikeDist = strikeDist;
                    bestExpiryDist = expiryDist;
                    bestStrike = k;
                    bestSymbol = c.optString("symbol");
                }
            }

            // Reject if the closest contract is still more than 5% away from target.
            // A mismatch this large means the local position's recorded strike diverges
            // from Alpaca's actual fill, causing broker-sync fingerprint mismatches that
            // remove the local position and trigger false daily-loss-limit halts.
            double maxAcceptableDist = targetStrike * 0.05;
            if (bestStrikeDist > maxAcceptableDist) {
                LOG.warning(String.format(
                        "lookupBestContract: best strike %.2f is %.2f away from target %.2f (>5%%) — rejecting [%s %s exp=%s]",
                        bestStrike, bestStrikeDist, targetStrike, symbol, optionType, targetExpiry));
                return null;
            }

            LOG.info(String.format(
                    "lookupBestContract: selected %s (K=%.2f, exp+%dd) for target K=%.2f exp=%s",
                    bestSymbol, bestStrike, bestExpiryDist, targetStrike, targetExpiry));
            return bestSymbol;
        } catch (Exception e) {
            LOG.warning("lookupBestContract failed: " + e.getMessage());
            return null;
        }
    }

    private JSONArray queryContracts(String symbol, String type,
                                     LocalDate expiryFrom, LocalDate expiryTo,
                                     double strikeFrom, double strikeTo) {
        try {
            String path = "/options/contracts"
                    + "?underlying_symbols=" + symbol
                    + "&type=" + type
                    + "&status=active"
                    + "&expiration_date_gte=" + expiryFrom
                    + "&expiration_date_lte=" + expiryTo;
            if (!Double.isNaN(strikeFrom)) {
                path += "&strike_price_gte=" + String.format("%.2f", strikeFrom)
                      + "&strike_price_lte=" + String.format("%.2f", strikeTo);
            }
            path += "&limit=50";
            JSONObject resp = getJson(path);
            return resp != null ? resp.optJSONArray("option_contracts") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean empty(JSONArray arr) {
        return arr == null || arr.length() == 0;
    }

    @Override
    public void syncAccount(Account account) {
        try {
            JSONObject alpacaAccount = getJson("/account");
            if (alpacaAccount != null) {
                double cash = alpacaAccount.optDouble("cash", account.getBalance());
                account.setBalance(cash);
                if (cash <= 100.0) account.setTradingHalted(true);
                // Use options_buying_power when available — it reflects the remaining
                // margin available for options orders, which is lower than buying_power
                // when the account has open short legs consuming margin.
                double bp = alpacaAccount.optDouble("options_buying_power",
                        alpacaAccount.optDouble("buying_power", cash));
                account.setBuyingPower(bp);
                double lastEquity = alpacaAccount.optDouble("last_equity", 0.0);
                if (lastEquity > 0) account.setLastEquity(lastEquity);
                double portfolioValue = alpacaAccount.optDouble("portfolio_value",
                        alpacaAccount.optDouble("equity", -1.0));
                if (portfolioValue > 0) account.setBrokerPortfolioValue(portfolioValue);
            }

            JSONArray positions = getJsonArray("/positions");
            if (positions != null) {
                // Mark every in-memory position as unverified. Only positions confirmed
                // by the broker will be re-verified; the rest are removed after the loop.
                account.markAllUnverified();

                // Build fingerprint → (key, existing position) map before any removals.
                // Preserves strategy-specific keys (e.g. NKE_STRADDLE_CALL,
                // BRZE_PUTSPREAD_SHORTPUT) across sync ticks. Without this, every sync
                // collapses multi-leg positions into SYMBOL_CALL / SYMBOL_PUT, causing
                // closeDirectionalLeg to fire on straddle and credit-spread legs.
                Map<String, String> keyByFingerprint = new HashMap<>();
                Map<String, com.tradingapp.account.OptionsPosition> posByFingerprint = new HashMap<>();
                for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e : account.getOptionsPositions().entrySet()) {
                    com.tradingapp.account.OptionsPosition p = e.getValue();
                    // Omit expiry from fingerprint: the local position stores the TARGET expiry
                    // from bsEngine.selectExpiry(), but the actual OCC contract filled by Alpaca
                    // can differ by ±14 days (lookupBestContract search window). Including expiry
                    // caused every condor/straddle leg to lose its strategy key on each sync tick,
                    // collapsing to generic SYMBOL_CALL / SYMBOL_PUT and firing closeDirectionalLeg.
                    String fp = p.getSymbol() + "|" + p.getType() + "|" + p.getStrike();
                    keyByFingerprint.put(fp, e.getKey());
                    posByFingerprint.put(fp, p);
                }

                // Collect new (broker-discovered) options that have no existing local match.
                // Processed after the loop so spread pairs can be detected before key assignment.
                List<Object[]> newBrokerOptions = new ArrayList<>();

                for (int i = 0; i < positions.length(); i++) {
                    JSONObject pos = positions.getJSONObject(i);
                    String symbol = pos.getString("symbol");
                    // Alpaca reports short options with negative qty; take abs so we don't skip them.
                    // Direction is preserved by reusing the existing OptionsPosition (negative contracts).
                    int qty = (int) Math.abs(pos.optDouble("qty", 0));
                    String side = pos.optString("side", "long");
                    double avgCost = pos.optDouble("avg_entry_price", 0.0);
                    double currentPrice = pos.optDouble("current_price", avgCost);
                    if (qty == 0) continue;
                    if (isOccSymbol(symbol)) {
                        OccComponents occ = parseOcc(symbol);
                        if (occ != null) {
                            String fp = occ.underlying + "|" + occ.type + "|" + occ.strike;
                            String existingKey = keyByFingerprint.get(fp);
                            // Reuse the existing position object to preserve negative contracts on short
                            // legs and the original premiumPaid. Fall back to a new object only for
                            // positions that appeared in Alpaca but weren't locally tracked.
                            com.tradingapp.account.OptionsPosition existing = posByFingerprint.get(fp);
                            if (existingKey != null && existing != null) {
                                // Matched: update broker OCC symbol, market price, and re-verify
                                existing.setBrokerOccSymbol(symbol);
                                existing.setCurrentMarketPrice(currentPrice);
                                account.addOptionsPosition(existingKey, existing);
                                account.markOptionVerified(existingKey);
                            } else {
                                // New position: collect for spread-pair detection below
                                int signedQty = "short".equals(side) ? -qty : qty;
                                com.tradingapp.account.OptionsPosition optPos =
                                        new com.tradingapp.account.OptionsPosition(
                                                occ.underlying, occ.type, occ.strike, occ.expiry, signedQty, avgCost);
                                // Pin the exact Alpaca OCC symbol so close orders bypass re-lookup.
                                optPos.setBrokerOccSymbol(symbol);
                                optPos.setCurrentMarketPrice(currentPrice);
                                newBrokerOptions.add(new Object[]{occ, optPos});
                            }
                        }
                    } else {
                        // Replace position entirely with broker data — don't accumulate.
                        account.setPositionFromBroker(symbol, qty, avgCost, Position.PositionType.STOCK);
                        account.updatePositionPrice(symbol, currentPrice);
                        account.markStockVerified(symbol);
                    }
                }

                // Assign keys to new broker-discovered options. Group by underlying+type+expiry
                // so that credit spread pairs (one short + one long) get strategy-specific keys
                // (BEARCALLSPREAD_SHORT/LONG, BULLPUTSPREAD_SHORT/LONG) instead of the generic
                // SYMBOL_CALL / SYMBOL_PUT key, which collapses both legs and causes
                // closeDirectionalLeg to attempt single-leg closes that Alpaca rejects as uncovered.
                Map<String, List<Object[]>> byTypeGroup = new LinkedHashMap<>();
                for (Object[] entry : newBrokerOptions) {
                    OccComponents occ = (OccComponents) entry[0];
                    String gk = occ.underlying + "|" + occ.type + "|" + occ.expiry;
                    byTypeGroup.computeIfAbsent(gk, k -> new ArrayList<>()).add(entry);
                }
                for (List<Object[]> group : byTypeGroup.values()) {
                    OccComponents firstOcc = (OccComponents) group.get(0)[0];
                    String underlying = firstOcc.underlying;
                    String type = firstOcc.type;

                    if (group.size() == 2) {
                        com.tradingapp.account.OptionsPosition p0 =
                                (com.tradingapp.account.OptionsPosition) group.get(0)[1];
                        com.tradingapp.account.OptionsPosition p1 =
                                (com.tradingapp.account.OptionsPosition) group.get(1)[1];
                        com.tradingapp.account.OptionsPosition shortLeg = null, longLeg = null;
                        if (p0.getContracts() < 0 && p1.getContracts() > 0) {
                            shortLeg = p0; longLeg = p1;
                        } else if (p0.getContracts() > 0 && p1.getContracts() < 0) {
                            shortLeg = p1; longLeg = p0;
                        }
                        if (shortLeg != null) {
                            // Credit spread: use PremiumSellerRouter's canonical key suffixes so
                            // buildPremiumRows() and checkExit*Spread() can locate both legs.
                            String shortKey = "CALL".equals(type)
                                    ? underlying + "_CALLSPREAD_SHORTCALL"
                                    : underlying + "_PUTSPREAD_SHORTPUT";
                            String longKey = "CALL".equals(type)
                                    ? underlying + "_CALLSPREAD_LONGCALL"
                                    : underlying + "_PUTSPREAD_LONGPUT";
                            // Don't overwrite a position that was already verified by fingerprint
                            // matching (same strike in Alpaca as local). Also don't overwrite a
                            // locally-tracked spread with positive premiumPaid: that indicates
                            // today's entry was recorded locally but Alpaca is returning a stale
                            // fill from a prior session at a different strike. The isOptionVerified
                            // guard alone doesn't cover this case because fingerprint match requires
                            // identical strikes — if Alpaca has K=1110 and local DB has K=1115, no
                            // fingerprint match occurs and the key is never marked verified.
                            com.tradingapp.account.OptionsPosition existingShort =
                                    account.getOptionsPositions().get(shortKey);
                            boolean hasTrackedSpread = existingShort != null
                                    && existingShort.getPremiumPaid() > 0;
                            if (!account.isOptionVerified(shortKey) && !hasTrackedSpread) {
                                account.addOptionsPosition(shortKey, shortLeg);
                            }
                            account.markOptionVerified(shortKey);
                            if (!account.isOptionVerified(longKey) && !hasTrackedSpread) {
                                account.addOptionsPosition(longKey, longLeg);
                            }
                            account.markOptionVerified(longKey);
                            LOG.info("[BrokerSync] Detected " + ("CALL".equals(type) ? "bear call" : "bull put")
                                    + " spread for " + underlying + ": short K=" + shortLeg.getStrike()
                                    + " long K=" + longLeg.getStrike());
                            continue;
                        }
                    }
                    // Standalone or non-credit-spread: use generic key with strike suffix for uniqueness
                    for (Object[] entry : group) {
                        com.tradingapp.account.OptionsPosition pos =
                                (com.tradingapp.account.OptionsPosition) entry[1];
                        OccComponents occ = (OccComponents) entry[0];
                        String defaultKey = underlying + "_" + type;
                        String key = account.getOptionsPositions().containsKey(defaultKey)
                                ? defaultKey + "_K" + (int) occ.strike
                                : defaultKey;
                        account.addOptionsPosition(key, pos);
                        account.markOptionVerified(key);
                    }
                }

                // Remove any positions that were in the DB but NOT confirmed by the broker.
                // For stocks, also insert a compensating SELL so they don't resurrect on restart.
                List<com.tradingapp.account.Position> staleStocks = account.getPositions().values().stream()
                        .filter(p -> !p.isBrokerVerified())
                        .collect(java.util.stream.Collectors.toList());
                // Skip options that were added locally within the last 90 seconds — Alpaca
                // fill confirmations can lag by 30-60 s at market open, and removing a
                // position that simply hasn't been confirmed yet causes the system to
                // re-enter it on the very next tick, creating duplicate positions.
                List<String> staleOptions = account.getOptionsPositions().keySet().stream()
                        .filter(k -> !account.isOptionVerified(k))
                        .filter(k -> !account.isOptionRecentlyAdded(k))
                        .collect(java.util.stream.Collectors.toList());

                if (!staleStocks.isEmpty() || !staleOptions.isEmpty()) {
                    LOG.warning("[BrokerSync] Positions in local DB not found in broker — removing: stocks="
                            + staleStocks.stream().map(com.tradingapp.account.Position::getSymbol)
                                         .collect(java.util.stream.Collectors.joining(", "))
                            + " options=" + staleOptions);
                }

                for (com.tradingapp.account.Position stale : staleStocks) {
                    account.removePosition(stale.getSymbol());
                    // Insert a compensating SELL so restoreAccount won't reconstruct this position
                    if (log != null) {
                        TransactionRecord r = new TransactionRecord();
                        r.setTimestamp(System.currentTimeMillis());
                        r.setSymbol(stale.getSymbol());
                        r.setAction(TransactionRecord.TransactionAction.SELL);
                        r.setQuantity(stale.getQuantity());
                        r.setPricePerUnit(stale.getAverageCost());
                        r.setFeeCharged(0.0);
                        r.setBalanceAfter(account.getBalance());
                        r.setReason("Broker sync close: position not found in brokerage account");
                        tryInsertLog(r);
                    }
                }
                staleOptions.forEach(account::removeOptionsPosition);

                account.setBrokerSyncComplete(true);
                String syncMsg = "[BrokerSync] Restored " + account.getOptionsPositions().size()
                        + " option position(s) and " + account.getPositions().size()
                        + " stock position(s) from Alpaca.";
                LOG.info(syncMsg);
                logCallback.accept(syncMsg);
            }
        } catch (Exception e) {
            String errMsg = "[BrokerSync] WARNING: sync failed (" + e.getMessage()
                    + ") — open position state may be incomplete, re-entries not blocked.";
            System.err.println(errMsg);
            logCallback.accept(errMsg);
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
                    // Skip fill-price correction for multi-leg orders: the composite
                    // filled_avg_price cannot be split correctly across individual leg records
                    // (all legs share the same external_id), and overwriting all to the same
                    // price corrupts per-leg P&L. Each leg's B-S price at submission is kept.
                    if (!"mleg".equals(o.optString("order_class", ""))) {
                        log.updateFillPrice(orderId, fillPrice);
                    }
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
    public String buyStock(String symbol, int shares) {
        try {
            JSONObject body = new JSONObject()
                    .put("symbol", symbol)
                    .put("qty", shares)
                    .put("side", "buy")
                    .put("type", "market")
                    .put("time_in_force", "day");
            HttpResponse<String> resp = post("/orders", body.toString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                return new JSONObject(resp.body()).optString("id");
            }
            System.err.println("Alpaca buyStock rejected (" + resp.statusCode() + "): " + resp.body());
            return null;
        } catch (Exception e) {
            System.err.println("Alpaca buyStock failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String sellStock(String symbol, int shares) {
        try {
            JSONObject body = new JSONObject()
                    .put("symbol", symbol)
                    .put("qty", shares)
                    .put("side", "sell")
                    .put("type", "market")
                    .put("time_in_force", "day");
            HttpResponse<String> resp = post("/orders", body.toString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                return new JSONObject(resp.body()).optString("id");
            }
            System.err.println("Alpaca sellStock rejected (" + resp.statusCode() + "): " + resp.body());
            return null;
        } catch (Exception e) {
            System.err.println("Alpaca sellStock failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public String getName() {
        return config.getBrokerType() == AppConfig.BrokerType.ALPACA_LIVE ? "Alpaca Live" : "Alpaca Paper";
    }

    @Override
    public int closeAllOptionsPositions() {
        try {
            JSONArray positions = getJsonArray("/positions");
            if (positions == null) return 0;
            int found = 0;
            for (int i = 0; i < positions.length(); i++) {
                String symbol = positions.getJSONObject(i).optString("symbol");
                if (!isOccSymbol(symbol)) continue;
                found++;
                LOG.info("Force-close: " + symbol);
                try {
                    HttpResponse<String> resp = deletePosition(symbol);
                    if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                        LOG.warning("Force-close failed for " + symbol + " (" + resp.statusCode() + "): " + resp.body());
                    }
                } catch (Exception e) {
                    LOG.warning("Force-close error for " + symbol + ": " + e.getMessage());
                }
            }
            return found;
        } catch (Exception e) {
            LOG.warning("Force-close-all failed: " + e.getMessage());
            return -1;
        }
    }

    @Override
    public int closeNonPremiumOptionsPositions(java.util.Set<String> skipOccSymbols) {
        try {
            JSONArray positions = getJsonArray("/positions");
            if (positions == null) return 0;
            int found = 0;
            for (int i = 0; i < positions.length(); i++) {
                String symbol = positions.getJSONObject(i).optString("symbol");
                if (!isOccSymbol(symbol)) continue;
                if (skipOccSymbols.contains(symbol)) continue;
                found++;
                LOG.info("Force-close (non-premium): " + symbol);
                try {
                    HttpResponse<String> resp = deletePosition(symbol);
                    if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                        LOG.warning("Force-close failed for " + symbol + " (" + resp.statusCode() + "): " + resp.body());
                    }
                } catch (Exception e) {
                    LOG.warning("Force-close error for " + symbol + ": " + e.getMessage());
                }
            }
            return found;
        } catch (Exception e) {
            LOG.warning("Force-close non-premium failed: " + e.getMessage());
            return -1;
        }
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

    private HttpResponse<String> deletePosition(String symbol) throws Exception {
        String encoded = java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getAlpacaBaseUrl() + "/positions/" + encoded))
                .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                .DELETE()
                .timeout(Duration.ofSeconds(15))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
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
