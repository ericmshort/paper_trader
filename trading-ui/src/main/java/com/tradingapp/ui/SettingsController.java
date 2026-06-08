package com.tradingapp.ui;

import com.tradingapp.broker.AlpacaBroker;
import com.tradingapp.broker.AppConfig;
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
    @FXML private TextField dailyLossLimitField;
    @FXML private TextField maxPortfolioExposureField;
    @FXML private CheckBox avoidOvernightCheck;
    @FXML private CheckBox marketRegimeFilterCheck;
    @FXML private TextField earningsBlackoutField;
    @FXML private CheckBox strategyCoveredCall;
    @FXML private CheckBox strategyBullPutSpread;
    @FXML private CheckBox strategyBearCallSpread;
    @FXML private CheckBox strategyIronCondor;
    @FXML private CheckBox strategyLongCall;
    @FXML private CheckBox strategyLongPut;
    @FXML private CheckBox strategyHighDeltaScalp;
    @FXML private CheckBox strategyMomentumNearTerm;
    @FXML private CheckBox strategyStraddle;
    @FXML private CheckBox strategyZeroDte;
    @FXML private Button testConnectionButton;
    @FXML private Label statusLabel;

    private AppConfig config;
    private AppConfig.BrokerType activeBrokerType = AppConfig.BrokerType.SIMULATED;
    private String activeApiKey = "";
    private Consumer<AppConfig> onSettingsSaved;
    private Consumer<AppConfig> onBrokerReset;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        brokerTypeCombo.getItems().addAll("Simulated", "Alpaca Paper", "Alpaca Live");
        quoteProviderCombo.getItems().addAll("Yahoo Finance", "Alpaca");

        config = AppConfig.load();
        populateFromConfig(config);

        brokerTypeCombo.setOnAction(e -> updateAlpacaFieldVisibility());
        quoteProviderCombo.setOnAction(e -> updateQuoteNote());
    }

    public void setActiveBrokerType(AppConfig.BrokerType type) {
        this.activeBrokerType = type;
    }

    public void setActiveApiKey(String key) {
        this.activeApiKey = key == null ? "" : key;
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
        quoteProviderCombo.setValue(cfg.getQuoteProviderType() == AppConfig.QuoteProviderType.ALPACA
                ? "Alpaca" : "Yahoo Finance");
        dailyLossLimitField.setText(String.valueOf(cfg.getDailyLossLimitPct()));
        maxPortfolioExposureField.setText(String.valueOf(cfg.getMaxPortfolioExposurePct()));
        avoidOvernightCheck.setSelected(cfg.isAvoidOvernightHolds());
        marketRegimeFilterCheck.setSelected(cfg.isMarketRegimeFilterEnabled());
        earningsBlackoutField.setText(String.valueOf(cfg.getEarningsBlackoutDays()));
        Set<String> enabled = cfg.getEnabledStrategies();
        strategyCoveredCall.setSelected(enabled.contains("COVERED_CALL"));
        strategyBullPutSpread.setSelected(enabled.contains("BULL_PUT_SPREAD"));
        strategyBearCallSpread.setSelected(enabled.contains("BEAR_CALL_SPREAD"));
        strategyIronCondor.setSelected(enabled.contains("IRON_CONDOR"));
        strategyLongCall.setSelected(enabled.contains("LONG_CALL"));
        strategyLongPut.setSelected(enabled.contains("LONG_PUT"));
        strategyHighDeltaScalp.setSelected(enabled.contains("HIGH_DELTA_SCALP"));
        strategyMomentumNearTerm.setSelected(enabled.contains("MOMENTUM_NEAR_TERM"));
        strategyStraddle.setSelected(enabled.contains("STRADDLE"));
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
        if ("Alpaca".equals(quoteProviderCombo.getValue())) {
            quoteProviderNote.setText("Alpaca free tier uses IEX data (limited market coverage). "
                    + "A paid Alpaca subscription is required for full SIP real-time quotes.");
        } else {
            quoteProviderNote.setText("Yahoo Finance provides near real-time quotes for large-cap US equities.");
        }
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

        boolean brokerTypeChanged = newBrokerType != activeBrokerType;
        boolean apiKeyChanged = newBrokerType != AppConfig.BrokerType.SIMULATED
                && !newConfig.getAlpacaApiKey().equals(activeApiKey);

        if (brokerTypeChanged || apiKeyChanged) {
            if (!confirmAccountReset(brokerTypeChanged, activeBrokerType, newBrokerType)) return;
            saveAndFireReset(newConfig);
        } else {
            saveNormally(newConfig);
        }
    }

    private boolean confirmAccountReset(boolean brokerTypeChanged,
                                        AppConfig.BrokerType from, AppConfig.BrokerType to) {
        String toLabel = brokerTypeLabel(to);

        String header = brokerTypeChanged
                ? "Switch from " + brokerTypeLabel(from) + " to " + toLabel + "?"
                : "Change API credentials for " + toLabel + "?";

        String detail = to == AppConfig.BrokerType.SIMULATED
                ? "Your account will be reset to $100,000 and all trade history will be cleared."
                : "All local trade history will be cleared. Your account balance and positions will be loaded from " + toLabel + ".";

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Account Reset — Data Will Be Lost");
        alert.setHeaderText(header);
        alert.setContentText(detail + "\n\nThis cannot be undone.");

        ButtonType confirmBtn = new ButtonType("Reset & Switch", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn  = new ButtonType("Cancel",         ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(confirmBtn, cancelBtn);

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == confirmBtn;
    }

    private void saveAndFireReset(AppConfig newConfig) {
        try {
            newConfig.save();
            config = newConfig;
            activeBrokerType = newConfig.getBrokerType();
            activeApiKey = newConfig.getAlpacaApiKey();
            setStatus("Account changed. Resetting...", true);
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
        cfg.setQuoteProviderType("Alpaca".equals(quoteProviderCombo.getValue())
                ? AppConfig.QuoteProviderType.ALPACA
                : AppConfig.QuoteProviderType.YAHOO);
        try {
            double limit = Double.parseDouble(dailyLossLimitField.getText().strip());
            cfg.setDailyLossLimitPct(Math.max(0, limit));
        } catch (NumberFormatException ignored) {}
        try {
            double exposure = Double.parseDouble(maxPortfolioExposureField.getText().strip());
            cfg.setMaxPortfolioExposurePct(Math.min(100, Math.max(1, exposure)));
        } catch (NumberFormatException ignored) {}
        cfg.setAvoidOvernightHolds(avoidOvernightCheck.isSelected());
        cfg.setMarketRegimeFilterEnabled(marketRegimeFilterCheck.isSelected());
        try {
            int days = Integer.parseInt(earningsBlackoutField.getText().strip());
            cfg.setEarningsBlackoutDays(Math.max(0, days));
        } catch (NumberFormatException ignored) {}
        Set<String> strategies = new LinkedHashSet<>();
        if (strategyCoveredCall.isSelected())    strategies.add("COVERED_CALL");
        if (strategyBullPutSpread.isSelected())  strategies.add("BULL_PUT_SPREAD");
        if (strategyBearCallSpread.isSelected()) strategies.add("BEAR_CALL_SPREAD");
        if (strategyIronCondor.isSelected())     strategies.add("IRON_CONDOR");
        if (strategyLongCall.isSelected())       strategies.add("LONG_CALL");
        if (strategyLongPut.isSelected())        strategies.add("LONG_PUT");
        if (strategyHighDeltaScalp.isSelected()) strategies.add("HIGH_DELTA_SCALP");
        if (strategyMomentumNearTerm.isSelected()) strategies.add("MOMENTUM_NEAR_TERM");
        if (strategyStraddle.isSelected())       strategies.add("STRADDLE");
        if (strategyZeroDte.isSelected())        strategies.add("ZERO_DTE");
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
