package com.tradingapp.account;

public class SafetyStop {

    private static final double MINIMUM_BALANCE = 0.0;

    private final Account account;
    private boolean warningPrinted;

    public SafetyStop(Account account) {
        this.account = account;
        this.warningPrinted = false;
    }

    public boolean check() {
        if (account.getBalance() <= MINIMUM_BALANCE) {
            account.setTradingHalted(true);
            if (!warningPrinted) {
                System.out.printf("SAFETY STOP: account balance $%.2f is zero or negative. All trading halted.%n",
                        account.getBalance());
                warningPrinted = true;
            }
            return true;
        }
        return false;
    }

    public boolean isHalted() {
        return account.isTradingHalted();
    }
}
