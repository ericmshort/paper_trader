package com.tradingapp.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ClosedTradeRecord {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    private final long   timestampRaw;
    private final String time;
    private final String symbol;
    private final String type;
    private final int    quantity;
    private final double entryRaw;
    private final double exitRaw;
    private final String entryPrice;
    private final String exitPrice;
    private final double pnlRaw;
    private final String pnl;
    private final String groupId;

    public ClosedTradeRecord(String symbol, String type, int quantity,
                             double entryRaw, double exitRaw, double pnlRaw,
                             long timestamp, String groupId) {
        this.timestampRaw = timestamp;
        this.time         = FMT.format(Instant.ofEpochMilli(timestamp));
        this.symbol       = symbol;
        this.type         = type;
        this.quantity     = quantity;
        this.entryRaw     = entryRaw;
        this.exitRaw      = exitRaw;
        this.entryPrice   = String.format("$%.4f", entryRaw);
        this.exitPrice    = String.format("$%.4f", exitRaw);
        this.pnlRaw       = pnlRaw;
        this.pnl          = pnlRaw >= 0
                ? String.format("+$%,.2f", pnlRaw)
                : String.format("-$%,.2f", Math.abs(pnlRaw));
        this.groupId      = groupId;
    }

    /** Convenience constructor for single-leg (non-grouped) records. */
    public ClosedTradeRecord(String symbol, String type, int quantity,
                             double entryRaw, double exitRaw, double pnlRaw, long timestamp) {
        this(symbol, type, quantity, entryRaw, exitRaw, pnlRaw, timestamp, null);
    }

    public long   getTimestampRaw() { return timestampRaw; }
    public String getTime()         { return time; }
    public String getSymbol()       { return symbol; }
    public String getType()         { return type; }
    public int    getQuantity()     { return quantity; }
    public double getEntryRaw()     { return entryRaw; }
    public double getExitRaw()      { return exitRaw; }
    public String getEntryPrice()   { return entryPrice; }
    public String getExitPrice()    { return exitPrice; }
    public double getPnlRaw()       { return pnlRaw; }
    public String getPnl()          { return pnl; }
    public String getGroupId()      { return groupId; }
}
