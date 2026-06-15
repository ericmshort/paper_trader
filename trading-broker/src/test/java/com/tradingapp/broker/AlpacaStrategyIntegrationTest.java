package com.tradingapp.broker;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.engine.SignalResult;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;
import com.tradingapp.data.PriceHistory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that hit the real Alpaca paper trading API.
 *
 * Excluded from normal mvn test runs (see trading-broker/pom.xml surefire excludes).
 *
 * Run all:  mvn test -pl trading-broker -Dtest=AlpacaStrategyIntegrationTest
 * Run one:  mvn test -pl trading-broker -Dtest=AlpacaStrategyIntegrationTest#contractLookupSelectsNearTargetStrike
 *
 * Credentials: loaded from ~/.tradingapp/day-trader/app.properties
 *   broker.type=ALPACA_PAPER
 *   broker.alpaca.api_key=...
 *   broker.alpaca.api_secret=...
 *
 * Tests that submit orders are skipped automatically when the US equity market
 * is closed (checked at test startup — holidays are not accounted for).
 */
class AlpacaStrategyIntegrationTest {

    private static final String SYMBOL = "SPY";
    private static final ZoneId ET = ZoneId.of("America/New_York");

    @TempDir
    Path tempDir;

    private AppConfig config;
    private HttpClient http;
    private Account account;
    private TransactionLog log;
    private AlpacaBroker broker;

    // OCC symbols of any positions opened during a test — closed in @AfterEach
    private final List<String> openedOccSymbols = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        config = AppConfig.load();
        Assumptions.assumeTrue(config.isAlpacaBroker(),
                "Skipping: broker is not configured as Alpaca (set broker.type=ALPACA_PAPER)");
        Assumptions.assumeFalse(config.getAlpacaApiKey().isBlank(),
                "Skipping: no Alpaca API key in ~/.tradingapp/day-trader/app.properties");

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        account = new Account();
        account.setBalance(100_000.0); // safe default; overwritten by syncAccount
        log = new TransactionLog(tempDir.resolve("test.db").toString());
        broker = new AlpacaBroker(config, account, log);
    }

    @AfterEach
    void tearDown() {
        // Best-effort close of any positions opened during the test
        for (String occ : openedOccSymbols) {
            try {
                broker.submitDirect(occ, 1, "sell", "sell_to_close");
            } catch (Exception ignored) {}
        }
        // Also sweep via broker to catch any that slipped through
        broker.closeAllOptionsPositions();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 1: API connectivity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void syncAccountReturnsValidBalance() throws Exception {
        broker.syncAccount(account);
        assertTrue(account.getBalance() > 0,
                "Paper account balance should be > 0 after sync. "
                + "Check credentials in ~/.tradingapp/day-trader/app.properties.");
        System.out.println("Paper account balance: $" + String.format("%.2f", account.getBalance()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 2: Contract lookup — no order submission required, runs any time
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches the current SPY price from Alpaca's data feed so contract-lookup
     * tests use a realistic target strike rather than a hardcoded one.
     */
    private double fetchCurrentSpyPrice() throws Exception {
        String url = config.getAlpacaDataUrl() + "/v2/stocks/SPY/trades/latest";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.out.println("Warning: could not fetch SPY price, using 560.0 as fallback");
            return 560.0;
        }
        return new JSONObject(resp.body()).getJSONObject("trade").optDouble("p", 560.0);
    }

    @Test
    void contractLookupSelectsNearTargetStrike() throws Exception {
        double spyPrice = fetchCurrentSpyPrice();
        // Use ATM as the target — this is where the strategy would select a strike
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0; // round to nearest $5
        LocalDate targetExpiry = nextFridayAtLeast7Days();

        System.out.printf("SPY price=%.2f  targetStrike=%.2f  targetExpiry=%s%n",
                spyPrice, targetStrike, targetExpiry);

        String occ = broker.lookupBestContract(SYMBOL, "CALL", targetStrike, targetExpiry);
        assertNotNull(occ,
                "lookupBestContract returned null for SPY ATM call. "
                + "Options may not be enabled on this Alpaca paper account.");

        System.out.println("Selected OCC: " + occ);

        // Parse strike from OCC symbol (format: SYMBOL YYMMDD C/P STRIKE*1000, 8 digits)
        double selectedStrike = parseOccStrike(occ);
        double pctDiff = Math.abs(selectedStrike - targetStrike) / targetStrike;
        assertTrue(pctDiff <= 0.05,
                String.format("Selected strike %.2f is %.1f%% away from target %.2f (>5%%): %s",
                        selectedStrike, pctDiff * 100, targetStrike, occ));

        System.out.printf("Strike match OK: selected=%.2f  target=%.2f  diff=%.1f%%%n",
                selectedStrike, targetStrike, pctDiff * 100);
    }

    @Test
    void contractLookupExpiryIsWithinWindow() throws Exception {
        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        LocalDate targetExpiry = nextFridayAtLeast7Days();

        String occ = broker.lookupBestContract(SYMBOL, "CALL", targetStrike, targetExpiry);
        Assumptions.assumeTrue(occ != null, "No contract returned — skipping expiry check");

        LocalDate selectedExpiry = parseOccExpiry(occ);
        long daysDiff = Math.abs(selectedExpiry.toEpochDay() - targetExpiry.toEpochDay());
        assertTrue(daysDiff <= 14,
                String.format("Selected expiry %s is %d days from target %s (>14 day window): %s",
                        selectedExpiry, daysDiff, targetExpiry, occ));

        System.out.printf("Expiry match OK: selected=%s  target=%s  diff=%dd%n",
                selectedExpiry, targetExpiry, daysDiff);
    }

    @Test
    void contractLookupReturnsDifferentExpiryContractsCorrectly() throws Exception {
        // Verify that requesting two different expiry targets returns OCC symbols
        // with expiries closest to each respective target (not both returning the same expiry).
        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;

        LocalDate nearExpiry = nextFridayAtLeast7Days();
        LocalDate farExpiry = nearExpiry.plusWeeks(4);

        String nearOcc = broker.lookupBestContract(SYMBOL, "CALL", targetStrike, nearExpiry);
        String farOcc  = broker.lookupBestContract(SYMBOL, "CALL", targetStrike, farExpiry);

        Assumptions.assumeTrue(nearOcc != null && farOcc != null,
                "One or both contract lookups returned null — skipping");

        LocalDate nearSelected = parseOccExpiry(nearOcc);
        LocalDate farSelected  = parseOccExpiry(farOcc);

        System.out.printf("Near target=%s selected=%s%n", nearExpiry, nearSelected);
        System.out.printf("Far  target=%s selected=%s%n", farExpiry, farSelected);

        // The far contract's expiry should be at least 14 days later than the near contract's
        long daysBetween = farSelected.toEpochDay() - nearSelected.toEpochDay();
        assertTrue(daysBetween >= 14,
                String.format("Far expiry %s should be at least 14d after near expiry %s but only %dd apart. "
                        + "lookupBestContract may be ignoring the target expiry.",
                        farSelected, nearSelected, daysBetween));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 3: Buy + sync fingerprint stability (market hours required)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void positionKeyStableAfterBrokerSync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        LocalDate expiry = nextFridayAtLeast7Days();

        // Simulate an OPENING_BREAKOUT entry: OptionsOrderExecutor assigns SPY_BREAKOUT_CALL key
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) T = 5.0 / 365.0;
        double sigma = 0.15;
        double premium = bsEngine.callPrice(spyPrice, targetStrike, 0.045, T, sigma);

        String posKey = SYMBOL + "_BREAKOUT_CALL";
        optExec.buyCallAs(posKey, SYMBOL, targetStrike, expiry, 1, premium, "test", "");

        OptionsPosition pos = account.getOptionsPositions().get(posKey);
        Assumptions.assumeTrue(pos != null,
                "Position was not opened (order may have been rejected) — skipping sync stability check");

        // Track OCC for cleanup
        if (pos.getBrokerOccSymbol() != null) openedOccSymbols.add(pos.getBrokerOccSymbol());

        // Run syncAccount — this is the step that was previously collapsing strategy keys
        Thread.sleep(1500); // allow Alpaca to settle the order
        broker.syncAccount(account);

        assertTrue(account.getOptionsPositions().containsKey(posKey),
                "syncAccount collapsed " + posKey + " to a generic key. "
                + "Fingerprint (symbol|type|strike) mismatch between local strike and Alpaca's actual fill. "
                + "This is the root cause of false daily-loss halts.");

        OptionsPosition posAfterSync = account.getOptionsPositions().get(posKey);
        assertNotNull(posAfterSync.getBrokerOccSymbol(),
                "brokerOccSymbol should be set after sync");

        System.out.printf("Key %s stable after sync. OCC=%s K=%.2f exp=%s%n",
                posKey, posAfterSync.getBrokerOccSymbol(),
                posAfterSync.getStrike(), posAfterSync.getExpiry());
    }

    @Test
    void positionKeyStableAcrossThreeSyncs() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        LocalDate expiry = nextFridayAtLeast7Days();

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) T = 5.0 / 365.0;
        double sigma = 0.15;
        double premium = bsEngine.callPrice(spyPrice, targetStrike, 0.045, T, sigma);

        String posKey = SYMBOL + "_STOCH_CALL";
        optExec.buyCallAs(posKey, SYMBOL, targetStrike, expiry, 1, premium, "test", "");

        Assumptions.assumeTrue(account.getOptionsPositions().containsKey(posKey),
                "Position not opened — skipping");
        OptionsPosition p = account.getOptionsPositions().get(posKey);
        if (p.getBrokerOccSymbol() != null) openedOccSymbols.add(p.getBrokerOccSymbol());

        Thread.sleep(1500);
        for (int tick = 1; tick <= 3; tick++) {
            broker.syncAccount(account);
            assertTrue(account.getOptionsPositions().containsKey(posKey),
                    "Key " + posKey + " collapsed to generic on sync tick " + tick
                    + " — fingerprint mismatch persists despite contract lookup fix");
            System.out.println("Tick " + tick + ": key " + posKey + " still present. "
                    + "currentMarketPrice=" + account.getOptionsPositions().get(posKey).getCurrentMarketPrice());
        }
    }

    @Test
    void currentMarketPriceUpdatedBySync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        LocalDate expiry = nextFridayAtLeast7Days();

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) T = 5.0 / 365.0;
        double premium = bsEngine.callPrice(spyPrice, targetStrike, 0.045, T, 0.15);

        String posKey = SYMBOL + "_CALL";
        optExec.buyCallAs(posKey, SYMBOL, targetStrike, expiry, 1, premium, "test", "");
        Assumptions.assumeTrue(account.getOptionsPositions().containsKey(posKey),
                "Position not opened — skipping");

        OptionsPosition p = account.getOptionsPositions().get(posKey);
        if (p.getBrokerOccSymbol() != null) openedOccSymbols.add(p.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        OptionsPosition synced = account.getOptionsPositions().get(posKey);
        if (synced == null) {
            fail("Position key collapsed after sync — fingerprint mismatch still present");
        }
        assertTrue(synced.getCurrentMarketPrice() > 0,
                "currentMarketPrice should be populated after broker sync (used for accurate loss valuation)");
        System.out.printf("currentMarketPrice after sync: %.4f%n", synced.getCurrentMarketPrice());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 4: Buy → sync → close round trip (market hours required)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buyAndCloseRoundTripRemovesPosition() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        LocalDate expiry = nextFridayAtLeast7Days();

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) T = 5.0 / 365.0;
        double premium = bsEngine.callPrice(spyPrice, targetStrike, 0.045, T, 0.15);

        String posKey = SYMBOL + "_CALL";
        optExec.buyCallAs(posKey, SYMBOL, targetStrike, expiry, 1, premium, "test", "");
        Assumptions.assumeTrue(account.getOptionsPositions().containsKey(posKey),
                "Position not opened — skipping");

        OptionsPosition p = account.getOptionsPositions().get(posKey);
        String occSymbol = p.getBrokerOccSymbol();
        Assumptions.assumeTrue(occSymbol != null, "brokerOccSymbol not set — skipping close test");

        Thread.sleep(2000);
        broker.syncAccount(account);

        // Close via optExec (goes through submitter → AlpacaBroker.submitDirect)
        optExec.closePosition(posKey, premium * 0.9, "test close");
        Thread.sleep(2000);
        broker.syncAccount(account);

        // After close, position should be removed from account
        // (closePosition calls account.removeOptionsPosition and submitter.submitDirect)
        assertFalse(account.getOptionsPositions().containsKey(posKey),
                "Position should be removed from account after close+sync");

        // Verify Alpaca also shows no remaining position for this OCC
        JSONArray positions = fetchAlpacaPositions();
        boolean stillOpen = false;
        for (int i = 0; i < positions.length(); i++) {
            if (occSymbol.equals(positions.getJSONObject(i).optString("symbol"))) {
                stillOpen = true;
                break;
            }
        }
        assertFalse(stillOpen,
                "Position " + occSymbol + " still open in Alpaca after close. "
                + "submitDirect may have failed or order was rejected.");
        System.out.println("Close round trip OK: " + occSymbol + " removed from both local and Alpaca.");
    }

    @Test
    void expiryFromSyncDoesNotTriggerEarlyClose() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        // Use a strike well ITM so the contract is easy to fill
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        // Request expiry at least 10 days away — well outside the <3 day close guard
        LocalDate targetExpiry = nextFridayAtLeast7Days();

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = bsEngine.timeToExpiry(targetExpiry);
        if (T <= 0) T = 10.0 / 365.0;
        double premium = bsEngine.callPrice(spyPrice, targetStrike, 0.045, T, 0.15);

        String posKey = SYMBOL + "_BREAKOUT_CALL";
        optExec.buyCallAs(posKey, SYMBOL, targetStrike, targetExpiry, 1, premium, "test", "");
        Assumptions.assumeTrue(account.getOptionsPositions().containsKey(posKey),
                "Position not opened — skipping");

        OptionsPosition p = account.getOptionsPositions().get(posKey);
        if (p.getBrokerOccSymbol() != null) openedOccSymbols.add(p.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        OptionsPosition synced = account.getOptionsPositions().get(posKey);
        if (synced == null) {
            fail("Position collapsed on sync — fingerprint mismatch still present with fix");
        }

        long daysToExpiry = synced.daysToExpiry();
        assertTrue(daysToExpiry >= 3,
                String.format("daysToExpiry=%d after sync — close guard would fire immediately! "
                        + "Broker sync set wrong expiry. OCC=%s targetExpiry=%s",
                        daysToExpiry, synced.getBrokerOccSymbol(), targetExpiry));

        System.out.printf("Expiry guard OK: daysToExpiry=%d (>3) OCC=%s%n",
                daysToExpiry, synced.getBrokerOccSymbol());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 5: Daily-loss phantom-drop guard (market hours required)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void dailyLossValueDoesNotPhantomDropAfterBuyAndSync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);
        double dayStartBalance = account.getBalance();

        double spyPrice = fetchCurrentSpyPrice();
        double targetStrike = Math.round(spyPrice / 5.0) * 5.0;
        LocalDate expiry = nextFridayAtLeast7Days();

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        double T = bsEngine.timeToExpiry(expiry);
        if (T <= 0) T = 5.0 / 365.0;
        double premium = bsEngine.callPrice(spyPrice, targetStrike, 0.045, T, 0.15);

        String posKey = SYMBOL + "_CALL";
        optExec.buyCallAs(posKey, SYMBOL, targetStrike, expiry, 1, premium, "test", "");
        Assumptions.assumeTrue(account.getOptionsPositions().containsKey(posKey),
                "Position not opened — skipping");

        OptionsPosition p = account.getOptionsPositions().get(posKey);
        if (p.getBrokerOccSymbol() != null) openedOccSymbols.add(p.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        double cashBalance = account.getBalance();
        double optionsMtM = account.getOptionsPositions().values().stream()
                .mapToDouble(op -> op.getCurrentMarketPrice() * Math.abs(op.getContracts()) * 100)
                .sum();
        double currentValue = cashBalance + optionsMtM;

        double changePct = (currentValue - dayStartBalance) / dayStartBalance;
        System.out.printf("dayStart=%.2f  cash=%.2f  optsMtM=%.2f  current=%.2f  change=%.2f%%%n",
                dayStartBalance, cashBalance, optionsMtM, currentValue, changePct * 100);

        // If the fingerprint matched correctly, currentValue should be very close to dayStartBalance
        // (within a few percent of slippage/spread). The false-trigger bug caused a 5-40% phantom drop.
        assertTrue(changePct > -0.10,
                String.format("Portfolio value dropped %.1f%% immediately after buy+sync — "
                        + "phantom drop detected (fingerprint mismatch still causing position to be removed). "
                        + "currentValue=%.2f dayStart=%.2f",
                        changePct * 100, currentValue, dayStartBalance));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group 6: Full strategy pipeline (market hours required)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void openingBreakoutStrategyFingerprintStableAfterSync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        String posKey = SYMBOL + "_BREAKOUT_CALL";

        OptionsSignalRouter router = buildRouter(true);
        router.setEnabledStrategies(Set.of("OPENING_BREAKOUT"));
        // Force clock to 9:45 ET (inside OPENING_BREAKOUT window)
        ZonedDateTime firstHour = ZonedDateTime.now(ET)
                .withHour(9).withMinute(45).withSecond(0).withNano(0);
        router.setClock(() -> firstHour);

        // Build signals: ORB buy + 2 confirming buys
        List<SignalResult> signals = List.of(
                SignalResult.buy("ORB", 1.0),
                SignalResult.buy("RSI", 1.0),
                SignalResult.buy("MACD", 1.0)
        );
        router.evaluateWithSignals(SYMBOL, spyPrice, 2, 0, "test", "", signals);

        OptionsPosition pos = account.getOptionsPositions().get(posKey);
        Assumptions.assumeTrue(pos != null,
                posKey + " not opened — premium may be too low or position budget insufficient. Skipping.");
        if (pos.getBrokerOccSymbol() != null) openedOccSymbols.add(pos.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        assertTrue(account.getOptionsPositions().containsKey(posKey),
                "OPENING_BREAKOUT strategy key " + posKey + " collapsed after sync. "
                + "The strategy-key→fingerprint pipeline has a mismatch.");
        System.out.printf("OPENING_BREAKOUT OK: key=%s OCC=%s K=%.2f%n",
                posKey,
                account.getOptionsPositions().get(posKey).getBrokerOccSymbol(),
                account.getOptionsPositions().get(posKey).getStrike());
    }

    @Test
    void stochasticReversalStrategyFingerprintStableAfterSync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        String posKey = SYMBOL + "_STOCH_CALL";

        OptionsSignalRouter router = buildRouter(true);
        router.setEnabledStrategies(Set.of("STOCHASTIC_REVERSAL"));
        ZonedDateTime midDay = ZonedDateTime.now(ET).withHour(11).withMinute(0).withSecond(0).withNano(0);
        router.setClock(() -> midDay);

        List<SignalResult> signals = List.of(
                SignalResult.buy("STOCHASTIC", 1.0),
                SignalResult.buy("RSI", 1.0)
        );
        router.evaluateWithSignals(SYMBOL, spyPrice, 1, 0, "test", "", signals);

        OptionsPosition pos = account.getOptionsPositions().get(posKey);
        Assumptions.assumeTrue(pos != null,
                posKey + " not opened — skipping. "
                + "Possible: uptrend filter blocked put, or premium too low.");
        if (pos.getBrokerOccSymbol() != null) openedOccSymbols.add(pos.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        assertTrue(account.getOptionsPositions().containsKey(posKey),
                "STOCHASTIC_REVERSAL key " + posKey + " collapsed after sync");
        System.out.printf("STOCHASTIC_REVERSAL OK: key=%s OCC=%s%n",
                posKey, account.getOptionsPositions().get(posKey).getBrokerOccSymbol());
    }

    @Test
    void macdCrossoverStrategyFingerprintStableAfterSync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        String posKey = SYMBOL + "_MACD_CALL";

        OptionsSignalRouter router = buildRouter(true);
        router.setEnabledStrategies(Set.of("MACD_CROSSOVER"));
        ZonedDateTime midDay = ZonedDateTime.now(ET).withHour(11).withMinute(0).withSecond(0).withNano(0);
        router.setClock(() -> midDay);

        List<SignalResult> signals = List.of(
                SignalResult.buy("MACD", 1.0),
                SignalResult.buy("RSI", 1.0)
        );
        router.evaluateWithSignals(SYMBOL, spyPrice, 1, 0, "test", "", signals);

        OptionsPosition pos = account.getOptionsPositions().get(posKey);
        Assumptions.assumeTrue(pos != null, posKey + " not opened — skipping");
        if (pos.getBrokerOccSymbol() != null) openedOccSymbols.add(pos.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        assertTrue(account.getOptionsPositions().containsKey(posKey),
                "MACD_CROSSOVER key " + posKey + " collapsed after sync");
        System.out.printf("MACD_CROSSOVER OK: key=%s OCC=%s%n",
                posKey, account.getOptionsPositions().get(posKey).getBrokerOccSymbol());
    }

    @Test
    void rsDivergenceStrategyFingerprintStableAfterSync() throws Exception {
        assumeMarketOpen();
        broker.syncAccount(account);

        double spyPrice = fetchCurrentSpyPrice();
        String posKey = SYMBOL + "_RS_CALL";

        OptionsSignalRouter router = buildRouter(true);
        router.setEnabledStrategies(Set.of("RELATIVE_STRENGTH_DIVERGENCE"));
        ZonedDateTime midDay = ZonedDateTime.now(ET).withHour(11).withMinute(0).withSecond(0).withNano(0);
        router.setClock(() -> midDay);

        List<SignalResult> signals = List.of(
                SignalResult.buy("RELATIVE_STRENGTH", 1.0),
                SignalResult.buy("RSI", 1.0),
                SignalResult.buy("MACD", 1.0)
        );
        router.evaluateWithSignals(SYMBOL, spyPrice, 2, 0, "test", "", signals);

        OptionsPosition pos = account.getOptionsPositions().get(posKey);
        Assumptions.assumeTrue(pos != null, posKey + " not opened — skipping");
        if (pos.getBrokerOccSymbol() != null) openedOccSymbols.add(pos.getBrokerOccSymbol());

        Thread.sleep(2000);
        broker.syncAccount(account);

        assertTrue(account.getOptionsPositions().containsKey(posKey),
                "RS_DIVERGENCE key " + posKey + " collapsed after sync");
        System.out.printf("RS_DIVERGENCE OK: key=%s OCC=%s%n",
                posKey, account.getOptionsPositions().get(posKey).getBrokerOccSymbol());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private OptionsSignalRouter buildRouter(boolean uptrend) {
        BlackScholesEngine bsEngine = new BlackScholesEngine();
        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, log, broker);

        // Build sufficient price history so vol/indicator calculations don't abort
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 40; i++) {
            ph.record(SYMBOL, i % 2 == 0 ? 558.0 : 562.0, 80_000_000.0);
        }

        List<String> msgs = new ArrayList<>();
        OptionsSignalRouter router = new OptionsSignalRouter(bsEngine, optExec, account, ph, msgs::add);
        router.setUptrendSupplier(() -> uptrend);
        // Disable overnight-hold guard and entry cutoff for tests
        router.setAvoidOvernightHolds(false);
        router.setEntryCutoff(null);
        return router;
    }

    private void assumeMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(ET);
        DayOfWeek day = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        Assumptions.assumeTrue(
                day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && time.isAfter(LocalTime.of(9, 30))
                && time.isBefore(LocalTime.of(15, 55)),
                "Skipping: market is closed (this test submits real orders)");
    }

    private JSONArray fetchAlpacaPositions() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(config.getAlpacaBaseUrl() + "/positions"))
                .header("APCA-API-KEY-ID", config.getAlpacaApiKey())
                .header("APCA-API-SECRET-KEY", config.getAlpacaApiSecret())
                .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return new JSONArray();
        return new JSONArray(resp.body());
    }

    /** Parse the strike price from an OCC symbol (8-digit integer = strike * 1000). */
    private static double parseOccStrike(String occ) {
        // OCC format: SYMBOL YYMMDD C/P STRIKE*1000 (padded to 8 digits)
        // Find the C/P character — everything after it (8 chars) is the strike
        int cpIdx = -1;
        for (int i = occ.length() - 9; i >= 0; i--) {
            char c = occ.charAt(i);
            if (c == 'C' || c == 'P') { cpIdx = i; break; }
        }
        if (cpIdx < 0 || cpIdx + 9 > occ.length()) throw new IllegalArgumentException("Bad OCC: " + occ);
        long strikeRaw = Long.parseLong(occ.substring(cpIdx + 1, cpIdx + 9));
        return strikeRaw / 1000.0;
    }

    /** Parse the expiry date from an OCC symbol. */
    private static LocalDate parseOccExpiry(String occ) {
        // Find first digit run (6 digits = YYMMDD)
        int dateStart = -1;
        for (int i = 0; i < occ.length(); i++) {
            if (Character.isDigit(occ.charAt(i))) { dateStart = i; break; }
        }
        if (dateStart < 0) throw new IllegalArgumentException("Bad OCC: " + occ);
        int yy = Integer.parseInt(occ.substring(dateStart, dateStart + 2));
        int mm = Integer.parseInt(occ.substring(dateStart + 2, dateStart + 4));
        int dd = Integer.parseInt(occ.substring(dateStart + 4, dateStart + 6));
        return LocalDate.of(2000 + yy, mm, dd);
    }

    private static LocalDate nextFridayAtLeast7Days() {
        LocalDate d = LocalDate.now().plusDays(7);
        while (d.getDayOfWeek() != DayOfWeek.FRIDAY) d = d.plusDays(1);
        return d;
    }
}
