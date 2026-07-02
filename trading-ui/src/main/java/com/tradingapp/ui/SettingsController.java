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
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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
    @FXML private CheckBox strategyOpeningBreakout;
    @FXML private CheckBox strategyStochasticReversal;
    @FXML private CheckBox strategyRelativeStrengthDivergence;
    @FXML private CheckBox strategyMacdCrossover;
    @FXML private CheckBox avoidOvernightHoldsCheck;
    @FXML private CheckBox stockTradingEnabledCheck;
    @FXML private CheckBox optionsTradingEnabledCheck;
    @FXML private CheckBox premiumSellerEnabledCheck;
    @FXML private CheckBox premiumPcsCheck;
    @FXML private CheckBox premiumCcsCheck;
    @FXML private TextField premiumMaxContractsField;
    @FXML private TextField premiumMinEntryField;
    @FXML private TextField premiumMaxSpreadsField;
    @FXML private TextField premiumSectorCapField;
    @FXML private CheckBox premiumPcsMacdCheck;
    @FXML private CheckBox premiumCcsSellSignalCheck;
    @FXML private CheckBox premiumShortExpiryCheck;
    @FXML private TextField optionsStopLossField;
    @FXML private TextField downtrendPutMinSignalsField;
    @FXML private TextField entryStartTimeField;
    @FXML private TextField entryCutoffField;
    @FXML private TextField profitTargetField;
    @FXML private TextField reversalMinSignalsField;
    @FXML private TextField entryConfirmationTicksField;
    @FXML private TextField overnightFloorField;
    @FXML private TextField callsDisabledField;
    @FXML private TextField putsDisabledField;
    @FXML private PasswordField claudeApiKeyField;
    @FXML private TextField trailingStopField;
    @FXML private TextField maxLossPerTradeField;
    @FXML private TextField circuitBreakerField;
    @FXML private TextArea  stockWatchlistArea;
    @FXML private TextArea  optionsWatchlistArea;
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
        quoteProviderCombo.getItems().addAll("Yahoo Finance", "Alpaca", "Alpaca WebSocket Free");

        config = AppConfig.load();
        populateFromConfig(config);

        brokerTypeCombo.setOnAction(e -> updateAlpacaFieldVisibility());
        quoteProviderCombo.setOnAction(e -> updateQuoteNote());
        stockTradingEnabledCheck.setOnAction(e -> updateStockStrategyCheckboxStates());
        optionsTradingEnabledCheck.setOnAction(e -> updateOptionsStrategyCheckboxStates());
        premiumSellerEnabledCheck.setOnAction(e -> updatePremiumStrategyCheckboxStates());
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
        quoteProviderCombo.setValue(switch (cfg.getQuoteProviderType()) {
            case ALPACA                -> "Alpaca";
            case ALPACA_WEBSOCKET_FREE -> "Alpaca WebSocket Free";
            default                    -> "Yahoo Finance";
        });
        dailyLossLimitField.setText(String.valueOf(cfg.getDailyLossLimitPct()));
        maxPortfolioExposureField.setText(String.valueOf(cfg.getMaxPortfolioExposurePct()));
        marketRegimeFilterCheck.setSelected(cfg.isMarketRegimeFilterEnabled());
        earningsBlackoutField.setText(String.valueOf(cfg.getEarningsBlackoutDays()));
        claudeApiKeyField.setText(cfg.getClaudeApiKey());
        Set<String> enabled = cfg.getEnabledStrategies();
        strategyHighDeltaScalp.setSelected(enabled.contains("HIGH_DELTA_SCALP"));
        strategyMomentumNearTerm.setSelected(enabled.contains("MOMENTUM_NEAR_TERM"));
        strategyLongCall.setSelected(enabled.contains("LONG_CALL"));
        strategyLongPut.setSelected(enabled.contains("LONG_PUT"));
        strategyZeroDte.setSelected(enabled.contains("ZERO_DTE"));
        strategyOpeningBreakout.setSelected(enabled.contains("OPENING_BREAKOUT"));
        strategyStochasticReversal.setSelected(enabled.contains("STOCHASTIC_REVERSAL"));
        strategyRelativeStrengthDivergence.setSelected(enabled.contains("RELATIVE_STRENGTH_DIVERGENCE"));
        strategyMacdCrossover.setSelected(enabled.contains("MACD_CROSSOVER"));
        avoidOvernightHoldsCheck.setSelected(cfg.isAvoidOvernightHolds());
        stockTradingEnabledCheck.setSelected(cfg.isStockTradingEnabled());
        optionsTradingEnabledCheck.setSelected(cfg.isOptionsTradingEnabled());
        premiumSellerEnabledCheck.setSelected(cfg.isPremiumSellerEnabled());
        Set<String> premStrats = cfg.getPremiumEnabledStrategies();
        premiumPcsCheck.setSelected(premStrats.contains(com.tradingapp.options.PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD));
        premiumCcsCheck.setSelected(premStrats.contains(com.tradingapp.options.PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD));
        premiumMaxContractsField.setText(String.valueOf(cfg.getPremiumSellerMaxContracts()));
        premiumMinEntryField.setText(cfg.getPremiumMinEntryTime() != null ? cfg.getPremiumMinEntryTime().toString() : "");
        premiumMaxSpreadsField.setText(String.valueOf(cfg.getPremiumMaxConcurrentSpreads()));
        premiumSectorCapField.setText(String.valueOf(cfg.getPremiumSectorConcentrationLimit()));
        premiumPcsMacdCheck.setSelected(cfg.isPremiumPcsRequireNonNegMacd());
        premiumCcsSellSignalCheck.setSelected(cfg.isPremiumCcsRequireSellSignal());
        premiumShortExpiryCheck.setSelected(cfg.isPremiumUseShortExpiry());
        optionsStopLossField.setText(String.valueOf((int) Math.round(cfg.getOptionsStopLossFrac() * 100)));
        downtrendPutMinSignalsField.setText(String.valueOf(cfg.getDowntrendPutMinSignals()));
        entryStartTimeField.setText(cfg.getOptionsEntryStartTime() != null ? cfg.getOptionsEntryStartTime().toString() : "");
        entryCutoffField.setText(cfg.getOptionsEntryCutoff() != null ? cfg.getOptionsEntryCutoff().toString() : "");
        profitTargetField.setText(String.valueOf(cfg.getProfitTarget()));
        reversalMinSignalsField.setText(String.valueOf(cfg.getReversalMinSignals()));
        entryConfirmationTicksField.setText(String.valueOf(cfg.getEntryConfirmationTicks()));
        overnightFloorField.setText(String.valueOf((int) Math.round(cfg.getOvernightMinPremiumFrac() * 100)));
        callsDisabledField.setText(String.join(",", cfg.getOptionsCallsDisabled()));
        putsDisabledField.setText(String.join(",", cfg.getOptionsPutsDisabled()));
        trailingStopField.setText(String.format("%.0f", cfg.getTrailingStopPct() * 100));
        maxLossPerTradeField.setText(String.format("%.2f", cfg.getMaxLossPerTradePct() * 100));
        circuitBreakerField.setText(String.format("%.1f", cfg.getCircuitBreakerPct() * 100));
        stockWatchlistArea.setText(String.join(", ", cfg.getStockWatchlist()));
        optionsWatchlistArea.setText(String.join(", ", cfg.getOptionsWatchlist()));
        updateAlpacaFieldVisibility();
        updateQuoteNote();
        updateStockStrategyCheckboxStates();
        updateOptionsStrategyCheckboxStates();
        updatePremiumStrategyCheckboxStates();
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
                    + "Signals evaluated on each poll. Requires Alpaca broker credentials.");
            case "Alpaca WebSocket Free" -> quoteProviderNote.setText(
                    "Alpaca WebSocket (IEX free tier) — real-time tick stream for 30 most-liquid symbols. "
                    + "Prices update continuously; signals evaluated every 5s. "
                    + "Builds 1-min/5-min candles for ORB, VWAP, and candlestick patterns. Requires Alpaca credentials.");
            default -> quoteProviderNote.setText(
                    "Yahoo Finance — near real-time quotes for large-cap US equities. Signals evaluated every 5s.");
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
        cfg.setClaudeApiKey(claudeApiKeyField.getText().strip());
        cfg.setAvoidOvernightHolds(avoidOvernightHoldsCheck.isSelected());
        cfg.setMarketRegimeFilterEnabled(marketRegimeFilterCheck.isSelected());
        cfg.setStockTradingEnabled(stockTradingEnabledCheck.isSelected());
        cfg.setOptionsTradingEnabled(optionsTradingEnabledCheck.isSelected());
        cfg.setPremiumSellerEnabled(premiumSellerEnabledCheck.isSelected());
        Set<String> premStrats = new LinkedHashSet<>();
        if (premiumPcsCheck.isSelected()) premStrats.add(com.tradingapp.options.PremiumSellerRouter.STRATEGY_PUT_CREDIT_SPREAD);
        if (premiumCcsCheck.isSelected()) premStrats.add(com.tradingapp.options.PremiumSellerRouter.STRATEGY_CALL_CREDIT_SPREAD);
        cfg.setPremiumEnabledStrategies(premStrats);
        try {
            int mc = Integer.parseInt(premiumMaxContractsField.getText().strip());
            cfg.setPremiumSellerMaxContracts(Math.min(50, Math.max(1, mc)));
        } catch (NumberFormatException ignored) {}
        String premMinEntryText = premiumMinEntryField.getText().strip();
        if (!premMinEntryText.isBlank()) {
            try { cfg.setPremiumMinEntryTime(LocalTime.parse(premMinEntryText)); }
            catch (DateTimeParseException ignored) {}
        }
        try {
            cfg.setPremiumMaxConcurrentSpreads(Math.max(0, Integer.parseInt(premiumMaxSpreadsField.getText().strip())));
        } catch (NumberFormatException ignored) {}
        try {
            cfg.setPremiumSectorConcentrationLimit(Math.max(0, Integer.parseInt(premiumSectorCapField.getText().strip())));
        } catch (NumberFormatException ignored) {}
        cfg.setPremiumPcsRequireNonNegMacd(premiumPcsMacdCheck.isSelected());
        cfg.setPremiumCcsRequireSellSignal(premiumCcsSellSignalCheck.isSelected());
        cfg.setPremiumUseShortExpiry(premiumShortExpiryCheck.isSelected());
        try {
            int days = Integer.parseInt(earningsBlackoutField.getText().strip());
            cfg.setEarningsBlackoutDays(Math.max(0, days));
        } catch (NumberFormatException ignored) {}
        Set<String> strategies = new LinkedHashSet<>();
        if (strategyHighDeltaScalp.isSelected())               strategies.add("HIGH_DELTA_SCALP");
        if (strategyMomentumNearTerm.isSelected())              strategies.add("MOMENTUM_NEAR_TERM");
        if (strategyLongCall.isSelected())                      strategies.add("LONG_CALL");
        if (strategyLongPut.isSelected())                       strategies.add("LONG_PUT");
        if (strategyZeroDte.isSelected())                       strategies.add("ZERO_DTE");
        if (strategyOpeningBreakout.isSelected())               strategies.add("OPENING_BREAKOUT");
        if (strategyStochasticReversal.isSelected())            strategies.add("STOCHASTIC_REVERSAL");
        if (strategyRelativeStrengthDivergence.isSelected())    strategies.add("RELATIVE_STRENGTH_DIVERGENCE");
        if (strategyMacdCrossover.isSelected())                 strategies.add("MACD_CROSSOVER");
        cfg.setEnabledStrategies(strategies);
        try {
            int pct = Integer.parseInt(optionsStopLossField.getText().strip());
            cfg.setOptionsStopLossFrac(Math.min(90, Math.max(10, pct)) / 100.0);
        } catch (NumberFormatException ignored) {}
        try {
            int n = Integer.parseInt(downtrendPutMinSignalsField.getText().strip());
            cfg.setDowntrendPutMinSignals(Math.min(6, Math.max(1, n)));
        } catch (NumberFormatException ignored) {}
        String startTimeText = entryStartTimeField.getText().strip();
        if (!startTimeText.isBlank()) {
            try { cfg.setOptionsEntryStartTime(LocalTime.parse(startTimeText)); }
            catch (DateTimeParseException ignored) {}
        }
        String cutoffText = entryCutoffField.getText().strip();
        if (!cutoffText.isBlank()) {
            try { cfg.setOptionsEntryCutoff(LocalTime.parse(cutoffText)); }
            catch (DateTimeParseException ignored) {}
        }
        try {
            double pt = Double.parseDouble(profitTargetField.getText().strip());
            cfg.setProfitTarget(Math.max(1.1, pt));
        } catch (NumberFormatException ignored) {}
        try {
            int n = Integer.parseInt(reversalMinSignalsField.getText().strip());
            cfg.setReversalMinSignals(Math.min(6, Math.max(1, n)));
        } catch (NumberFormatException ignored) {}
        try {
            int n = Integer.parseInt(entryConfirmationTicksField.getText().strip());
            cfg.setEntryConfirmationTicks(Math.max(1, n));
        } catch (NumberFormatException ignored) {}
        try {
            int pct = Integer.parseInt(overnightFloorField.getText().strip());
            cfg.setOvernightMinPremiumFrac(Math.min(100, Math.max(0, pct)) / 100.0);
        } catch (NumberFormatException ignored) {}
        cfg.setOptionsCallsDisabled(parseSymbolSet(callsDisabledField.getText()));
        cfg.setOptionsPutsDisabled(parseSymbolSet(putsDisabledField.getText()));
        try {
            double pct = Double.parseDouble(trailingStopField.getText().strip());
            cfg.setTrailingStopPct(Math.min(15, Math.max(1, pct)) / 100.0);
        } catch (NumberFormatException ignored) {}
        try {
            double pct = Double.parseDouble(maxLossPerTradeField.getText().strip());
            cfg.setMaxLossPerTradePct(Math.min(1.0, Math.max(0.1, pct)) / 100.0);
        } catch (NumberFormatException ignored) {}
        try {
            double pct = Double.parseDouble(circuitBreakerField.getText().strip());
            cfg.setCircuitBreakerPct(Math.min(10, Math.max(0, pct)) / 100.0);
        } catch (NumberFormatException ignored) {}
        cfg.setStockWatchlist(new java.util.ArrayList<>(parseSymbolSet(stockWatchlistArea.getText())));
        Set<String> optWatchlist = parseSymbolSet(optionsWatchlistArea.getText());
        cfg.setOptionsWatchlist(new java.util.ArrayList<>(optWatchlist));
        cfg.setOptionsSymbolAllowlist(optWatchlist);
        return cfg;
    }

    private Set<String> parseSymbolSet(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return result;
        for (String s : raw.split("[,\\s]+")) {
            String sym = s.strip().toUpperCase();
            if (!sym.isEmpty()) result.add(sym);
        }
        return result;
    }

    private void updateStockStrategyCheckboxStates() {
        boolean enabled = stockTradingEnabledCheck.isSelected();
        strategyOpeningBreakout.setDisable(!enabled);
        strategyRelativeStrengthDivergence.setDisable(!enabled);
    }

    private void updateOptionsStrategyCheckboxStates() {
        boolean enabled = optionsTradingEnabledCheck.isSelected();
        strategyHighDeltaScalp.setDisable(!enabled);
        strategyMomentumNearTerm.setDisable(!enabled);
        strategyLongCall.setDisable(!enabled);
        strategyLongPut.setDisable(!enabled);
        strategyZeroDte.setDisable(!enabled);
        strategyStochasticReversal.setDisable(!enabled);
        strategyMacdCrossover.setDisable(!enabled);
    }

    private void updatePremiumStrategyCheckboxStates() {
        boolean enabled = premiumSellerEnabledCheck.isSelected();
        premiumPcsCheck.setDisable(!enabled);
        premiumCcsCheck.setDisable(!enabled);
        premiumMaxContractsField.setDisable(!enabled);
        premiumMinEntryField.setDisable(!enabled);
        premiumMaxSpreadsField.setDisable(!enabled);
        premiumSectorCapField.setDisable(!enabled);
        premiumPcsMacdCheck.setDisable(!enabled);
        premiumCcsSellSignalCheck.setDisable(!enabled);
        premiumShortExpiryCheck.setDisable(!enabled);
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
