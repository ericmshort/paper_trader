package com.tradingapp.account;

import org.junit.jupiter.api.Test;
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
    void testSafetyStopIdempotent() {
        Account account = new Account();
        account.setBalance(0.0);
        SafetyStop stop = new SafetyStop(account);
        assertTrue(stop.check());
        assertTrue(stop.check(), "Repeated check should still return true");
        assertTrue(stop.isHalted());
    }
}
