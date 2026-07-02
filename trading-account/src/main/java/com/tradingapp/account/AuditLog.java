package com.tradingapp.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Structured rolling audit log for debugging trade lifecycle events.
 *
 * Format: ISO_INSTANT|TYPE|field=value|...
 *
 * Types: TRANSACTION, FORCE_CLOSE, VERIFIED, EVICTED, HALT, SIGNAL, EVENT
 *
 * Call AuditLog.get().init(logDir) once at startup. All writes are no-ops
 * until initialized (safe to call from any module without startup ordering
 * concerns). Rolling: 100 MB per file, up to 10 files before wrapping.
 */
public class AuditLog {

    private static final Logger LOGGER = Logger.getLogger("com.tradingapp.audit");
    private static final AuditLog INSTANCE = new AuditLog();
    private static final Path DEFAULT_DIR = Path.of(
            System.getProperty("user.home"), ".tradingapp", "day-trader");

    private volatile boolean initialized = false;
    // Per-thread mute flag: backtest threads suppress writes so their synthetic
    // trades don't pollute the real audit log alongside live trading events.
    private static final ThreadLocal<Boolean> MUTED = ThreadLocal.withInitial(() -> false);

    public static void muteForCurrentThread()   { MUTED.set(true);  }
    public static void unmuteForCurrentThread() { MUTED.set(false); }

    private AuditLog() {
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);
    }

    public static AuditLog get() { return INSTANCE; }

    public synchronized void init(Path logDir) {
        if (initialized) return;
        try {
            Files.createDirectories(logDir);
            String pattern = logDir.resolve("audit-%g.log").toString();
            FileHandler handler = new FileHandler(pattern, 100 * 1024 * 1024, 10, true);
            handler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return record.getMessage() + "\n";
                }
            });
            handler.setLevel(Level.ALL);
            LOGGER.addHandler(handler);
            initialized = true;
            write("EVENT|AUDIT_LOG_STARTED|dir=" + logDir);
        } catch (IOException e) {
            System.err.println("AuditLog: failed to init file handler at " + logDir + ": " + e.getMessage());
        }
    }

    /** Lazily initializes from the default path if never explicitly init'd. */
    private void ensureInit() {
        if (!initialized) init(DEFAULT_DIR);
    }

    private void write(String line) {
        if (MUTED.get()) return;
        ensureInit();
        if (!initialized) return;
        LOGGER.info(Instant.now() + "|" + line);
    }

    // ── TRANSACTION — any order dispatched to the broker ──────────────────────

    /**
     * @param action    e.g. STOCK_BUY, STOCK_SELL, OPTIONS_BUY, OPTIONS_SELL,
     *                  OPTIONS_MLEG, OPTIONS_CLOSE_DIRECT
     * @param symbol    underlying symbol
     * @param occSymbol OCC contract symbol (or order ID for mleg), null if N/A
     * @param qty       contracts or shares (positive)
     * @param price     per-unit fill price (0 if unknown at submission time)
     * @param cashDelta cash change: positive = received, negative = paid
     * @param balance   account balance after
     * @param notes     free-text (side, positionIntent, reason, etc.)
     */
    public void logTransaction(String action, String symbol, String occSymbol,
                                int qty, double price, double cashDelta,
                                double balance, String notes) {
        write(String.format("TRANSACTION|action=%s|symbol=%s|occ=%s|qty=%d|price=%.4f|cash=%.2f|balance=%.2f|notes=%s",
                action, symbol,
                occSymbol != null ? occSymbol : "-",
                qty, price, cashDelta, balance,
                notes != null ? notes.replace('|', '/') : ""));
    }

    // ── FORCE_CLOSE — EOD sweep or halt force-close sent to broker ────────────

    public void logForceClose(String occSymbol, String trigger) {
        write(String.format("FORCE_CLOSE|occ=%s|trigger=%s", occSymbol, trigger));
    }

    // ── VERIFIED — Alpaca confirmed a fill / position exists ──────────────────

    public void logVerification(String posKey, String occSymbol, double avgCost) {
        write(String.format("VERIFIED|key=%s|occ=%s|avgCost=%.4f",
                posKey,
                occSymbol != null ? occSymbol : "-",
                avgCost));
    }

    // ── EVICTED — local position removed because broker does not have it ──────

    public void logEviction(String posKey, String reason) {
        write(String.format("EVICTED|key=%s|reason=%s", posKey, reason));
    }

    // ── HALT — daily loss limit or circuit breaker activated ──────────────────

    public void logHalt(String haltType, double currentValue, double startValue,
                         double limitPct, String notes) {
        double threshold = startValue * (1.0 - limitPct);
        write(String.format("HALT|type=%s|current=%.2f|start=%.2f|threshold=%.2f|limitPct=%.1f|notes=%s",
                haltType, currentValue, startValue, threshold, limitPct * 100,
                notes != null ? notes.replace('|', '/') : ""));
    }

    // ── SIGNAL — per-tick signal summary for a symbol ─────────────────────────

    /**
     * @param symbol    underlying
     * @param price     current price
     * @param buyCount  raw BUY signal count
     * @param sellCount raw SELL signal count
     * @param signalStr human-readable signal list (e.g. "RSI:BUY, MACD:SELL")
     */
    public void logSignal(String symbol, double price, int buyCount, int sellCount, String signalStr) {
        write(String.format("SIGNAL|symbol=%s|price=%.4f|buy=%d|sell=%d|signals=%s",
                symbol, price, buyCount, sellCount,
                signalStr != null ? signalStr.replace('|', '/') : ""));
    }

    // ── EVENT — other key lifecycle events ────────────────────────────────────

    public void logEvent(String category, String message) {
        write(String.format("EVENT|category=%s|msg=%s",
                category,
                message != null ? message.replace('|', '/') : ""));
    }
}
