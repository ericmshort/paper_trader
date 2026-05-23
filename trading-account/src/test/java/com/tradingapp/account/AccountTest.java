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
        // 200 shares at $210 = $42,000 = 42% of $100k starting balance
        account.addOrUpdatePosition("MSFT", 200, 210.0, Position.PositionType.STOCK);
        assertEquals(0.42, account.totalExposureFraction(), 0.001);
    }

    @Test
    void testTotalExposureFractionOptionsOnly() {
        Account account = new Account();
        // 5 contracts, $2.00 premium = 5 * 2.00 * 100 = $1,000 = 1% of $100k
        account.addOptionsPosition("AAPL_CALL",
                new OptionsPosition("AAPL", "CALL", 150.0, LocalDate.now().plusMonths(1), 5, 2.00));
        assertEquals(0.01, account.totalExposureFraction(), 0.001);
    }

    @Test
    void testTotalExposureFractionCombined() {
        Account account = new Account();
        account.addOrUpdatePosition("AAPL", 100, 150.0, Position.PositionType.STOCK);   // $15,000
        account.addOptionsPosition("AAPL_CALL",
                new OptionsPosition("AAPL", "CALL", 150.0, LocalDate.now().plusMonths(1), 10, 1.00)); // $1,000
        // $16,000 / $100,000 = 16%
        assertEquals(0.16, account.totalExposureFraction(), 0.001);
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
