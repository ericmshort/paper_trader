package com.tradingapp.options;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.account.TransactionRecord.TransactionAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies end-to-end balance accounting for credit spread open and close.
 *
 * Ensures:
 *  - The net credit added at open equals exactly (shortPrem - longPrem) × 100 × contracts
 *  - premiumPaid is stored without inflation (regression: IV_PREMIUM was previously
 *    multiplied in here, creating phantom credits that syncAccount() erased moments later)
 *  - The close debit/credit is computed from the passed-in close premiums, not from premiumPaid
 *  - Realized P&L = open credit - close cost
 *  - Positions are removed and transaction records are correct after a full round trip
 */
public class CreditSpreadAccountingTest {

    @TempDir Path tempDir;

    private static final LocalDate EXPIRY = LocalDate.now().plusDays(52);
    private static final double DELTA = 0.001;

    // Mock broker submitter that accepts all multi-leg orders
    private final OptionsSubmitter mockBroker = new OptionsSubmitter() {
        @Override
        public String submit(String symbol, String optionType, double strike,
                             LocalDate expiry, int contracts, String side) {
            return null;
        }
        @Override
        public String submitMultiLeg(List<MultiLegOrder> legs, int contracts) {
            return "mock-order-" + legs.hashCode();
        }
    };

    // ── Call credit spread ────────────────────────────────────────────────────

    @Test
    void callSpread_openBalanceIsExactNetCredit() {
        Account account = new Account();
        OptionsOrderExecutor exec = new OptionsOrderExecutor(
                account, new TransactionLog(tempDir.resolve("call-open.db").toString()), mockBroker);

        double shortPrem = 5.00;
        double longPrem  = 3.00;
        int    contracts = 3;
        double expectedCredit = (shortPrem - longPrem) * 100 * contracts; // $600

        exec.openCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                "AAPL", "CALL", 200.0, 210.0, EXPIRY, contracts,
                shortPrem, longPrem, "test-signal", "", "CALL SPREAD");

        assertEquals(Account.STARTING_BALANCE + expectedCredit, account.getBalance(), DELTA,
                "Balance should increase by exactly the net credit — no inflation");
    }

    @Test
    void callSpread_openPremiumPaidStoredExactly() {
        Account account = new Account();
        OptionsOrderExecutor exec = new OptionsOrderExecutor(
                account, new TransactionLog(tempDir.resolve("call-prem.db").toString()), mockBroker);

        double shortPrem = 5.00;
        double longPrem  = 3.00;

        exec.openCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                "AAPL", "CALL", 200.0, 210.0, EXPIRY, 2,
                shortPrem, longPrem, "signal", "", "CALL SPREAD");

        OptionsPosition shortPos = account.getOptionsPositions().get("AAPL_CALLSPREAD_SHORTCALL");
        OptionsPosition longPos  = account.getOptionsPositions().get("AAPL_CALLSPREAD_LONGCALL");

        assertNotNull(shortPos, "Short leg must be added to account");
        assertNotNull(longPos,  "Long leg must be added to account");
        assertEquals(shortPrem, shortPos.getPremiumPaid(), DELTA,
                "Short leg premiumPaid must equal exactly what was passed in — no IV inflation");
        assertEquals(longPrem,  longPos.getPremiumPaid(),  DELTA,
                "Long leg premiumPaid must equal exactly what was passed in");
        assertEquals(-2, shortPos.getContracts(), "Short leg contracts must be negative");
        assertEquals( 2, longPos.getContracts(),  "Long leg contracts must be positive");
    }

    @Test
    void callSpread_openCreatesCorrectTransactionRecords() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call-rec.db").toString());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(account, log, mockBroker);

        exec.openCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                "AAPL", "CALL", 200.0, 210.0, EXPIRY, 2,
                5.00, 3.00, "signal", "", "CALL SPREAD");

        List<TransactionRecord> records = log.findAll();
        assertEquals(2, records.size(), "Open should produce exactly 2 transaction records");

        TransactionRecord shortRec = records.stream()
                .filter(r -> r.getAction() == TransactionAction.CALL_SELL).findFirst().orElseThrow();
        TransactionRecord longRec  = records.stream()
                .filter(r -> r.getAction() == TransactionAction.CALL_BUY).findFirst().orElseThrow();

        assertEquals("AAPL",  shortRec.getSymbol());
        assertEquals(2,       shortRec.getQuantity());
        assertEquals(5.00,    shortRec.getPricePerUnit(), DELTA);
        assertTrue(shortRec.getReason().contains("(SHORT)"));

        assertEquals("AAPL",  longRec.getSymbol());
        assertEquals(2,       longRec.getQuantity());
        assertEquals(3.00,    longRec.getPricePerUnit(), DELTA);
        assertTrue(longRec.getReason().contains("(LONG)"));

        // Both records belong to the same group order
        assertEquals(shortRec.getExternalId(), longRec.getExternalId(), "Both legs share the broker order ID");
        assertEquals(shortRec.getGroupId(),    longRec.getGroupId(),    "Both legs share the group ID");
    }

    @Test
    void callSpread_closeBalanceAndPnL() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call-close.db").toString());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(account, log, mockBroker);

        // Open: shortPrem=$5.00, longPrem=$3.00, 3 contracts → credit $600
        exec.openCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                "AAPL", "CALL", 200.0, 210.0, EXPIRY, 3,
                5.00, 3.00, "open-signal", "", "CALL SPREAD");

        double balanceAfterOpen = account.getBalance(); // 100_000 + 600 = 100_600

        // Close at 50% profit: shortClose=$3.50, longClose=$2.00 → closeCost=$450
        double shortClose = 3.50;
        double longClose  = 2.00;
        double closeCost  = (shortClose - longClose) * 100 * 3; // $450
        double expectedPnL = 600.0 - closeCost;                 // $150

        exec.closeCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                shortClose, longClose, "Profit target 50%: +$150");

        // Balance: after-open + (short close debit) + (long close credit)
        //        = 100_600 + (3.50×100×-3) + (2.00×100×3)
        //        = 100_600 - 1_050 + 600 = 100_150
        assertEquals(balanceAfterOpen - closeCost, account.getBalance(), DELTA,
                "Balance after close = after-open minus close cost");
        assertEquals(Account.STARTING_BALANCE + expectedPnL, account.getBalance(), DELTA,
                "Final balance = starting + net P&L");

        assertTrue(account.getOptionsPositions().isEmpty(), "Both positions must be removed after close");
        assertEquals(expectedPnL, account.getTotalRealizedPnL(), DELTA,
                "Realized P&L must equal open credit minus close cost");
    }

    @Test
    void callSpread_closeTransactionRecords() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("call-close-rec.db").toString());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(account, log, mockBroker);

        exec.openCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                "AAPL", "CALL", 200.0, 210.0, EXPIRY, 2,
                5.00, 3.00, "open", "", "CALL SPREAD");

        exec.closeCreditSpread(
                "AAPL_CALLSPREAD_SHORTCALL", "AAPL_CALLSPREAD_LONGCALL",
                3.50, 2.00, "Profit target 50%");

        List<TransactionRecord> records = log.findAll();
        assertEquals(4, records.size(), "Open+close should produce 4 total records");

        // Closing the SHORT leg = buying back → CALL_BUY with negative quantity
        TransactionRecord shortClose = records.stream()
                .filter(r -> r.getAction() == TransactionAction.CALL_BUY && r.getQuantity() < 0)
                .findFirst().orElseThrow(() -> new AssertionError("Missing short-leg close record"));
        // Closing the LONG leg = selling → CALL_SELL with positive quantity
        TransactionRecord longClose = records.stream()
                .filter(r -> r.getAction() == TransactionAction.CALL_SELL && r.getQuantity() > 0
                        && r.getReason().contains("Profit target"))
                .findFirst().orElseThrow(() -> new AssertionError("Missing long-leg close record"));

        assertEquals(-2,   shortClose.getQuantity());
        assertEquals(3.50, shortClose.getPricePerUnit(), DELTA, "Short close price must match");
        assertEquals( 2,   longClose.getQuantity());
        assertEquals(2.00, longClose.getPricePerUnit(), DELTA, "Long close price must match");

        // Close legs share the same group
        assertEquals(shortClose.getExternalId(), longClose.getExternalId());
        assertEquals(shortClose.getGroupId(),    longClose.getGroupId());
    }

    // ── Put credit spread ─────────────────────────────────────────────────────

    @Test
    void putSpread_fullRoundTripBalance() {
        Account account = new Account();
        TransactionLog log = new TransactionLog(tempDir.resolve("put-round.db").toString());
        OptionsOrderExecutor exec = new OptionsOrderExecutor(account, log, mockBroker);

        // Open put credit spread: short K=480 @ $4.50, long K=470 @ $2.50, 2 contracts
        double shortPrem = 4.50;
        double longPrem  = 2.50;
        int contracts    = 2;
        double credit    = (shortPrem - longPrem) * 100 * contracts; // $400

        exec.openCreditSpread(
                "NVDA_PUTSPREAD_SHORTPUT", "NVDA_PUTSPREAD_LONGPUT",
                "NVDA", "PUT", 480.0, 470.0, EXPIRY, contracts,
                shortPrem, longPrem, "put-signal", "", "PUT SPREAD");

        assertEquals(Account.STARTING_BALANCE + credit, account.getBalance(), DELTA,
                "Put spread open should credit balance by net premium");

        OptionsPosition shortPos = account.getOptionsPositions().get("NVDA_PUTSPREAD_SHORTPUT");
        assertNotNull(shortPos);
        assertEquals("PUT", shortPos.getType());
        assertEquals(-contracts, shortPos.getContracts());
        assertEquals(shortPrem, shortPos.getPremiumPaid(), DELTA);

        // Close at a loss: short buyback $3.00, long sell $1.50 → closeCost=$300, pnl=$100
        double shortClose = 3.00;
        double longClose  = 1.50;
        double closeCost  = (shortClose - longClose) * 100 * contracts; // $300
        double pnl        = credit - closeCost;                          // $100

        exec.closeCreditSpread(
                "NVDA_PUTSPREAD_SHORTPUT", "NVDA_PUTSPREAD_LONGPUT",
                shortClose, longClose, "Profit target 50%: +$100");

        assertEquals(Account.STARTING_BALANCE + pnl, account.getBalance(), DELTA,
                "Final balance = starting + (credit - closeCost)");
        assertTrue(account.getOptionsPositions().isEmpty());
        assertEquals(pnl, account.getTotalRealizedPnL(), DELTA);

        // Verify close records use the actual close prices, not premiumPaid
        List<TransactionRecord> all = log.findAll();
        TransactionRecord shortCloseRec = all.stream()
                .filter(r -> r.getAction() == TransactionAction.PUT_BUY && r.getQuantity() < 0)
                .findFirst().orElseThrow();
        TransactionRecord longCloseRec = all.stream()
                .filter(r -> r.getAction() == TransactionAction.PUT_SELL && r.getQuantity() > 0
                        && r.getReason().contains("Profit target"))
                .findFirst().orElseThrow();

        assertEquals(shortClose, shortCloseRec.getPricePerUnit(), DELTA,
                "Short close record must use the close price, not premiumPaid");
        assertEquals(longClose,  longCloseRec.getPricePerUnit(), DELTA,
                "Long close record must use the close price, not premiumPaid");
    }

    // ── Regression: IV_PREMIUM inflation would have caused phantom credit ─────

    @Test
    void regression_noPremiumInflationOnShortLeg() {
        // Before the fix, PremiumSellerRouter multiplied shortPrem by (1 + 0.15) before passing
        // to openCreditSpread(). This test verifies that whatever is passed in is stored exactly —
        // the executor does not apply additional inflation.
        //
        // Scenario mirroring the June 30 AMAT bug:
        //   actual market short prem ≈ $40.88 (BS without inflation)
        //   inflated shortPrem (old code) = $40.88 × 1.15 = $47.01
        //
        // With inflated input, the phantom credit at open was $2,625 instead of $783.
        // syncAccount() would reset balance to Alpaca's actual $783 credit,
        // causing an apparent $1,842 loss per spread.
        //
        // This test passes the CORRECT (un-inflated) short premium to confirm the executor
        // stores it as-is, so the credit it adds to balance matches what Alpaca actually received.

        Account account = new Account();
        OptionsOrderExecutor exec = new OptionsOrderExecutor(
                account, new TransactionLog(tempDir.resolve("regression.db").toString()), mockBroker);

        double actualShortPrem = 40.88; // what Alpaca would price the option at
        double actualLongPrem  = 38.26;
        int    contracts       = 3;
        double expectedCredit  = (actualShortPrem - actualLongPrem) * 100 * contracts; // $786

        exec.openCreditSpread(
                "AMAT_CALLSPREAD_SHORTCALL", "AMAT_CALLSPREAD_LONGCALL",
                "AMAT", "CALL", 800.0, 810.0, EXPIRY, contracts,
                actualShortPrem, actualLongPrem, "signal", "", "CALL SPREAD");

        // Balance must increase by EXACTLY $786, not $786 + 15% phantom
        assertEquals(Account.STARTING_BALANCE + expectedCredit, account.getBalance(), DELTA,
                "Credit must equal actual premium passed in — no 15% IV_PREMIUM inflation");

        // premiumPaid must be stored exactly
        OptionsPosition shortPos = account.getOptionsPositions().get("AMAT_CALLSPREAD_SHORTCALL");
        assertEquals(actualShortPrem, shortPos.getPremiumPaid(), DELTA,
                "premiumPaid must be the actual market price, not inflated");

        // If the old inflated value ($47.01) had been stored as premiumPaid and then
        // Alpaca set currentMarketPrice to $40.86, the exit check would have seen:
        //   credit = (47.01 - 38.26) × 300 = $2,625
        //   closeCost = (40.86 - 38.25) × 300 = $783
        //   false pnl = $1,842 → immediately triggers 50% profit target
        //
        // With the fix, premiumPaid = $40.88 ≈ Alpaca's currentMarketPrice,
        // so pnl ≈ 0 and no false trigger fires.
        double inflatiedPremiumPaid = actualShortPrem * 1.15;
        assertNotEquals(inflatiedPremiumPaid, shortPos.getPremiumPaid(), DELTA,
                "premiumPaid must NOT be the 15%-inflated value from the old IV_PREMIUM code");
    }
}
