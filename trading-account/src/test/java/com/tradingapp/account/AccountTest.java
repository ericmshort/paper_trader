package com.tradingapp.account;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

public class AccountTest {

    @Test
    void testStartingBalance() {
        Account account = new Account();
        assertEquals(100_000.0, account.getBalance(), 0.001, "Starting balance should be $100,000");
    }

    @Test
    void testTradingNotHaltedAtStart() {
        Account account = new Account();
        assertFalse(account.isTradingHalted(), "Trading should not be halted on new account");
    }

    @Test
    void testSafetyStopHaltsAtZeroBalance() {
        Account account = new Account();
        account.setBalance(0.0);
        SafetyStop stop = new SafetyStop(account);
        assertTrue(stop.check(), "Safety stop should trigger at $0");
        assertTrue(account.isTradingHalted(), "Account should be marked halted at $0");
        assertTrue(stop.isHalted(), "isHalted() should return true");
    }

    @Test
    void testSafetyStopHaltsOnNegativeBalance() {
        Account account = new Account();
        account.setBalance(-50.0);
        SafetyStop stop = new SafetyStop(account);
        assertTrue(stop.check(), "Safety stop should trigger on negative balance");
        assertTrue(account.isTradingHalted(), "Account should be marked halted on negative balance");
    }

    @Test
    void testSafetyStopDoesNotHaltAtSmallPositiveBalance() {
        Account account = new Account();
        account.setBalance(0.01);
        SafetyStop stop = new SafetyStop(account);
        assertFalse(stop.check(), "Safety stop should not trigger at $0.01");
        assertFalse(account.isTradingHalted(), "Account should not be halted at $0.01");
    }

    @Test
    void testTotalExposureFractionEmptyAccount() {
        Account account = new Account();
        assertEquals(0.0, account.totalExposureFraction(), 0.001, "Empty account should have zero exposure");
    }

    @Test
    void testTotalExposureFractionEquityOnly() {
        Account account = new Account();
        // 200 shares at $210 = $42,000; deduct from balance to simulate realistic state
        account.addOrUpdatePosition("MSFT", 200, 210.0, Position.PositionType.STOCK);
        account.setBalance(100_000.0 - 42_000.0); // $58,000
        // $42,000 / ($58,000 + $42,000) = 42%
        assertEquals(0.42, account.totalExposureFraction(), 0.001);
    }

    @Test
    void testTotalExposureFractionOptionsOnly() {
        Account account = new Account();
        // 5 contracts × $2.00 × 100 = $1,000; deduct from balance
        account.addOptionsPosition("AAPL_CALL",
                new OptionsPosition("AAPL", "CALL", 150.0, LocalDate.now().plusMonths(1), 5, 2.00));
        account.setBalance(100_000.0 - 1_000.0); // $99,000
        // $1,000 / ($99,000 + $1,000) = 1%
        assertEquals(0.01, account.totalExposureFraction(), 0.001);
    }

    @Test
    void testTotalExposureFractionCombined() {
        Account account = new Account();
        account.addOrUpdatePosition("AAPL", 100, 150.0, Position.PositionType.STOCK);   // $15,000
        account.addOptionsPosition("AAPL_CALL",
                new OptionsPosition("AAPL", "CALL", 150.0, LocalDate.now().plusMonths(1), 10, 1.00)); // $1,000
        account.setBalance(100_000.0 - 16_000.0); // $84,000
        // $16,000 / ($84,000 + $16,000) = 16%
        assertEquals(0.16, account.totalExposureFraction(), 0.001);
    }

    /**
     * Regression: credit spread short legs (negative contracts) used to reduce the exposure
     * numerator, letting the 60% cap be bypassed when many spreads were open. With 20 credit
     * spreads open the cap could effectively allow ~70k in long options instead of 60k.
     * Short legs must not deflate the deployment count.
     */
    @Test
    void testCreditSpreadShortLegsDoNotReduceExposure() {
        Account account = new Account();
        LocalDate expiry = LocalDate.now().plusMonths(1);

        // Simulate a bull-put spread: short leg has negative contracts, long leg positive.
        // Short leg: contracts=-5, premiumPaid=$3.00 → would have been: balance += credit
        // Long leg:  contracts=+5, premiumPaid=$1.50 → balance -= debit
        double creditReceived = 3.00 * 100 * 5;  // $1,500 received
        double debitPaid      = 1.50 * 100 * 5;  // $750 paid
        account.setBalance(100_000.0 + creditReceived - debitPaid); // $100,750
        account.addOptionsPosition("TEST_BULLPUTSPREAD_SHORT",
                new OptionsPosition("TEST", "PUT", 145.0, expiry, -5, 3.00));
        account.addOptionsPosition("TEST_BULLPUTSPREAD_LONG",
                new OptionsPosition("TEST", "PUT", 140.0, expiry,  5, 1.50));

        // Exposure should reflect only the long leg's cash deployment ($750 / ~$100k ≈ 0.75%)
        // and must NOT be negative or zero just because the short leg offsets it.
        double exposure = account.totalExposureFraction();
        assertTrue(exposure > 0,
                "Exposure must be positive — short legs must not deflate it below zero");
        // Long leg: $750 / totalPortfolio ≈ $750 / $100,000 ≈ 0.75%
        assertEquals(0.0075, exposure, 0.001,
                "Exposure should reflect only the long leg cash deployed");
    }

    @Test
    void testDailyLossHaltedDefaultsFalse() {
        Account account = new Account();
        assertFalse(account.isDailyLossHalted(), "Daily loss halt should be false on a new account");
    }

    @Test
    void testDailyLossHaltedSetAndClear() {
        Account account = new Account();
        account.setDailyLossHalted(true);
        assertTrue(account.isDailyLossHalted());
        account.setDailyLossHalted(false);
        assertFalse(account.isDailyLossHalted());
    }

    @Test
    void testDailyLossHaltedResetOnAccountReset() {
        Account account = new Account();
        account.setDailyLossHalted(true);
        account.reset(Account.STARTING_BALANCE);
        assertFalse(account.isDailyLossHalted(), "reset() should clear the daily loss halt");
    }

    @Test
    void testSafetyStopIdempotent() {
        Account account = new Account();
        account.setBalance(0.0);
        SafetyStop stop = new SafetyStop(account);
        assertTrue(stop.check());
        assertTrue(stop.check(), "Repeated check should still return true");
        assertTrue(stop.isHalted());
    }
}
