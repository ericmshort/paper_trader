package com.tradingapp.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ClosedTradeRecord {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final String time;
    private final String symbol;
    private final String type;
    private final int quantity;
    private final String entryPrice;
    private final String exitPrice;
    private final double pnlRaw;
    private final String pnl;

    public ClosedTradeRecord(String symbol, String type, int quantity,
                             double entryRaw, double exitRaw, double pnlRaw, long timestamp) {
        this.time = FMT.format(Instant.ofEpochMilli(timestamp));
        this.symbol = symbol;
        this.type = type;
        this.quantity = quantity;
        this.entryPrice = String.format("$%.4f", entryRaw);
        this.exitPrice = String.format("$%.4f", exitRaw);
        this.pnlRaw = pnlRaw;
        this.pnl = pnlRaw >= 0
                ? String.format("+$%,.2f", pnlRaw)
                : String.format("-$%,.2f", Math.abs(pnlRaw));
    }

    public String getTime() { return time; }
    public String getSymbol() { return symbol; }
    public String getType() { return type; }
    public int getQuantity() { return quantity; }
    public String getEntryPrice() { return entryPrice; }
    public String getExitPrice() { return exitPrice; }
    public double getPnlRaw() { return pnlRaw; }
    public String getPnl() { return pnl; }
}
