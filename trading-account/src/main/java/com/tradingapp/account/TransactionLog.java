package com.tradingapp.account;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransactionLog {

    private static final String DEFAULT_DB_PATH =
            System.getProperty("user.home") + "/.tradingapp/transactions.db";

    private final String dbPath;

    public TransactionLog() {
        this(DEFAULT_DB_PATH);
    }

    public TransactionLog(String dbPath) {
        this.dbPath = dbPath;
        initDb();
    }

    private Connection connect() {
        try {
            Class.forName("org.sqlite.JDBC");
            return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Cannot connect to SQLite database: " + dbPath, e);
        }
    }

    private void initDb() {
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        symbol TEXT NOT NULL,
                        action TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        price_per_unit REAL NOT NULL,
                        fee_charged REAL NOT NULL,
                        balance_after REAL NOT NULL,
                        reason TEXT,
                        signals TEXT
                    )
                    """);
            for (String migration : new String[]{
                    "ALTER TABLE transactions ADD COLUMN features TEXT",
                    "ALTER TABLE transactions ADD COLUMN external_id TEXT",
                    "ALTER TABLE transactions ADD COLUMN group_id TEXT"
            }) {
                try {
                    stmt.execute(migration);
                } catch (SQLException e) {
                    if (!e.getMessage().contains("duplicate column name")) throw e;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize transaction log database", e);
        }
    }

    public void insert(TransactionRecord record) {
        String sql = """
                INSERT INTO transactions
                    (timestamp, symbol, action, quantity, price_per_unit, fee_charged, balance_after, reason, signals, features, external_id, group_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, record.getTimestamp());
            ps.setString(2, record.getSymbol());
            ps.setString(3, record.getAction().name());
            ps.setInt(4, record.getQuantity());
            ps.setDouble(5, record.getPricePerUnit());
            ps.setDouble(6, record.getFeeCharged());
            ps.setDouble(7, record.getBalanceAfter());
            ps.setString(8, record.getReason());
            ps.setString(9, record.getSignals());
            ps.setString(10, record.getFeatures());
            ps.setString(11, record.getExternalId());
            ps.setString(12, record.getGroupId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    record.setId(keys.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to insert transaction record", e);
        }
    }

    public void updateGroupId(long id, String groupId) {
        String sql = "UPDATE transactions SET group_id = ? WHERE id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, groupId);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update group_id", e);
        }
    }

    public void updateFillPrice(String externalId, double fillPrice) {
        String sql = "UPDATE transactions SET price_per_unit = ? WHERE external_id = ?";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, fillPrice);
            ps.setString(2, externalId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update fill price", e);
        }
    }

    public void purgeUnmatched(java.util.Set<String> knownExternalIds) {
        // Delete every record whose external_id is NULL (never sent to broker)
        // or whose external_id is not in the set of known broker order IDs.
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM transactions WHERE external_id IS NULL");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge unmatched transactions", e);
        }
        if (knownExternalIds.isEmpty()) return;
        // Build a parameterised IN clause for the known IDs
        String placeholders = String.join(",",
                java.util.Collections.nCopies(knownExternalIds.size(), "?"));
        String sql = "DELETE FROM transactions WHERE external_id IS NOT NULL AND external_id NOT IN (" + placeholders + ")";
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String id : knownExternalIds) ps.setString(i++, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to purge unmatched transactions", e);
        }
    }

    public boolean existsByExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) return false;
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM transactions WHERE external_id = ? LIMIT 1")) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query by external_id", e);
        }
    }

    public List<TransactionRecord> findAll() {
        String sql = "SELECT * FROM transactions ORDER BY timestamp DESC";
        List<TransactionRecord> records = new ArrayList<>();
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                TransactionRecord r = new TransactionRecord();
                r.setId(rs.getLong("id"));
                r.setTimestamp(rs.getLong("timestamp"));
                r.setSymbol(rs.getString("symbol"));
                r.setAction(TransactionRecord.TransactionAction.valueOf(rs.getString("action")));
                r.setQuantity(rs.getInt("quantity"));
                r.setPricePerUnit(rs.getDouble("price_per_unit"));
                r.setFeeCharged(rs.getDouble("fee_charged"));
                r.setBalanceAfter(rs.getDouble("balance_after"));
                r.setReason(rs.getString("reason"));
                r.setSignals(rs.getString("signals"));
                r.setFeatures(rs.getString("features"));
                r.setExternalId(rs.getString("external_id"));
                r.setGroupId(rs.getString("group_id"));
                records.add(r);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query transaction log", e);
        }
        return records;
    }

    public void restoreAccount(Account account) {
        List<TransactionRecord> records = findAll();
        if (records.isEmpty()) return;

        // findAll() returns DESC; reverse for chronological replay
        List<TransactionRecord> asc = new ArrayList<>(records);
        java.util.Collections.reverse(asc);

        Map<String, Integer> openShares = new HashMap<>();
        Map<String, Double> avgCost = new HashMap<>();
        Map<String, OptionsPosition> openOptions = new HashMap<>();
        double realizedPnL = 0.0;

        for (TransactionRecord r : asc) {
            switch (r.getAction()) {
                case BUY -> {
                    int prev = openShares.getOrDefault(r.getSymbol(), 0);
                    double prevAvg = avgCost.getOrDefault(r.getSymbol(), 0.0);
                    int newTotal = prev + r.getQuantity();
                    double newAvg = newTotal > 0
                            ? (prev * prevAvg + r.getQuantity() * r.getPricePerUnit()) / newTotal
                            : r.getPricePerUnit();
                    openShares.put(r.getSymbol(), newTotal);
                    avgCost.put(r.getSymbol(), newAvg);
                }
                case SELL -> {
                    double entry = avgCost.getOrDefault(r.getSymbol(), r.getPricePerUnit());
                    realizedPnL += (r.getPricePerUnit() - entry) * r.getQuantity() - r.getFeeCharged();
                    int remaining = openShares.getOrDefault(r.getSymbol(), 0) - r.getQuantity();
                    if (remaining <= 0) {
                        openShares.remove(r.getSymbol());
                        avgCost.remove(r.getSymbol());
                    } else {
                        openShares.put(r.getSymbol(), remaining);
                    }
                }
                case CALL_BUY, PUT_BUY -> {
                    String type = r.getAction() == TransactionRecord.TransactionAction.CALL_BUY ? "CALL" : "PUT";
                    String key = r.getSymbol() + "_" + type;
                    double strike = parseStrikeFromReason(r.getReason());
                    LocalDate expiry = parseExpiryFromReason(r.getReason());
                    if (expiry != null && !expiry.isBefore(LocalDate.now())) {
                        openOptions.put(key, new OptionsPosition(
                                r.getSymbol(), type, strike, expiry, r.getQuantity(), r.getPricePerUnit()));
                    }
                }
                case CALL_SELL -> openOptions.remove(r.getSymbol() + "_CALL");
                case PUT_SELL  -> openOptions.remove(r.getSymbol() + "_PUT");
            }
        }

        account.setBalance(asc.get(asc.size() - 1).getBalanceAfter());

        for (Map.Entry<String, Integer> e : openShares.entrySet()) {
            account.addOrUpdatePosition(e.getKey(), e.getValue(), avgCost.get(e.getKey()),
                    Position.PositionType.STOCK);
        }

        for (Map.Entry<String, OptionsPosition> e : openOptions.entrySet()) {
            account.addOptionsPosition(e.getKey(), e.getValue());
        }

        account.addRealizedPnL(realizedPnL);
    }

    private double parseStrikeFromReason(String reason) {
        if (reason == null) return 0.0;
        int k = reason.indexOf("K=");
        if (k < 0) return 0.0;
        int end = reason.indexOf(' ', k + 2);
        String val = end < 0 ? reason.substring(k + 2) : reason.substring(k + 2, end);
        try { return Double.parseDouble(val); } catch (NumberFormatException e) { return 0.0; }
    }

    private LocalDate parseExpiryFromReason(String reason) {
        if (reason == null) return null;
        int e = reason.indexOf("exp=");
        if (e < 0) return null;
        String val = reason.substring(e + 4).trim();
        try { return LocalDate.parse(val); } catch (Exception ex) { return null; }
    }

    /**
     * Returns the account balance at end-of-day for the last day before {@code today}.
     * Used by TradingLoop to seed dayStartValue from durable storage so restarts don't
     * reset the daily loss baseline to the current (already-depleted) balance.
     * Returns STARTING_BALANCE if no transactions exist before today.
     */
    public double getBalanceBeforeDate(LocalDate today) {
        long dayStartMs = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String sql = "SELECT balance_after FROM transactions WHERE timestamp < ? ORDER BY timestamp DESC LIMIT 1";
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, dayStartMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("balance_after");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query balance before date", e);
        }
        return Account.STARTING_BALANCE;
    }

    public int countWins() {
        String sql = "SELECT COUNT(*) FROM transactions WHERE action='SELL' AND (price_per_unit * quantity) > fee_charged";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count wins", e);
        }
    }

    public void clearAll() {
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM transactions");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear transaction log", e);
        }
    }

    public int countLosses() {
        String sql = "SELECT COUNT(*) FROM transactions WHERE action='SELL' AND (price_per_unit * quantity) <= fee_charged";
        try (Connection conn = connect();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count losses", e);
        }
    }
}
