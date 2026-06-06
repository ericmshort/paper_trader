package com.tradingapp.ui;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.SafetyStop;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.broker.AlpacaBroker;
import com.tradingapp.broker.AlpacaQuoteProvider;
import com.tradingapp.broker.AlpacaWebSocketFreeProvider;
import com.tradingapp.broker.AppConfig;
import com.tradingapp.data.CandleHistory;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.EarningsCalendar;
import com.tradingapp.data.LargeCapWatchList;
import com.tradingapp.data.SmallCapWatchList;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.data.YahooFinanceQuoteProvider;
import com.tradingapp.ai.MLSignalEvaluator;
import com.tradingapp.ai.SignalWeights;
import com.tradingapp.engine.*;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.function.Consumer;

public class DashboardController implements Initializable {

    @FXML private Label totalPortfolioLabel;
    @FXML private Label stockHoldingsLabel;
    @FXML private Label optionHoldingsLabel;
    @FXML private Label availableCashLabel;
    @FXML private Label optionsCashDeployedLabel;
    @FXML private Label haltedLabel;
    @FXML private Label winsLabel;
    @FXML private Label lossesLabel;
    @FXML private Label winRateLabel;
    @FXML private Button pnlButton;
    @FXML private Label unrealizedPnlLabel;
    @FXML private Label optionsTotalUnrealizedLabel;
    @FXML private Label stockTotalUnrealizedLabel;
    @FXML private LineChart<Number, Number> equityChart;
    @FXML private TableView<OptionsPositionRow> optionsTable;
    @FXML private TableColumn<OptionsPositionRow, String> optColSymbol;
    @FXML private TableColumn<OptionsPositionRow, String> optColType;
    @FXML private TableColumn<OptionsPositionRow, String> optColStrike;
    @FXML private TableColumn<OptionsPositionRow, String> optColExpiry;
    @FXML private TableColumn<OptionsPositionRow, Integer> optColContracts;
    @FXML private TableColumn<OptionsPositionRow, String> optColCost;
    @FXML private TableColumn<OptionsPositionRow, String> optColCurrentValue;
    @FXML private TableView<StockPositionRow> stockPositionsTable;
    @FXML private TableColumn<StockPositionRow, String> stkColSymbol;
    @FXML private TableColumn<StockPositionRow, Integer> stkColQuantity;
    @FXML private TableColumn<StockPositionRow, String> stkColAvgCost;
    @FXML private TableColumn<StockPositionRow, String> stkColCurrentPrice;
    @FXML private TableColumn<StockPositionRow, String> stkColMarketValue;
    @FXML private TableColumn<StockPositionRow, String> stkColUnrealizedPnl;
    @FXML private TextArea researchArea;
    @FXML private TableView<TransactionRecord> tradeHistoryTable;
    @FXML private TableColumn<TransactionRecord, Long> colTimestamp;
    @FXML private TableColumn<TransactionRecord, String> colSymbol;
    @FXML private TableColumn<TransactionRecord, String> colAction;
    @FXML private TableColumn<TransactionRecord, Integer> colQuantity;
    @FXML private TableColumn<TransactionRecord, Double> colPrice;
    @FXML private TableColumn<TransactionRecord, Double> colFee;
    @FXML private TableColumn<TransactionRecord, Double> colBalance;
    @FXML private TableColumn<TransactionRecord, String> colReason;

    @FXML private SettingsController settingsPanelController;
    @FXML private BacktestController backtestPanelController;

    private Account account;
    private TransactionLog transactionLog;
    private PriceHistory priceHistory;
    private CandleHistory candleHistory;
    private BlackScholesEngine bsEngine;
    private ScheduledExecutorService scheduler;
    private TradingLoop tradingLoop;
    private OptionsSignalRouter optionsRouter;
    private AlpacaWebSocketFreeProvider wsProvider;
    private XYChart.Series<Number, Number> equitySeries;
    private int tickCount = 0;
    private boolean alpacaMode = false;

    private static final Logger LOG = Logger.getLogger(DashboardController.class.getName());
    private final TradingLogger tradingLogger = new TradingLogger();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        account = new Account();
        transactionLog = new TransactionLog();
        transactionLog.restoreAccount(account);
        priceHistory = new PriceHistory();
        candleHistory = new CandleHistory();
        bsEngine = new BlackScholesEngine();

        equitySeries = new XYChart.Series<>();
        equityChart.getData().add(equitySeries);

        setupTableColumns();
        setupOptionsTableColumns();
        setupStockTableColumns();

        refreshUi();

        AppConfig appConfig = AppConfig.load();
        startTradingComponents(appConfig);

        if (settingsPanelController != null) {
            settingsPanelController.setActiveBrokerType(appConfig.getBrokerType());
            settingsPanelController.setOnSettingsSaved(cfg -> {
                if (tradingLoop != null) {
                    tradingLoop.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
                    tradingLoop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
                    tradingLoop.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
                    tradingLoop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                    tradingLoop.setEarningsBlackoutDays(cfg.getEarningsBlackoutDays());
                }
                if (optionsRouter != null) {
                    optionsRouter.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
                    optionsRouter.setEnabledStrategies(cfg.getEnabledStrategies());
                }
                Platform.runLater(() -> researchArea.appendText(
                        "\nRisk settings updated (effective next tick). Broker/quote changes take effect on next restart.\n"));
            });
            settingsPanelController.setOnBrokerReset(this::handleBrokerReset);
        }
    }

    private void startTradingComponents(AppConfig appConfig) {
        boolean useWsProvider = appConfig.getQuoteProviderType() == AppConfig.QuoteProviderType.ALPACA_WEBSOCKET_FREE
                && appConfig.isAlpacaBroker();

        researchArea.setText("Waiting for market data...\n\nMarket hours: 9:30 AM – 4:00 PM ET"
                + "\nWatching " + (useWsProvider ? "30 most-liquid day-trading symbols" : "100 large-cap and small-cap US stocks") + "."
                + "\nBroker: " + SettingsController.brokerTypeLabel(appConfig.getBrokerType())
                + " | Quotes: " + appConfig.getQuoteProviderType().name());

        QuoteProvider quoteProvider;
        if (useWsProvider) {
            wsProvider = new AlpacaWebSocketFreeProvider(appConfig, candleHistory,
                    msg -> Platform.runLater(() -> researchArea.appendText(msg + "\n")));
            wsProvider.start();
            quoteProvider = wsProvider;
        } else if (appConfig.getQuoteProviderType() == AppConfig.QuoteProviderType.ALPACA && appConfig.isAlpacaBroker()) {
            wsProvider = null;
            quoteProvider = new AlpacaQuoteProvider(appConfig);
        } else {
            wsProvider = null;
            quoteProvider = new YahooFinanceQuoteProvider();
        }

        if (backtestPanelController != null) {
            backtestPanelController.setContext(appConfig, quoteProvider);
        }

        FeeCalculator feeCalc = new FeeCalculator();
        alpacaMode = appConfig.isAlpacaBroker();
        BrokerClient brokerClient;
        if (appConfig.isAlpacaBroker()) {
            AlpacaBroker alpaca = new AlpacaBroker(appConfig, account, transactionLog);
            alpaca.syncAccount(account);
            alpaca.reconcileTransactionLog();
            brokerClient = alpaca;
            refreshUi();
        } else {
            SafetyStop safetyStop = new SafetyStop(account);
            OrderExecutor orderExecutor = new OrderExecutor(account, safetyStop, transactionLog, feeCalc);
            brokerClient = new SimulatedBroker(orderExecutor);
        }

        Consumer<String> researchCb = msg -> {
            Platform.runLater(() -> researchArea.appendText(msg + "\n"));
            // Quote lines: "HH:mm | SYMBOL $price | ... | BUY=n SELL=n" — skip those
            if (!msg.contains("| BUY=")) {
                tradingLogger.log(msg);
            }
        };
        Runnable uiRefresh = () -> Platform.runLater(this::refreshUi);

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, transactionLog,
                appConfig.isAlpacaBroker()
                        ? (AlpacaBroker) brokerClient
                        : null);
        optionsRouter = new OptionsSignalRouter(
                bsEngine, optExec, account, priceHistory, researchCb, quoteProvider);

        Path weightsPath = Path.of(System.getProperty("user.home"), ".tradingapp", "signal-weights.json");
        SignalWeights initialWeights;
        try {
            initialWeights = Files.exists(weightsPath) ? SignalWeights.load(weightsPath) : new SignalWeights();
        } catch (IOException e) {
            initialWeights = new SignalWeights();
        }
        MLSignalEvaluator mlEval = new MLSignalEvaluator(initialWeights, weightsPath);
        // Auto-retraining disabled: early trade history was corrupted by the options position
        // key-mismatch bug (excessive contract accumulation). Re-enable once we have a clean
        // history of at least 50 trades placed under correct conditions.
        Runnable trainingCallback = null;

        EarningsCalendar earningsCalendar = new EarningsCalendar();

        List<String> allSymbols;
        if (useWsProvider) {
            allSymbols = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        } else {
            allSymbols = new ArrayList<>();
            allSymbols.addAll(LargeCapWatchList.SYMBOLS);
            allSymbols.addAll(SmallCapWatchList.SYMBOLS);
        }

        tradingLoop = new TradingLoop(quoteProvider, priceHistory,
                new IndicatorEngine(), new TrailingStopMonitor(), brokerClient, feeCalc,
                allSymbols, researchCb, uiRefresh, account,
                optionsRouter, mlEval, trainingCallback);
        tradingLoop.setTransactionLog(transactionLog);
        if (useWsProvider) {
            tradingLoop.setCandleHistory(candleHistory);
        }
        tradingLoop.setDailyLossLimitPct(appConfig.getDailyLossLimitPct() / 100.0);
        tradingLoop.setMaxPortfolioExposure(appConfig.getMaxPortfolioExposurePct() / 100.0);
        optionsRouter.setMaxPortfolioExposure(appConfig.getMaxPortfolioExposurePct() / 100.0);
        optionsRouter.setEnabledStrategies(appConfig.getEnabledStrategies());
        tradingLoop.setAvoidOvernightHolds(appConfig.isAvoidOvernightHolds());
        tradingLoop.setMarketRegimeFilterEnabled(appConfig.isMarketRegimeFilterEnabled());
        tradingLoop.setEarningsCalendar(earningsCalendar);
        tradingLoop.setEarningsBlackoutDays(appConfig.getEarningsBlackoutDays());
        optionsRouter.setUptrendSupplier(tradingLoop::isUptrend);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trading-loop");
            t.setDaemon(true);
            return t;
        });
        // Day trading: 5s interval so candle data and signals are evaluated frequently
        scheduler.scheduleAtFixedRate(tradingLoop, 0, 5, TimeUnit.SECONDS);

        // Seed daily price history for RSI / Bollinger baseline.
        // WebSocket provider seeds from Alpaca REST; others use Yahoo.
        QuoteProvider seedProvider = useWsProvider ? wsProvider : new YahooFinanceQuoteProvider();
        long seedDelayMs = useWsProvider ? 300 : 120; // Alpaca REST is faster, fewer symbols too
        Thread seedThread = new Thread(() -> {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(280);
            int seeded = 0;
            List<String> symbolsToSeed = new ArrayList<>(allSymbols);
            if (!symbolsToSeed.contains("SPY")) symbolsToSeed.add("SPY");
            for (String sym : symbolsToSeed) {
                try {
                    var bars = seedProvider.getHistoricalBars(sym, start, end);
                    if (bars != null && !bars.isEmpty()) {
                        priceHistory.seed(sym, bars);
                        seeded++;
                    }
                    Thread.sleep(seedDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Non-fatal: symbol will build history organically from live ticks
                }
            }
            int finalSeeded = seeded;
            Platform.runLater(() -> researchCb.accept(
                    "Price history seeded for " + finalSeeded + "/" + symbolsToSeed.size() + " symbols (incl. SPY regime data)."));
        }, "price-history-seed");
        seedThread.setDaemon(true);
        seedThread.start();
    }

    private void handleBrokerReset(AppConfig newConfig) {
        Platform.runLater(() -> {
            // Stop the current trading loop
            if (scheduler != null) {
                scheduler.shutdownNow();
                try { scheduler.awaitTermination(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }

            // Wipe all historical data
            transactionLog.clearAll();

            // Reset account state
            if (newConfig.isAlpacaBroker()) {
                account.reset(0.0);
            } else {
                account.reset(100_000.0);
            }

            // Stop WebSocket if running
            if (wsProvider != null) {
                wsProvider.stop();
                wsProvider = null;
            }

            // Reset chart and price history
            priceHistory = new PriceHistory();
            candleHistory = new CandleHistory();
            equitySeries.getData().clear();
            tickCount = 0;

            // Refresh UI to show cleared state
            refreshUi();
            researchArea.setText("Broker switched to "
                    + SettingsController.brokerTypeLabel(newConfig.getBrokerType())
                    + ". Historical data cleared.\n"
                    + "Waiting for market data...\n");

            // Restart the trading loop with new config
            startTradingComponents(newConfig);

            // Update settings controller so it knows the new active broker
            if (settingsPanelController != null) {
                settingsPanelController.setActiveBrokerType(newConfig.getBrokerType());
            }
        });
    }

    private void setupTableColumns() {
        colTimestamp.setCellValueFactory(new PropertyValueFactory<>("timestamp"));
        colTimestamp.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Long ts, boolean empty) {
                super.updateItem(ts, empty);
                setText(empty || ts == null ? null : TIME_FMT.format(Instant.ofEpochMilli(ts)));
            }
        });

        colSymbol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        colAction.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(
                cd.getValue().getAction() == null ? "" : cd.getValue().getAction().name()));
        colAction.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String action, boolean empty) {
                super.updateItem(action, empty);
                if (empty || action == null) { setText(null); setStyle(""); return; }
                String label = formatAction(action);
                boolean isBuy = label.contains("BUY") || label.contains("OPEN");
                setText(label);
                setStyle(isBuy
                        ? "-fx-text-fill: #00ff88; -fx-font-weight: bold;"
                        : "-fx-text-fill: #ff4444; -fx-font-weight: bold;");
            }
        });
        colQuantity.setCellValueFactory(new PropertyValueFactory<>("quantity"));

        colPrice.setCellValueFactory(new PropertyValueFactory<>("pricePerUnit"));
        colPrice.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double price, boolean empty) {
                super.updateItem(price, empty);
                setText(empty || price == null ? null : String.format("$%.2f", price));
            }
        });

        colFee.setCellValueFactory(new PropertyValueFactory<>("feeCharged"));
        colFee.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double fee, boolean empty) {
                super.updateItem(fee, empty);
                setText(empty || fee == null ? null : String.format("$%.2f", fee));
            }
        });

        colBalance.setCellValueFactory(new PropertyValueFactory<>("balanceAfter"));
        colBalance.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double balance, boolean empty) {
                super.updateItem(balance, empty);
                setText(empty || balance == null ? null : String.format("$%,.2f", balance));
            }
        });

        colReason.setCellValueFactory(new PropertyValueFactory<>("reason"));
    }

    private void setupOptionsTableColumns() {
        optColSymbol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        optColType.setCellValueFactory(new PropertyValueFactory<>("type"));
        optColStrike.setCellValueFactory(new PropertyValueFactory<>("strike"));
        optColExpiry.setCellValueFactory(new PropertyValueFactory<>("expiry"));
        optColContracts.setCellValueFactory(new PropertyValueFactory<>("contracts"));
        optColCost.setCellValueFactory(new PropertyValueFactory<>("cost"));
        optColCurrentValue.setCellValueFactory(new PropertyValueFactory<>("currentValue"));
    }

    private void setupStockTableColumns() {
        stkColSymbol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        stkColQuantity.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        stkColAvgCost.setCellValueFactory(new PropertyValueFactory<>("avgCost"));
        stkColCurrentPrice.setCellValueFactory(new PropertyValueFactory<>("currentPrice"));
        stkColMarketValue.setCellValueFactory(new PropertyValueFactory<>("marketValue"));
        stkColUnrealizedPnl.setCellValueFactory(new PropertyValueFactory<>("unrealizedPnl"));
    }

    private double populateOptionsTable() {
        List<OptionsPositionRow> rows = new ArrayList<>();
        double totalUnrealized = 0.0;

        // Group legs that belong to the same multi-leg strategy into one display row
        Map<String, List<Map.Entry<String, OptionsPosition>>> groups = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, OptionsPosition> entry : account.getOptionsPositions().entrySet()) {
            String gk = strategyGroupKey(entry.getKey(), entry.getValue().getSymbol());
            groups.computeIfAbsent(gk, k -> new ArrayList<>()).add(entry);
        }

        for (List<Map.Entry<String, OptionsPosition>> legs : groups.values()) {
            if (legs.size() == 1) {
                OptionsPosition pos = legs.get(0).getValue();
                double currentPrem = computeOptionCurrentPremium(pos);
                double costRaw = -pos.getPremiumPaid() * 100.0 * pos.getContracts();
                double currentValueRaw = currentPrem * 100.0 * pos.getContracts();
                totalUnrealized += costRaw + currentValueRaw;
                rows.add(new OptionsPositionRow(
                        pos.getSymbol(), pos.getType(),
                        String.format("$%.2f", pos.getStrike()),
                        pos.getExpiry().toString(),
                        Math.abs(pos.getContracts()), costRaw, currentValueRaw));
            } else {
                OptionsPosition first = legs.get(0).getValue();
                String symbol  = first.getSymbol();
                String type    = strategyLabel(legs.get(0).getKey(), symbol);
                String strike  = legs.stream()
                        .map(e -> e.getValue().getStrike())
                        .distinct().sorted()
                        .map(k -> String.format("$%.0f", k))
                        .collect(java.util.stream.Collectors.joining("/"));
                String expiry  = legs.stream()
                        .map(e -> e.getValue().getExpiry())
                        .min(Comparator.naturalOrder())
                        .map(Object::toString).orElse("");
                int contracts  = Math.abs(first.getContracts());
                double costRaw = 0.0, currentValueRaw = 0.0;
                for (Map.Entry<String, OptionsPosition> e : legs) {
                    OptionsPosition pos = e.getValue();
                    double currentPrem = computeOptionCurrentPremium(pos);
                    costRaw         += -pos.getPremiumPaid() * 100.0 * pos.getContracts();
                    currentValueRaw += currentPrem * 100.0 * pos.getContracts();
                }
                totalUnrealized += costRaw + currentValueRaw;
                rows.add(new OptionsPositionRow(symbol, type, strike, expiry,
                        contracts, costRaw, currentValueRaw));
            }
        }

        optionsTable.setItems(FXCollections.observableArrayList(rows));
        return totalUnrealized;
    }

    /**
     * Returns the group key for a position by stripping the leg-type suffix.
     * Standalone "SYMBOL_CALL" / "SYMBOL_PUT" keys are returned unchanged so they
     * never merge with each other or with multi-leg legs.
     */
    private static String strategyGroupKey(String posKey, String symbol) {
        if (posKey.equals(symbol + "_CALL") || posKey.equals(symbol + "_PUT")) return posKey;
        for (String suffix : new String[]{"_SHORTCALL", "_LONGCALL", "_SHORTPUT", "_LONGPUT",
                                          "_SHORT", "_LONG", "_CALL", "_PUT"}) {
            if (posKey.endsWith(suffix)) return posKey.substring(0, posKey.length() - suffix.length());
        }
        return posKey;
    }

    private static String strategyLabel(String posKey, String symbol) {
        String root = strategyGroupKey(posKey, symbol);
        String name = root.startsWith(symbol + "_") ? root.substring(symbol.length() + 1) : root;
        return switch (name) {
            case "IRONCONDOR"    -> "Iron Condor";
            case "STRADDLE"      -> "Straddle";
            case "STRANGLE"      -> "Strangle";
            case "BULLPUTSPREAD" -> "Bull Put Spread";
            case "BEARCALLSPREAD"-> "Bear Call Spread";
            case "ZEROTE"        -> "Zero DTE";
            case "HIGHDELTA"     -> "High Delta";
            case "NEARTERM"      -> "Near Term";
            default              -> name;
        };
    }

    private double populateStockTable() {
        List<StockPositionRow> rows = new ArrayList<>();
        double totalUnrealized = 0.0;
        for (Position pos : account.getPositions().values()) {
            List<Double> prices = priceHistory.getPrices(pos.getSymbol());
            double currentPrice = prices.isEmpty() ? pos.getAverageCost() : prices.get(prices.size() - 1);
            totalUnrealized += (currentPrice - pos.getAverageCost()) * pos.getQuantity();
            rows.add(new StockPositionRow(pos.getSymbol(), pos.getQuantity(), pos.getAverageCost(), currentPrice));
        }
        stockPositionsTable.setItems(FXCollections.observableArrayList(rows));
        return totalUnrealized;
    }

    private String formatUnrealizedPnl(String prefix, double pnl) {
        return pnl >= 0
                ? String.format("%s: +$%,.2f", prefix, pnl)
                : String.format("%s: -$%,.2f", prefix, Math.abs(pnl));
    }

    private double computeStockHoldings() {
        double total = 0.0;
        for (var entry : account.getPositions().entrySet()) {
            List<Double> prices = priceHistory.getPrices(entry.getKey());
            double price = prices.isEmpty() ? entry.getValue().getAverageCost() : prices.get(prices.size() - 1);
            total += entry.getValue().getQuantity() * price;
        }
        return total;
    }

    private double computeOptionCurrentPremium(OptionsPosition pos) {
        // Use the most recent intraday tick for the spot price, falling back to daily
        List<Double> intradayPrices = priceHistory.getPrices(pos.getSymbol());
        List<Double> dailyPrices    = priceHistory.getDailyPrices(pos.getSymbol());

        // Need a spot price — prefer the freshest intraday tick
        double S;
        if (!intradayPrices.isEmpty()) {
            S = intradayPrices.get(intradayPrices.size() - 1);
        } else if (!dailyPrices.isEmpty()) {
            S = dailyPrices.get(dailyPrices.size() - 1);
        } else {
            return pos.getPremiumPaid();
        }

        double T = bsEngine.timeToExpiry(pos.getExpiry());
        if (T <= 0) return 0.0;

        // Vol must come from DAILY returns — sqrt(252) annualization is correct only for
        // daily prices. Intraday 30-second ticks would need sqrt(252*780) and would
        // still be noisy; daily bars seeded from Yahoo are the right input.
        double vol = bsEngine.historicalVol(dailyPrices.isEmpty() ? intradayPrices : dailyPrices);
        if (!Double.isFinite(vol) || vol == 0) return pos.getPremiumPaid();

        double K = pos.getStrike();
        double r = 0.05;
        return "CALL".equals(pos.getType())
                ? bsEngine.callPrice(S, K, r, T, vol)
                : bsEngine.putPrice(S, K, r, T, vol);
    }

    private double computeOptionHoldings() {
        double total = 0.0;
        for (OptionsPosition pos : account.getOptionsPositions().values()) {
            total += pos.getCurrentValue(computeOptionCurrentPremium(pos));
        }
        return total;
    }

    private double computeStockUnrealizedPnL() {
        double total = 0.0;
        for (var entry : account.getPositions().entrySet()) {
            Position pos = entry.getValue();
            List<Double> prices = priceHistory.getPrices(entry.getKey());
            double currentPrice = prices.isEmpty() ? pos.getAverageCost() : prices.get(prices.size() - 1);
            total += (currentPrice - pos.getAverageCost()) * pos.getQuantity();
        }
        return total;
    }

    private double computeOptionsUnrealizedPnL() {
        double total = 0.0;
        for (OptionsPosition pos : account.getOptionsPositions().values()) {
            total += (computeOptionCurrentPremium(pos) - pos.getPremiumPaid()) * 100 * pos.getContracts();
        }
        return total;
    }

    private double computeOptionsCashDeployed() {
        return account.getOptionsPositions().values().stream()
                .filter(p -> p.getContracts() > 0)
                .mapToDouble(p -> p.getPremiumPaid() * 100 * p.getContracts())
                .sum();
    }

    private void refreshUi() {
        List<TransactionRecord> history = collapseMultiLegHistory(transactionLog.findAll());
        tradeHistoryTable.setItems(FXCollections.observableArrayList(history));

        double availableCash = account.getBalance();
        double stockHoldings = computeStockHoldings();
        double optionHoldings = computeOptionHoldings();
        double totalPortfolio = availableCash + stockHoldings + optionHoldings;

        totalPortfolioLabel.setText(String.format("Total Portfolio: $%,.2f", totalPortfolio));
        stockHoldingsLabel.setText(String.format("Stocks: $%,.2f", stockHoldings));
        optionHoldingsLabel.setText(String.format("Options: $%,.2f", optionHoldings));
        availableCashLabel.setText(String.format("Cash: $%,.2f", availableCash));
        optionsCashDeployedLabel.setText(String.format("Options Reserved: $%,.2f", computeOptionsCashDeployed()));

        double optTotalUnrealized = populateOptionsTable();
        double stkTotalUnrealized = populateStockTable();
        unrealizedPnlLabel.setText(formatUnrealizedPnl("Unrealized P&L", optTotalUnrealized + stkTotalUnrealized));
        optionsTotalUnrealizedLabel.setText(String.format("Market Value: $%,.2f", optionHoldings));
        stockTotalUnrealizedLabel.setText(formatUnrealizedPnl("Total Unrealized P&L", stkTotalUnrealized));

        List<ClosedTradeRecord> closedTrades = computeClosedTrades();
        int wins = (int) closedTrades.stream().filter(t -> t.getPnlRaw() >= 0).count();
        int losses = (int) closedTrades.stream().filter(t -> t.getPnlRaw() < 0).count();
        int total = wins + losses;
        double winRate = total > 0 ? (wins * 100.0 / total) : 0.0;
        winsLabel.setText("Wins: " + wins);
        lossesLabel.setText("Losses: " + losses);
        winRateLabel.setText(String.format("Win Rate: %.1f%%", winRate));
        double realizedPnl = closedTrades.stream().mapToDouble(ClosedTradeRecord::getPnlRaw).sum();
        pnlButton.setText(String.format("P&L: $%,.2f", realizedPnl));

        equitySeries.getData().add(new XYChart.Data<>(tickCount++, totalPortfolio));
        if (equitySeries.getData().size() > 200) {
            equitySeries.getData().remove(0);
        }

        if (account.isTradingHalted()) {
            haltedLabel.setText("⛔ TRADING HALTED — portfolio exhausted");
        } else {
            haltedLabel.setText("");
        }
    }

    public void refreshBalance() {
        double availableCash = account.getBalance();
        double stockHoldings = computeStockHoldings();
        double optionHoldings = computeOptionHoldings();
        double totalPortfolio = availableCash + stockHoldings + optionHoldings;

        totalPortfolioLabel.setText(String.format("Total Portfolio: $%,.2f", totalPortfolio));
        stockHoldingsLabel.setText(String.format("Stocks: $%,.2f", stockHoldings));
        optionHoldingsLabel.setText(String.format("Options: $%,.2f", optionHoldings));
        availableCashLabel.setText(String.format("Cash: $%,.2f", availableCash));
        optionsCashDeployedLabel.setText(String.format("Options Reserved: $%,.2f", computeOptionsCashDeployed()));
        double unrealizedPnl = computeStockUnrealizedPnL() + computeOptionsUnrealizedPnL();
        double realizedPnl = computeClosedTrades().stream().mapToDouble(ClosedTradeRecord::getPnlRaw).sum();
        pnlButton.setText(String.format("P&L: $%,.2f", realizedPnl));
        unrealizedPnlLabel.setText(formatUnrealizedPnl("Unrealized P&L", unrealizedPnl));

        if (account.isTradingHalted()) {
            haltedLabel.setText("⛔ TRADING HALTED — portfolio exhausted");
        } else {
            haltedLabel.setText("");
        }
    }

    @FXML
    private void onPnlClicked() {
        showPnlBreakdown();
    }

    private List<ClosedTradeRecord> computeClosedTrades() {
        List<TransactionRecord> allRecords = transactionLog.findAll();
        List<TransactionRecord> records = new ArrayList<>(allRecords);
        Collections.reverse(records); // replay chronologically

        Map<String, Integer> openShares = new HashMap<>();
        Map<String, Double> avgCost = new HashMap<>();
        Map<String, double[]> openLongPremiums  = new HashMap<>(); // long opens: key -> {premium, contracts}
        Map<String, double[]> openShortPremiums = new HashMap<>(); // short opens: key -> {premium, contracts}
        List<ClosedTradeRecord> closed = new ArrayList<>();
        Set<String> processedGroupIds = new HashSet<>(); // tracks which mleg close groups have had P&L assigned

        for (TransactionRecord r : records) {
            switch (r.getAction()) {
                case BUY -> {
                    int prev = openShares.getOrDefault(r.getSymbol(), 0);
                    double prevAvg = avgCost.getOrDefault(r.getSymbol(), 0.0);
                    int newTotal = prev + r.getQuantity();
                    // Include buy fee in cost basis so it's reflected in the sell P&L
                    double newAvg = newTotal > 0
                            ? (prev * prevAvg + r.getQuantity() * r.getPricePerUnit() + r.getFeeCharged()) / newTotal
                            : r.getPricePerUnit() + r.getFeeCharged() / r.getQuantity();
                    openShares.put(r.getSymbol(), newTotal);
                    avgCost.put(r.getSymbol(), newAvg);
                }
                case SELL -> {
                    double entry = avgCost.getOrDefault(r.getSymbol(), r.getPricePerUnit());
                    double pnl = (r.getPricePerUnit() - entry) * r.getQuantity() - r.getFeeCharged();
                    closed.add(new ClosedTradeRecord(r.getSymbol(), "Stock", r.getQuantity(),
                            entry, r.getPricePerUnit(), pnl, r.getTimestamp()));
                    int remaining = openShares.getOrDefault(r.getSymbol(), 0) - r.getQuantity();
                    if (remaining <= 0) {
                        openShares.remove(r.getSymbol());
                        avgCost.remove(r.getSymbol());
                    } else {
                        openShares.put(r.getSymbol(), remaining);
                    }
                }
                case CALL_BUY -> accumulateOption(openLongPremiums, r, "_CALL");
                case PUT_BUY  -> accumulateOption(openLongPremiums, r, "_PUT");
                case CALL_SELL -> processOptionSell(r, "_CALL", "Call Option",
                        openLongPremiums, openShortPremiums, closed, processedGroupIds);
                case PUT_SELL  -> processOptionSell(r, "_PUT",  "Put Option",
                        openLongPremiums, openShortPremiums, closed, processedGroupIds);
            }
        }

        Collections.reverse(closed); // most recent first
        return mergeMultiLegClosedTrades(closed);
    }

    /** Accumulates multiple BUY records for the same option key into a weighted-average position. */
    private static void accumulateOption(Map<String, double[]> map, TransactionRecord r, String suffix) {
        double effectivePremium = r.getPricePerUnit() + r.getFeeCharged() / (r.getQuantity() * 100.0);
        double[] existing = map.getOrDefault(r.getSymbol() + suffix, new double[]{0.0, 0.0});
        double totalContracts = existing[1] + r.getQuantity();
        double weightedAvg = totalContracts > 0
                ? (existing[0] * existing[1] + effectivePremium * r.getQuantity()) / totalContracts
                : effectivePremium;
        map.put(r.getSymbol() + suffix, new double[]{weightedAvg, totalContracts});
    }

    /**
     * Handles a CALL_SELL or PUT_SELL record.
     *
     * Three distinct cases:
     * 1. Opening a short leg: reason contains "(SHORT)" → store as a short credit entry, no closed record yet.
     * 2. Closing a short leg: quantity < 0 → buying back a short; P&L = (openPrice - closePrice) × qty × 100.
     * 3. Closing a long leg: quantity > 0, no "(SHORT)" in reason → selling a long; P&L = (closePrice - openPrice) × qty × 100.
     *
     * For broker-submitted mleg closes (fee=0, groupId non-null), the per-leg prices may be corrupted by
     * fill-price sync writing the composite debit/credit across all legs. When the close reason contains
     * the original credit and close-cost, we use credit−cost as the net P&L for the first leg of each
     * group and 0 for the remaining legs, so mergeMultiLegClosedTrades() produces the correct total.
     */
    private static void processOptionSell(TransactionRecord r, String suffix, String type,
                                          Map<String, double[]> openLongPremiums,
                                          Map<String, double[]> openShortPremiums,
                                          List<ClosedTradeRecord> closed,
                                          Set<String> processedGroupIds) {
        String key = r.getSymbol() + suffix;
        boolean isShortOpen = r.getReason() != null && r.getReason().contains("(SHORT)");

        if (isShortOpen) {
            // Opening a short leg — store credit received; no closed record yet
            accumulateOption(openShortPremiums, r, suffix);
            return;
        }

        // For broker mleg closes, extract total P&L from reason to bypass corrupted per-leg prices.
        // Only apply when fee=0 (broker-submitted) to avoid affecting paper-trading records.
        String groupId = r.getGroupId();
        double reasonPnl = parseReasonPnl(r.getReason());
        boolean useReasonPnl = !Double.isNaN(reasonPnl) && groupId != null && r.getFeeCharged() == 0;

        if (r.getQuantity() < 0) {
            // Buying back a short (quantity is negative in the log for buy-to-close)
            int qty = Math.abs(r.getQuantity());
            double[] entry = openShortPremiums.getOrDefault(key, new double[]{r.getPricePerUnit(), qty});
            double pnl;
            if (useReasonPnl && !processedGroupIds.contains(groupId)) {
                pnl = reasonPnl; // total net P&L for this multi-leg group
                processedGroupIds.add(groupId);
            } else if (useReasonPnl) {
                pnl = 0; // P&L already recorded for this group's first close record
            } else {
                pnl = (entry[0] - r.getPricePerUnit()) * 100.0 * qty - r.getFeeCharged();
            }
            closed.add(new ClosedTradeRecord(r.getSymbol(), type + " (Short)", qty,
                    entry[0], r.getPricePerUnit(), pnl, r.getTimestamp(), r.getGroupId()));
            reduceOptionPosition(openShortPremiums, key, entry, qty);
        } else {
            // Selling a long leg
            double[] entry = openLongPremiums.getOrDefault(key, new double[]{r.getPricePerUnit(), r.getQuantity()});
            double pnl;
            if (useReasonPnl) {
                // P&L is captured on the first short-leg close for this multi-leg group
                pnl = 0;
            } else {
                pnl = (r.getPricePerUnit() - entry[0]) * 100.0 * r.getQuantity() - r.getFeeCharged();
            }
            closed.add(new ClosedTradeRecord(r.getSymbol(), type, r.getQuantity(),
                    entry[0], r.getPricePerUnit(), pnl, r.getTimestamp(), r.getGroupId()));
            reduceOptionPosition(openLongPremiums, key, entry, r.getQuantity());
        }
    }

    /**
     * Parses the net P&L (credit − close cost) from a multi-leg strategy close reason.
     * Handles both IC format ("close=X ... credit=Y") and spread format ("close cost=X ... credit=Y").
     * Returns NaN if the reason is not a recognizable credit strategy close.
     */
    private static double parseReasonPnl(String reason) {
        if (reason == null) return Double.NaN;
        int creditIdx = reason.indexOf("credit=");
        if (creditIdx < 0) return Double.NaN;
        double credit = parseReasonDouble(reason, creditIdx + 7);
        if (Double.isNaN(credit)) return Double.NaN;

        double closeCost;
        int costIdx = reason.indexOf("close cost=");
        if (costIdx >= 0) {
            closeCost = parseReasonDouble(reason, costIdx + 11);
        } else {
            int closeIdx = reason.indexOf("close=");
            if (closeIdx < 0) return Double.NaN;
            closeCost = parseReasonDouble(reason, closeIdx + 6);
        }
        return Double.isNaN(closeCost) ? Double.NaN : credit - closeCost;
    }

    private static double parseReasonDouble(String s, int start) {
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
        try { return Double.parseDouble(s.substring(start, end)); } catch (NumberFormatException e) { return Double.NaN; }
    }

    /**
     * Collapses pairs of multi-leg history records (same non-null groupId) into a single
     * combined TransactionRecord for display in the trade history table.
     * Records without a groupId are passed through unchanged.
     */
    private static List<TransactionRecord> collapseMultiLegHistory(List<TransactionRecord> records) {
        Map<String, TransactionRecord> pending = new java.util.LinkedHashMap<>();
        List<TransactionRecord> result = new ArrayList<>();

        for (TransactionRecord r : records) {
            if (r.getGroupId() == null) {
                result.add(r);
                continue;
            }
            TransactionRecord first = pending.remove(r.getGroupId());
            if (first == null) {
                pending.put(r.getGroupId(), r);
            } else {
                // Combine the two legs into one display row
                TransactionRecord combined = new TransactionRecord();
                combined.setTimestamp(Math.min(first.getTimestamp(), r.getTimestamp()));
                combined.setSymbol(first.getSymbol());
                combined.setAction(first.getAction()); // drives color: CALL_BUY=green, CALL_SELL=red
                combined.setQuantity(first.getQuantity());
                combined.setPricePerUnit(first.getPricePerUnit() + r.getPricePerUnit());
                combined.setFeeCharged(first.getFeeCharged() + r.getFeeCharged());
                // Balance after both legs: the record with the lower id is the later insert
                combined.setBalanceAfter(first.getId() > r.getId()
                        ? first.getBalanceAfter() : r.getBalanceAfter());
                combined.setReason(buildCombinedReason(first.getReason(), r.getReason()));
                combined.setSignals(first.getSignals());
                combined.setExternalId(first.getExternalId());
                combined.setGroupId(first.getGroupId());
                result.add(combined);
            }
        }
        // Any unmatched first legs (shouldn't happen normally) pass through as-is
        result.addAll(pending.values());
        result.sort(Comparator.comparingLong(TransactionRecord::getTimestamp).reversed());
        return result;
    }

    /** Merges pairs of ClosedTradeRecord entries that share a groupId into one combined row. */
    private static List<ClosedTradeRecord> mergeMultiLegClosedTrades(List<ClosedTradeRecord> records) {
        Map<String, ClosedTradeRecord> pending = new java.util.LinkedHashMap<>();
        List<ClosedTradeRecord> result = new ArrayList<>();

        for (ClosedTradeRecord r : records) {
            if (r.getGroupId() == null) {
                result.add(r);
                continue;
            }
            ClosedTradeRecord first = pending.remove(r.getGroupId());
            if (first == null) {
                pending.put(r.getGroupId(), r);
            } else {
                String type = first.getType().equals(r.getType()) ? "Credit Spread" : "Straddle/Strangle";
                result.add(new ClosedTradeRecord(
                        first.getSymbol(), type, first.getQuantity(),
                        first.getEntryRaw() + r.getEntryRaw(),
                        first.getExitRaw()  + r.getExitRaw(),
                        first.getPnlRaw()   + r.getPnlRaw(),
                        Math.min(first.getTimestampRaw(), r.getTimestampRaw())));
            }
        }
        result.addAll(pending.values());
        result.sort(Comparator.comparingLong(ClosedTradeRecord::getTimestampRaw).reversed());
        return result;
    }

    /** Builds a human-readable combined reason from two multi-leg leg reasons. */
    private static String buildCombinedReason(String r1, String r2) {
        if (r1 == null) return r2 != null ? r2 : "";
        if (r2 == null) return r1;
        if (r1.equals(r2)) return r1; // close reasons are identical — just use one
        // Open reasons: "STRADDLE CALL K=150.0 exp=..." + "STRADDLE PUT K=150.0 exp=..."
        // Extract strategy prefix (everything before " CALL" or " PUT")
        int split1 = indexOfLegKeyword(r1);
        int split2 = indexOfLegKeyword(r2);
        if (split1 > 0 && split2 > 0) {
            String strategy = r1.substring(0, split1).trim();
            String leg1 = r1.substring(split1).trim();
            String leg2 = r2.substring(split2).trim();
            return strategy + ": " + leg1 + " + " + leg2;
        }
        return r1 + " / " + r2;
    }

    private static int indexOfLegKeyword(String reason) {
        int i = reason.indexOf(" CALL K=");
        if (i >= 0) return i + 1;
        i = reason.indexOf(" PUT K=");
        if (i >= 0) return i + 1;
        return -1;
    }

    /** Maps a TransactionAction enum name to a human-readable display string. */
    private static String formatAction(String action) {
        return switch (action) {
            case "BUY"       -> "STOCK BUY";
            case "SELL"      -> "STOCK SELL";
            case "CALL_BUY"  -> "CALL BUY";
            case "CALL_SELL" -> "CALL SELL";
            case "PUT_BUY"   -> "PUT BUY";
            case "PUT_SELL"  -> "PUT SELL";
            default          -> action;
        };
    }

    /** Reduces an open option position after a partial or full close. */
    private static void reduceOptionPosition(Map<String, double[]> map, String key, double[] entry, int sellQty) {
        double remaining = entry[1] - sellQty;
        if (remaining <= 0) map.remove(key);
        else map.put(key, new double[]{entry[0], remaining});
    }

    private void showPnlBreakdown() {
        List<ClosedTradeRecord> trades = computeClosedTrades();

        TableView<ClosedTradeRecord> table = new TableView<>();
        table.setStyle("-fx-background-color: #1a1a2e;");

        TableColumn<ClosedTradeRecord, String> colTime = new TableColumn<>("Time");
        colTime.setCellValueFactory(new PropertyValueFactory<>("time"));
        colTime.setPrefWidth(100);

        TableColumn<ClosedTradeRecord, String> colSym = new TableColumn<>("Symbol");
        colSym.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        colSym.setPrefWidth(80);

        TableColumn<ClosedTradeRecord, String> colType = new TableColumn<>("Type");
        colType.setCellValueFactory(new PropertyValueFactory<>("type"));
        colType.setPrefWidth(95);

        TableColumn<ClosedTradeRecord, Integer> colQty = new TableColumn<>("Qty");
        colQty.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        colQty.setPrefWidth(55);

        TableColumn<ClosedTradeRecord, String> colEntry = new TableColumn<>("Entry Price");
        colEntry.setCellValueFactory(new PropertyValueFactory<>("entryPrice"));
        colEntry.setPrefWidth(110);

        TableColumn<ClosedTradeRecord, String> colExit = new TableColumn<>("Exit Price");
        colExit.setCellValueFactory(new PropertyValueFactory<>("exitPrice"));
        colExit.setPrefWidth(110);

        TableColumn<ClosedTradeRecord, String> colPnl = new TableColumn<>("P&L");
        colPnl.setCellValueFactory(new PropertyValueFactory<>("pnl"));
        colPnl.setPrefWidth(120);
        colPnl.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(value);
                    ClosedTradeRecord row = getTableView().getItems().get(getIndex());
                    setStyle(row.getPnlRaw() >= 0
                            ? "-fx-text-fill: #00ff88; -fx-font-weight: bold;"
                            : "-fx-text-fill: #ff4444; -fx-font-weight: bold;");
                }
            }
        });

        table.getColumns().addAll(colTime, colSym, colType, colQty, colEntry, colExit, colPnl);
        table.setItems(FXCollections.observableArrayList(trades));
        VBox.setVgrow(table, Priority.ALWAYS);

        double totalPnl = trades.stream().mapToDouble(ClosedTradeRecord::getPnlRaw).sum();
        String totalText = totalPnl >= 0
                ? String.format("Total Realised P&L:  +$%,.2f", totalPnl)
                : String.format("Total Realised P&L:  -$%,.2f", Math.abs(totalPnl));
        Label totalLabel = new Label(totalText);
        totalLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: "
                + (totalPnl >= 0 ? "#00ff88;" : "#ff4444;"));

        Label title = new Label("Realised P&L Breakdown");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #e0e0e0;");

        VBox root = new VBox(10, title, table, totalLabel);
        root.setStyle("-fx-background-color: #1a1a2e; -fx-padding: 15;");

        Scene scene = new Scene(root, 690, 500);
        Stage stage = new Stage();
        stage.setTitle("Realised P&L Breakdown");
        stage.setScene(scene);
        stage.show();
    }

    public void stop() {
        if (wsProvider != null) {
            wsProvider.stop();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Account getAccount() { return account; }
    public TransactionLog getTransactionLog() { return transactionLog; }
}
