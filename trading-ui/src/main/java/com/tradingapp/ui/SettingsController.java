package com.tradingapp.ui;

import com.tradingapp.broker.AlpacaBroker;
import com.tradingapp.broker.AlpacaWebSocketFreeProvider;
import com.tradingapp.broker.AppConfig;
import com.tradingapp.data.CandleHistory;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;

import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Consumer;

public class SettingsController implements Initializable {

    @FXML private ComboBox<String> brokerTypeCombo;
    @FXML private HBox alpacaRow;
    @FXML private HBox alpacaSecretRow;
    @FXML private HBox alpacaUrlRow;
    @FXML private TextField apiKeyField;
    @FXML private PasswordField apiSecretField;
    @FXML private TextField baseUrlField;
    @FXML private ComboBox<String> quoteProviderCombo;
    @FXML private Label quoteProviderNote;
    @FXML private HBox testWsRow;
    @FXML private Button testWsButton;
    @FXML private TextField dailyLossLimitField;
    @FXML private TextField maxPortfolioExposureField;
    @FXML private CheckBox marketRegimeFilterCheck;
    @FXML private TextField earningsBlackoutField;
    @FXML private CheckBox strategyHighDeltaScalp;
    @FXML private CheckBox strategyMomentumNearTerm;
    @FXML private CheckBox strategyLongCall;
    @FXML private CheckBox strategyLongPut;
    @FXML private CheckBox strategyZeroDte;
    @FXML private Button testConnectionButton;
    @FXML private Label statusLabel;

    private AppConfig config;
    private AppConfig.BrokerType activeBrokerType = AppConfig.BrokerType.SIMULATED;
    private Consumer<AppConfig> onSettingsSaved;
    private Consumer<AppConfig> onBrokerReset;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        brokerTypeCombo.getItems().addAll("Simulated", "Alpaca Paper", "Alpaca Live");
        quoteProviderCombo.getItems().addAll("Yahoo Finance", "Alpaca", "Alpaca WebSocket Free");

        config = AppConfig.load();
        populateFromConfig(config);

        brokerTypeCombo.setOnAction(e -> updateAlpacaFieldVisibility());
        quoteProviderCombo.setOnAction(e -> updateQuoteNote());
    }

    public void setActiveBrokerType(AppConfig.BrokerType type) {
        this.activeBrokerType = type;
    }

    public void setOnSettingsSaved(Consumer<AppConfig> handler) {
        this.onSettingsSaved = handler;
    }

    public void setOnBrokerReset(Consumer<AppConfig> handler) {
        this.onBrokerReset = handler;
    }

    private void populateFromConfig(AppConfig cfg) {
        brokerTypeCombo.setValue(brokerTypeLabel(cfg.getBrokerType()));
        apiKeyField.setText(cfg.getAlpacaApiKey());
        apiSecretField.setText(cfg.getAlpacaApiSecret());
        quoteProviderCombo.setValue(switch (cfg.getQuoteProviderType()) {
            case ALPACA                -> "Alpaca";
            case ALPACA_WEBSOCKET_FREE -> "Alpaca WebSocket Free";
            default                    -> "Yahoo Finance";
        });
        dailyLossLimitField.setText(String.valueOf(cfg.getDailyLossLimitPct()));
        maxPortfolioExposureField.setText(String.valueOf(cfg.getMaxPortfolioExposurePct()));
        marketRegimeFilterCheck.setSelected(cfg.isMarketRegimeFilterEnabled());
        earningsBlackoutField.setText(String.valueOf(cfg.getEarningsBlackoutDays()));
        Set<String> enabled = cfg.getEnabledStrategies();
        strategyHighDeltaScalp.setSelected(enabled.contains("HIGH_DELTA_SCALP"));
        strategyMomentumNearTerm.setSelected(enabled.contains("MOMENTUM_NEAR_TERM"));
        strategyLongCall.setSelected(enabled.contains("LONG_CALL"));
        strategyLongPut.setSelected(enabled.contains("LONG_PUT"));
        strategyZeroDte.setSelected(enabled.contains("ZERO_DTE"));
        updateAlpacaFieldVisibility();
        updateQuoteNote();
    }

    private void updateAlpacaFieldVisibility() {
        boolean alpaca = !brokerTypeCombo.getValue().equals("Simulated");
        alpacaRow.setVisible(alpaca);
        alpacaRow.setManaged(alpaca);
        alpacaSecretRow.setVisible(alpaca);
        alpacaSecretRow.setManaged(alpaca);
        alpacaUrlRow.setVisible(alpaca);
        alpacaUrlRow.setManaged(alpaca);
        testConnectionButton.setVisible(alpaca);
        testConnectionButton.setManaged(alpaca);
        if (alpaca) {
            baseUrlField.setText(brokerTypeCombo.getValue().equals("Alpaca Live")
                    ? "https://api.alpaca.markets"
                    : "https://paper-api.alpaca.markets/v2");
        }
        clearStatus();
    }

    private void updateQuoteNote() {
        switch (quoteProviderCombo.getValue()) {
            case "Alpaca" -> quoteProviderNote.setText(
                    "Alpaca REST — polls latest trade per symbol every 30s. "
                    + "Requires Alpaca broker credentials.");
            case "Alpaca WebSocket Free" -> quoteProviderNote.setText(
                    "Alpaca WebSocket (IEX free tier) — real-time tick stream for 30 most-liquid symbols. "
                    + "Builds 1-min/5-min candles for pattern detection. Requires Alpaca credentials.");
            default -> quoteProviderNote.setText(
                    "Yahoo Finance provides near real-time quotes for large-cap US equities.");
        }
        boolean isWs = "Alpaca WebSocket Free".equals(quoteProviderCombo.getValue());
        testWsRow.setVisible(isWs);
        testWsRow.setManaged(isWs);
    }

    @FXML
    private void onTestWebSocket() {
        AppConfig test = buildConfigFromFields();
        if (test.getAlpacaApiKey().isBlank() || test.getAlpacaApiSecret().isBlank()) {
            setStatus("Enter API key and secret first.", false);
            return;
        }
        setStatus("Connecting to Alpaca IEX WebSocket...", true);
        testWsButton.setDisable(true);

        Thread t = new Thread(() -> {
            AlpacaWebSocketFreeProvider provider = new AlpacaWebSocketFreeProvider(
                    test, new CandleHistory(),
                    msg -> Platform.runLater(() -> setStatus(msg, true)));
            provider.start();

            // Poll for authentication up to 10 seconds
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && !provider.isConnected()) {
                try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            }
            boolean connected = provider.isConnected();

            // If authenticated, wait up to 5 more seconds for a tick to confirm data flow
            String tickInfo = "";
            if (connected) {
                long tickDeadline = System.currentTimeMillis() + 5_000;
                while (System.currentTimeMillis() < tickDeadline
                        && provider.getQuotes(java.util.List.of("SPY")).isEmpty()) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
                boolean gotTick = !provider.getQuotes(java.util.List.of("SPY")).isEmpty();
                tickInfo = gotTick ? " — receiving live ticks" : " — no ticks yet (market may be closed)";
            }

            provider.stop();
            final String info = tickInfo;
            Platform.runLater(() -> {
                testWsButton.setDisable(false);
                if (connected) {
                    setStatus("WebSocket authenticated" + info + ".", true);
                } else {
                    setStatus("WebSocket connection failed. Check API key and secret.", false);
                }
            });
        }, "alpaca-ws-test");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onTestConnection() {
        AppConfig test = buildConfigFromFields();
        if (test.getAlpacaApiKey().isBlank() || test.getAlpacaApiSecret().isBlank()) {
            setStatus("Enter API key and secret first.", false);
            return;
        }
        setStatus("Testing...", true);
        Thread t = new Thread(() -> {
            AlpacaBroker broker = new AlpacaBroker(test, null, null) {
                @Override public void syncAccount(com.tradingapp.account.Account account) {}
            };
            org.json.JSONObject result = broker.testConnection();
            Platform.runLater(() -> {
                if (result != null) {
                    String equity = String.format("$%,.2f", result.optDouble("equity", 0.0));
                    setStatus("Connected — account equity: " + equity, true);
                } else {
                    setStatus("Connection failed. Check API key and secret.", false);
                }
            });
        }, "alpaca-test");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onSaveSettings() {
        AppConfig newConfig = buildConfigFromFields();
        AppConfig.BrokerType newBrokerType = newConfig.getBrokerType();

        if (newBrokerType != activeBrokerType) {
            if (!confirmBrokerChange(activeBrokerType, newBrokerType)) return;
            saveAndFireReset(newConfig);
        } else {
            saveNormally(newConfig);
        }
    }

    private boolean confirmBrokerChange(AppConfig.BrokerType from, AppConfig.BrokerType to) {
        String fromLabel = brokerTypeLabel(from);
        String toLabel   = brokerTypeLabel(to);

        String detail = to == AppConfig.BrokerType.SIMULATED
                ? "Your account will be reset to $100,000 and all trade history will be cleared."
                : "All local trade history will be cleared. Your account balance and positions will be loaded from " + toLabel + ".";

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Switch Broker — Data Will Be Lost");
        alert.setHeaderText("Switch from " + fromLabel + " to " + toLabel + "?");
        alert.setContentText(detail + "\n\nThis cannot be undone.");

        ButtonType switchBtn = new ButtonType("Switch Broker", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("Cancel",         ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(switchBtn, cancelBtn);

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == switchBtn;
    }

    private void saveAndFireReset(AppConfig newConfig) {
        try {
            newConfig.save();
            config = newConfig;
            activeBrokerType = newConfig.getBrokerType();
            setStatus("Broker changed. Resetting account...", true);
            if (onBrokerReset != null) onBrokerReset.accept(newConfig);
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage(), false);
        }
    }

    private void saveNormally(AppConfig newConfig) {
        try {
            newConfig.save();
            config = newConfig;
            setStatus("Settings saved.", true);
            if (onSettingsSaved != null) onSettingsSaved.accept(newConfig);
        } catch (Exception e) {
            setStatus("Save failed: " + e.getMessage(), false);
        }
    }

    private AppConfig buildConfigFromFields() {
        AppConfig cfg = new AppConfig();
        cfg.setBrokerType(switch (brokerTypeCombo.getValue()) {
            case "Alpaca Paper" -> AppConfig.BrokerType.ALPACA_PAPER;
            case "Alpaca Live"  -> AppConfig.BrokerType.ALPACA_LIVE;
            default             -> AppConfig.BrokerType.SIMULATED;
        });
        cfg.setAlpacaApiKey(apiKeyField.getText().strip());
        cfg.setAlpacaApiSecret(apiSecretField.getText().strip());
        cfg.setQuoteProviderType(switch (quoteProviderCombo.getValue()) {
            case "Alpaca"                -> AppConfig.QuoteProviderType.ALPACA;
            case "Alpaca WebSocket Free" -> AppConfig.QuoteProviderType.ALPACA_WEBSOCKET_FREE;
            default                      -> AppConfig.QuoteProviderType.YAHOO;
        });
        try {
            double limit = Double.parseDouble(dailyLossLimitField.getText().strip());
            cfg.setDailyLossLimitPct(Math.max(0, limit));
        } catch (NumberFormatException ignored) {}
        try {
            double exposure = Double.parseDouble(maxPortfolioExposureField.getText().strip());
            cfg.setMaxPortfolioExposurePct(Math.min(100, Math.max(1, exposure)));
        } catch (NumberFormatException ignored) {}
        cfg.setAvoidOvernightHolds(true);
        cfg.setMarketRegimeFilterEnabled(marketRegimeFilterCheck.isSelected());
        try {
            int days = Integer.parseInt(earningsBlackoutField.getText().strip());
            cfg.setEarningsBlackoutDays(Math.max(0, days));
        } catch (NumberFormatException ignored) {}
        Set<String> strategies = new LinkedHashSet<>();
        if (strategyHighDeltaScalp.isSelected())   strategies.add("HIGH_DELTA_SCALP");
        if (strategyMomentumNearTerm.isSelected())  strategies.add("MOMENTUM_NEAR_TERM");
        if (strategyLongCall.isSelected())          strategies.add("LONG_CALL");
        if (strategyLongPut.isSelected())           strategies.add("LONG_PUT");
        if (strategyZeroDte.isSelected())           strategies.add("ZERO_DTE");
        cfg.setEnabledStrategies(strategies);
        return cfg;
    }

    private void setStatus(String msg, boolean success) {
        statusLabel.setText(msg);
        statusLabel.setStyle(success
                ? "-fx-font-size: 12px; -fx-text-fill: #00ff88;"
                : "-fx-font-size: 12px; -fx-text-fill: #ff4444;");
    }

    private void clearStatus() { statusLabel.setText(""); }

    public AppConfig getConfig() { return config; }

    public static String brokerTypeLabel(AppConfig.BrokerType type) {
        return switch (type) {
            case ALPACA_PAPER -> "Alpaca Paper";
            case ALPACA_LIVE  -> "Alpaca Live";
            default           -> "Simulated";
        };
    }
}
