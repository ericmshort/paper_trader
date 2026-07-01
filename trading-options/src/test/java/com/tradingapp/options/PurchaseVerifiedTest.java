package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.data.PriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that PremiumSellerRouter exit checks are suppressed until Alpaca has
 * confirmed a newly opened position (purchaseVerified = true).
 *
 * Without this guard, positions opened with any entry price mismatch could trigger
 * an immediate false profit target on the very first markPositionsToMarket call
 * after the position is added to the account.
 */
public class PurchaseVerifiedTest {

    @TempDir Path tempDir;

    private static final double BASE_PRICE = 500.0;
    private static final LocalDate EXPIRY   = LocalDate.now().plusDays(45);

    private final OptionsSubmitter mockBroker = new OptionsSubmitter() {
        @Override
        public String submit(String symbol, String optionType, double strike,
                             LocalDate expiry, int contracts, String side) { return null; }
        @Override
        public String submitMultiLeg(List<MultiLegOrder> legs, int contracts) {
            return "mock-" + legs.hashCode();
        }
    };

    /** Seeds enough daily prices to produce a non-zero historical vol. */
    private PriceHistory seedPrices(String symbol) {
        PriceHistory ph = new PriceHistory();
        for (int i = 0; i < 30; i++) {
            // Small variation so historicalVol() > 0
            ph.recordDaily(symbol, BASE_PRICE + (i % 5) * 0.5, 1_000_000);
        }
        return ph;
    }

    // ── Put credit spread ──────────────────────────────────────────────────────

    @Test
    void putSpread_exitSuppressedUntilVerified() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put-verified.db").toString());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(account, log, mockBroker);

        List<String> closedLog = new ArrayList<>();
        PremiumSellerRouter router = new PremiumSellerRouter(
                new BlackScholesEngine(), exec, account, seedPrices("MSFT"), closedLog::add);

        // Open a put credit spread: short $490 @ $3.00, long $480 @ $1.00 → $200 credit
        exec.openCreditSpread(
                "MSFT" + PremiumSellerRouter.PUTSPREAD_SHORT,
                "MSFT" + PremiumSellerRouter.PUTSPREAD_LONG,
                "MSFT", "PUT", 490.0, 480.0, EXPIRY, 1,
                3.00, 1.00, "signal", "", "PUT SPREAD");

        OptionsPosition shortPos = account.getOptionsPositions().get("MSFT" + PremiumSellerRouter.PUTSPREAD_SHORT);
        OptionsPosition longPos  = account.getOptionsPositions().get("MSFT" + PremiumSellerRouter.PUTSPREAD_LONG);
        assertNotNull(shortPos);
        assertNotNull(longPos);

        // Confirm both legs start unverified
        assertFalse(shortPos.isPurchaseVerified(), "short leg must not be verified immediately after open");
        assertFalse(longPos.isPurchaseVerified(),  "long leg must not be verified immediately after open");

        // Set market prices that would normally trigger the 50% profit target:
        // credit=$200, closeCost=(0.25-0.10)*100=$15, pnl=$185 ≥ $100 target
        shortPos.setCurrentMarketPrice(0.25);
        longPos.setCurrentMarketPrice(0.10);

        // Evaluate — exit must be suppressed because purchaseVerified = false
        router.evaluateWithSignals("MSFT", BASE_PRICE, 2, 0, "BUY", "", List.of());

        assertTrue(account.getOptionsPositions().containsKey("MSFT" + PremiumSellerRouter.PUTSPREAD_SHORT),
                "spread must NOT close before Alpaca confirms it (purchaseVerified=false)");
        assertTrue(closedLog.stream().noneMatch(m -> m.contains("PUT CREDIT SPREAD closed")),
                "no close log entry should appear before verification");

        // ── Simulate Alpaca syncAccount confirming both legs ──────────────────
        shortPos.setPurchaseVerified(true);
        longPos.setPurchaseVerified(true);

        // Evaluate again — profit target should now fire
        router.evaluateWithSignals("MSFT", BASE_PRICE, 2, 0, "BUY", "", List.of());

        assertFalse(account.getOptionsPositions().containsKey("MSFT" + PremiumSellerRouter.PUTSPREAD_SHORT),
                "spread must close after Alpaca verification when profit target is met");
        assertTrue(closedLog.stream().anyMatch(m -> m.contains("PUT CREDIT SPREAD closed")),
                "close log entry must appear after verification");
    }

    // ── Call credit spread ─────────────────────────────────────────────────────

    @Test
    void callSpread_exitSuppressedUntilVerified() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call-verified.db").toString());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(account, log, mockBroker);

        List<String> closedLog = new ArrayList<>();
        PremiumSellerRouter router = new PremiumSellerRouter(
                new BlackScholesEngine(), exec, account, seedPrices("AAPL"), closedLog::add);

        exec.openCreditSpread(
                "AAPL" + PremiumSellerRouter.CALLSPREAD_SHORT,
                "AAPL" + PremiumSellerRouter.CALLSPREAD_LONG,
                "AAPL", "CALL", 510.0, 520.0, EXPIRY, 1,
                3.00, 1.00, "signal", "", "CALL SPREAD");

        OptionsPosition shortPos = account.getOptionsPositions().get("AAPL" + PremiumSellerRouter.CALLSPREAD_SHORT);
        OptionsPosition longPos  = account.getOptionsPositions().get("AAPL" + PremiumSellerRouter.CALLSPREAD_LONG);

        assertFalse(shortPos.isPurchaseVerified());
        assertFalse(longPos.isPurchaseVerified());

        shortPos.setCurrentMarketPrice(0.25);
        longPos.setCurrentMarketPrice(0.10);

        router.evaluateWithSignals("AAPL", BASE_PRICE, 0, 2, "SELL", "", List.of());

        assertTrue(account.getOptionsPositions().containsKey("AAPL" + PremiumSellerRouter.CALLSPREAD_SHORT),
                "call spread must NOT close before verification");

        shortPos.setPurchaseVerified(true);
        longPos.setPurchaseVerified(true);

        router.evaluateWithSignals("AAPL", BASE_PRICE, 0, 2, "SELL", "", List.of());

        assertFalse(account.getOptionsPositions().containsKey("AAPL" + PremiumSellerRouter.CALLSPREAD_SHORT),
                "call spread must close after verification when profit target is met");
    }

    // ── purchaseVerified flag default and setter ───────────────────────────────

    @Test
    void optionsPosition_defaultsToUnverified() {
        OptionsPosition pos = new OptionsPosition("AAPL", "CALL", 200.0,
                LocalDate.now().plusDays(30), 1, 5.0);
        assertFalse(pos.isPurchaseVerified(), "new OptionsPosition must default to unverified");
    }

    @Test
    void optionsPosition_canBeVerified() {
        OptionsPosition pos = new OptionsPosition("AAPL", "CALL", 200.0,
                LocalDate.now().plusDays(30), 1, 5.0);
        pos.setPurchaseVerified(true);
        assertTrue(pos.isPurchaseVerified());
    }
}
