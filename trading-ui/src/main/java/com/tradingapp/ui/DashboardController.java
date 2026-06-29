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
import com.tradingapp.data.HistoricalBarFetcher;
import com.tradingapp.data.LargeCapWatchList;
import com.tradingapp.data.SmallCapWatchList;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.data.YahooFinanceQuoteProvider;
import com.tradingapp.ai.MLSignalEvaluator;
import com.tradingapp.ai.SignalWeights;
import com.tradingapp.data.NewsSentimentCache;
import com.tradingapp.engine.*;
import com.tradingapp.sentiment.SentimentRefreshScheduler;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;
import com.tradingapp.options.PremiumSellerRouter;
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
import javafx.scene.paint.Color;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.function.Consumer;

public class DashboardController implements Initializable {

    @FXML private Label totalPortfolioLabel;
    @FXML private Label stockHoldingsLabel;
    @FXML private Label optionHoldingsLabel;
    @FXML private Label availableCashLabel;
    @FXML private Label optionsCashDeployedLabel;
    @FXML private Label haltedLabel;
    @FXML private Button resetDailyLossButton;
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
    @FXML private TableColumn<OptionsPositionRow, String> optColPnl;
    @FXML private TableView<PremiumSellerRow> premiumTable;
    @FXML private TableColumn<PremiumSellerRow, String> prmColSymbol;
    @FXML private TableColumn<PremiumSellerRow, String> prmColStrategy;
    @FXML private TableColumn<PremiumSellerRow, String> prmColLowStrike;
    @FXML private TableColumn<PremiumSellerRow, String> prmColHighStrike;
    @FXML private TableColumn<PremiumSellerRow, String> prmColCurrentPrice;
    @FXML private TableColumn<PremiumSellerRow, String> prmColExpiry;
    @FXML private TableColumn<PremiumSellerRow, String> prmColDte;
    @FXML private TableColumn<PremiumSellerRow, String> prmColPremium;
    @FXML private TableColumn<PremiumSellerRow, String> prmColMax;
    @FXML private TableColumn<PremiumSellerRow, String> prmColPnl;
    @FXML private TableColumn<PremiumSellerRow, String> prmColPct;
    @FXML private Label premiumTotalLabel;
    @FXML private TableView<StockPositionRow> stockPositionsTable;
    @FXML private TableColumn<StockPositionRow, String> stkColSymbol;
    @FXML private TableColumn<StockPositionRow, Integer> stkColQuantity;
    @FXML private TableColumn<StockPositionRow, String> stkColAvgCost;
    @FXML private TableColumn<StockPositionRow, String> stkColCurrentPrice;
    @FXML private TableColumn<StockPositionRow, String> stkColMarketValue;
    @FXML private TableColumn<StockPositionRow, String> stkColUnrealizedPnl;
    @FXML private TextArea signalsArea;
    @FXML private TextArea decisionsArea;
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
    private PremiumSellerRouter premiumSellerRouter;
    private AlpacaWebSocketFreeProvider wsProvider;
    private SentimentRefreshScheduler sentimentScheduler;
    private final NewsSentimentCache sentimentCache = new NewsSentimentCache();
    private XYChart.Series<Number, Number> equitySeries;
    private int tickCount = 0;
    private boolean alpacaMode = false;
    private final ConcurrentLinkedQueue<String> pendingSignals = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> pendingDecisions = new ConcurrentLinkedQueue<>();
    // Coalesces concurrent refresh requests: at most one applyUiSnapshot is queued in the FX
    // event loop at any time. The FX thread always applies the latest snapshot.
    private final AtomicReference<UiSnapshot> pendingSnapshot = new AtomicReference<>();
    private ScrollPane decisionsScrollPane;
    private boolean decisionsUserScrolledUp = false;
    private boolean suppressDecisionsScrollTracking = false;

    private static final Logger LOG = Logger.getLogger(DashboardController.class.getName());
    private final TradingLogger tradingLogger = new TradingLogger();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        account = new Account();
        transactionLog = new TransactionLog(
                AppConfig.getDataDir().resolve("transactions.db").toString());
        transactionLog.restoreAccount(account);
        priceHistory = new PriceHistory();
        candleHistory = new CandleHistory();
        bsEngine = new BlackScholesEngine();

        equitySeries = new XYChart.Series<>();
        equityChart.getData().add(equitySeries);

        setupTableColumns();
        setupOptionsTableColumns();
        setupStockTableColumns();
        setupPremiumTableColumns();

        // Track scroll position on the decisions panel so we don't auto-scroll while user reads.
        decisionsArea.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                ScrollPane sp = (ScrollPane) decisionsArea.lookup(".scroll-pane");
                if (sp != null) {
                    decisionsScrollPane = sp;
                    sp.vvalueProperty().addListener((o, oldV, newV) -> {
                        if (!suppressDecisionsScrollTracking)
                            decisionsUserScrolledUp = newV.doubleValue() < sp.getVmax() - 0.01;
                    });
                }
            }
        });

        refreshUi();

        AppConfig appConfig = AppConfig.load();
        startTradingComponents(appConfig);

        if (settingsPanelController != null) {
            settingsPanelController.setActiveBrokerType(appConfig.getBrokerType());
            settingsPanelController.setActiveApiKey(appConfig.getAlpacaApiKey());
            settingsPanelController.setOnSettingsSaved(cfg -> {
                if (tradingLoop != null) {
                    tradingLoop.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
                    tradingLoop.setDailyLossLimitPct(cfg.getDailyLossLimitPct() / 100.0);
                    tradingLoop.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
                    tradingLoop.setMarketRegimeFilterEnabled(cfg.isMarketRegimeFilterEnabled());
                    tradingLoop.setEarningsBlackoutDays(cfg.getEarningsBlackoutDays());
                    tradingLoop.setTrailingStopPct(cfg.getTrailingStopPct());
                    tradingLoop.setMaxLossPerTradePct(cfg.getMaxLossPerTradePct());
                    tradingLoop.setCircuitBreakerPct(cfg.getCircuitBreakerPct());
                }
                if (optionsRouter != null) {
                    optionsRouter.setMaxPortfolioExposure(cfg.getMaxPortfolioExposurePct() / 100.0);
                    optionsRouter.setEnabledStrategies(cfg.getEnabledStrategies());
                    optionsRouter.setAvoidOvernightHolds(cfg.isAvoidOvernightHolds());
                    optionsRouter.setEntryConfirmationTicks(cfg.getEntryConfirmationTicks());
                    optionsRouter.setOvernightMinPremiumFrac(cfg.getOvernightMinPremiumFrac());
                    optionsRouter.setIvSurgeThreshold(cfg.getIvSurgeThreshold());
                    optionsRouter.setStopLossFrac(cfg.getOptionsStopLossFrac());
                    optionsRouter.setProfitTarget(cfg.getProfitTarget());
                    optionsRouter.setReversalMinSignals(cfg.getReversalMinSignals());
                    optionsRouter.setDowntrendPutMinSignals(cfg.getDowntrendPutMinSignals());
                    optionsRouter.setEntryCutoff(cfg.getOptionsEntryCutoff());
                    optionsRouter.setEntryStartTime(cfg.getOptionsEntryStartTime());
                    if (cfg.getOptionsForceCloseTime() != null) optionsRouter.setForceCloseTime(cfg.getOptionsForceCloseTime());
                    optionsRouter.setPositionBudgetFrac(cfg.getPositionBudgetFrac());
                    optionsRouter.setMaxContractsPerTrade(cfg.getMaxContractsPerTrade());
                    tradingLoop.setLossLimitRecoveryBars(cfg.getLossLimitRecoveryBars());
                    optionsRouter.setOptionsAllowlist(cfg.getOptionsSymbolAllowlist());
                    optionsRouter.setCallsDisabledSymbols(cfg.getOptionsCallsDisabled());
                    optionsRouter.setPutsDisabledSymbols(cfg.getOptionsPutsDisabled());
                }
                Platform.runLater(() -> decisionsArea.appendText(
                        "\nRisk settings updated (effective next tick). Broker/quote changes take effect on next restart.\n"));
            });
            settingsPanelController.setOnBrokerReset(this::handleBrokerReset);
        }
    }

    private void startTradingComponents(AppConfig appConfig) {
        boolean useWsProvider = appConfig.getQuoteProviderType() == AppConfig.QuoteProviderType.ALPACA_WEBSOCKET_FREE
                && appConfig.isAlpacaBroker();

        decisionsArea.setText("Waiting for market data...\n\nMarket hours: 9:30 AM – 4:00 PM ET"
                + "\nWatching " + (useWsProvider ? "30 most-liquid day-trading symbols" : "100 large-cap and small-cap US stocks") + "."
                + "\nBroker: " + SettingsController.brokerTypeLabel(appConfig.getBrokerType())
                + " | Quotes: " + appConfig.getQuoteProviderType().name());

        QuoteProvider quoteProvider;
        if (useWsProvider) {
            wsProvider = new AlpacaWebSocketFreeProvider(appConfig, candleHistory,
                    msg -> Platform.runLater(() -> decisionsArea.appendText(msg + "\n")));
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
            alpaca.setLogCallback(tradingLogger::log);
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
            if (msg.contains("| BUY=")) {
                pendingSignals.add(msg);
            } else {
                pendingDecisions.add(msg);
                tradingLogger.log(msg);
            }
        };
        // Coalescing UI refresh: snapshot is computed on the trading thread, then stored in
        // pendingSnapshot. Only one Platform.runLater dispatch is outstanding at a time — if one
        // is already queued, we just replace the snapshot value so the FX thread applies the
        // latest state when it runs. This prevents multiple applyUiSnapshot calls from piling up
        // in the FX event queue on ticks where several trades fire in quick succession.
        Runnable uiRefresh = () -> {
            UiSnapshot snapshot = computeUiSnapshot();
            if (pendingSnapshot.getAndSet(snapshot) == null) {
                Platform.runLater(() -> {
                    UiSnapshot s = pendingSnapshot.getAndSet(null);
                    if (s != null) applyUiSnapshot(s);
                });
            }
        };

        OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, transactionLog,
                appConfig.isAlpacaBroker()
                        ? (AlpacaBroker) brokerClient
                        : null);
        // AlpacaWebSocketFreeProvider.getOptionsChain() is a stub — options chains
        // require REST. Use a dedicated AlpacaQuoteProvider for chain lookups when
        // the WebSocket provider is active for real-time prices.
        QuoteProvider optionsDataClient = useWsProvider
                ? new AlpacaQuoteProvider(appConfig)
                : quoteProvider;
        optionsRouter = new OptionsSignalRouter(
                bsEngine, optExec, account, priceHistory, researchCb, optionsDataClient);
        optionsRouter.setOptionsAllowlist(appConfig.getOptionsSymbolAllowlist());
        optionsRouter.setCallsDisabledSymbols(appConfig.getOptionsCallsDisabled());
        optionsRouter.setPutsDisabledSymbols(appConfig.getOptionsPutsDisabled());
        if (appConfig.isAlpacaBroker()) {
            optionsRouter.restoreSessionState(transactionLog);
        }

        premiumSellerRouter = new PremiumSellerRouter(
                bsEngine, optExec, account, priceHistory, researchCb);
        if (!appConfig.getPremiumEnabledStrategies().isEmpty()) {
            premiumSellerRouter.setEnabledStrategies(appConfig.getPremiumEnabledStrategies());
        }
        premiumSellerRouter.setMaxPortfolioExposure(appConfig.getMaxPortfolioExposurePct() / 100.0);
        premiumSellerRouter.setAllowlist(appConfig.getOptionsSymbolAllowlist());
        String dataDir = new java.io.File(transactionLog.getDbPath()).getParent();
        premiumSellerRouter.restoreExitDates(dataDir);

        Path weightsPath = AppConfig.getDataDir().resolve("signal-weights.json");
        SignalWeights initialWeights;
        try {
            initialWeights = Files.exists(weightsPath) ? SignalWeights.load(weightsPath) : new SignalWeights();
        } catch (IOException e) {
            initialWeights = new SignalWeights();
        }
        MLSignalEvaluator mlEval = new MLSignalEvaluator(initialWeights, weightsPath);
        Runnable trainingCallback = () -> {
            mlEval.retrain(transactionLog);
            Platform.runLater(() -> decisionsArea.appendText(
                "ML weights updated: " + mlEval.getWeightsSummary() + "\n"));
        };

        EarningsCalendar earningsCalendar = new EarningsCalendar();

        List<String> allSymbols;
        if (useWsProvider) {
            allSymbols = new ArrayList<>(DayTraderWatchList.SYMBOLS);
            List<String> stockWatchlist = appConfig.getStockWatchlist();
            if (!stockWatchlist.isEmpty()) {
                for (String sym : stockWatchlist) {
                    if (!allSymbols.contains(sym)) allSymbols.add(sym);
                }
            }
        } else {
            allSymbols = new ArrayList<>();
            List<String> stockWatchlist = appConfig.getStockWatchlist();
            allSymbols.addAll(stockWatchlist.isEmpty() ? LargeCapWatchList.SYMBOLS : stockWatchlist);
            allSymbols.addAll(SmallCapWatchList.SYMBOLS);
        }

        tradingLoop = new TradingLoop(quoteProvider, priceHistory,
                new IndicatorEngine(), new TrailingStopMonitor(), brokerClient, feeCalc,
                allSymbols, researchCb, uiRefresh, account,
                optionsRouter, mlEval, trainingCallback);
        tradingLoop.setTransactionLog(transactionLog);
        if (appConfig.isPremiumSellerEnabled()) {
            premiumSellerRouter.setUptrendSupplier(tradingLoop::isUptrend);
            tradingLoop.setPremiumSellerEvaluator(premiumSellerRouter);
        }
        if (useWsProvider) {
            tradingLoop.setCandleHistory(candleHistory);
        }
        tradingLoop.setNewsSentimentCache(sentimentCache);

        if (sentimentScheduler != null) sentimentScheduler.stop();
        if (!appConfig.getClaudeApiKey().isBlank()) {
            sentimentScheduler = new SentimentRefreshScheduler(
                    sentimentCache, appConfig.getClaudeApiKey(), allSymbols, researchCb);
            sentimentScheduler.start();
        }
        tradingLoop.setDailyLossLimitPct(appConfig.getDailyLossLimitPct() / 100.0);
        tradingLoop.setMaxPortfolioExposure(appConfig.getMaxPortfolioExposurePct() / 100.0);
        optionsRouter.setMaxPortfolioExposure(appConfig.getMaxPortfolioExposurePct() / 100.0);
        optionsRouter.setEnabledStrategies(appConfig.getEnabledStrategies());
        optionsRouter.setDowntrendPutMinSignals(appConfig.getDowntrendPutMinSignals());
        optionsRouter.setReversalMinSignals(appConfig.getReversalMinSignals());
        optionsRouter.setProfitTarget(appConfig.getProfitTarget());
        tradingLoop.setAvoidOvernightHolds(appConfig.isAvoidOvernightHolds());
        optionsRouter.setAvoidOvernightHolds(appConfig.isAvoidOvernightHolds());
        optionsRouter.setEntryConfirmationTicks(appConfig.getEntryConfirmationTicks());
        optionsRouter.setOvernightMinPremiumFrac(appConfig.getOvernightMinPremiumFrac());
        optionsRouter.setIvSurgeThreshold(appConfig.getIvSurgeThreshold());
        tradingLoop.setMarketRegimeFilterEnabled(appConfig.isMarketRegimeFilterEnabled());
        tradingLoop.setEarningsCalendar(earningsCalendar);
        tradingLoop.setEarningsBlackoutDays(appConfig.getEarningsBlackoutDays());
        optionsRouter.setUptrendSupplier(tradingLoop::isUptrend);
        optionsRouter.setStopLossFrac(appConfig.getOptionsStopLossFrac());
        optionsRouter.setEntryCutoff(appConfig.getOptionsEntryCutoff());
        optionsRouter.setEntryStartTime(appConfig.getOptionsEntryStartTime());
        if (appConfig.getOptionsForceCloseTime() != null) optionsRouter.setForceCloseTime(appConfig.getOptionsForceCloseTime());
        optionsRouter.setPositionBudgetFrac(appConfig.getPositionBudgetFrac());
        optionsRouter.setMaxContractsPerTrade(appConfig.getMaxContractsPerTrade());
        tradingLoop.setLossLimitRecoveryBars(appConfig.getLossLimitRecoveryBars());
        optionsRouter.setClosePositionsOnHalt(true);
        tradingLoop.setAccurateOptionsValuation(true);
        tradingLoop.setStockTradingEnabled(appConfig.isStockTradingEnabled());
        tradingLoop.setTrailingStopPct(appConfig.getTrailingStopPct());
        tradingLoop.setMaxLossPerTradePct(appConfig.getMaxLossPerTradePct());
        tradingLoop.setCircuitBreakerPct(appConfig.getCircuitBreakerPct());
        tradingLoop.setMaxConcurrentStockPositions(8);
        tradingLoop.setRegimeMaDays(5);
        if (!appConfig.getOptionsSymbolAllowlist().isEmpty()) {
            tradingLoop.setOptionsWatchlist(appConfig.getOptionsSymbolAllowlist());
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trading-loop");
            t.setDaemon(true);
            return t;
        });
        // Day trading: 5s interval so candle data and signals are evaluated frequently
        scheduler.scheduleAtFixedRate(tradingLoop, 0, 5, TimeUnit.SECONDS);

        // Seed daily price history so RSI and Bollinger Bands are active from the first tick.
        // Always use Yahoo Finance (free, no credentials) — Alpaca's data API requires
        // a paid subscription and fails silently, leaving RSI/BB permanently neutral.
        // 365 calendar days (~252 trading days) gives a stable long-term sigma baseline for
        // the IV surge guard. 60 days was too short — a calm spring followed by any vol
        // uptick would falsely trip the 1.5× threshold, blocking all options entries.
        HistoricalBarFetcher seedFetcher = new HistoricalBarFetcher();
        Thread seedThread = new Thread(() -> {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(365);
            int seeded = 0;
            List<String> symbolsToSeed = new ArrayList<>(allSymbols);
            if (!symbolsToSeed.contains("SPY")) symbolsToSeed.add("SPY");
            for (String sym : symbolsToSeed) {
                try {
                    var bars = seedFetcher.fetchDailyBars(sym, start, end);
                    if (bars != null && !bars.isEmpty()) {
                        priceHistory.seed(sym, bars);
                        seeded++;
                    }
                    Thread.sleep(120);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    researchCb.accept("Seed failed for " + sym + ": " + e.getMessage());
                }
            }
            int finalSeeded = seeded;
            researchCb.accept("Price history seeded for " + finalSeeded + "/"
                    + symbolsToSeed.size() + " symbols — RSI and Bollinger Bands now active.");
        }, "price-history-seed");
        seedThread.setDaemon(true);
        seedThread.start();
    }

    private void handleBrokerReset(AppConfig newConfig) {
        // Capture and clear fields on the FX thread before handing off to background.
        ScheduledExecutorService schedulerToStop = scheduler;
        AlpacaWebSocketFreeProvider wsToStop = wsProvider;
        scheduler = null;
        wsProvider = null;

        Thread resetThread = new Thread(() -> {
            // Blocking shutdown off the FX thread — previously froze the UI for up to 5 seconds.
            if (schedulerToStop != null) {
                schedulerToStop.shutdownNow();
                try { schedulerToStop.awaitTermination(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (wsToStop != null) wsToStop.stop();

            Platform.runLater(() -> {
                pendingSignals.clear();
                pendingDecisions.clear();
                transactionLog.clearAll();
                account.reset(newConfig.isAlpacaBroker() ? 0.0 : 100_000.0);
                priceHistory = new PriceHistory();
                candleHistory = new CandleHistory();
                equitySeries.getData().clear();
                tickCount = 0;
                applyUiSnapshot(computeUiSnapshot());
                decisionsArea.setText("Broker switched to "
                        + SettingsController.brokerTypeLabel(newConfig.getBrokerType())
                        + ". Historical data cleared.\nWaiting for market data...\n");
                signalsArea.setText("");
                startTradingComponents(newConfig);
                if (settingsPanelController != null) {
                    settingsPanelController.setActiveBrokerType(newConfig.getBrokerType());
                    settingsPanelController.setActiveApiKey(newConfig.getAlpacaApiKey());
                }
            });
        }, "broker-reset");
        resetThread.setDaemon(true);
        resetThread.start();
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
        optColPnl.setCellValueFactory(new PropertyValueFactory<>("pnl"));
        optColPnl.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setTextFill(Color.BLACK);
                } else {
                    setText(value);
                    setTextFill(value.startsWith("-") ? Color.RED : Color.GREEN);
                }
            }
        });
    }

    private void setupStockTableColumns() {
        stkColSymbol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        stkColQuantity.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        stkColAvgCost.setCellValueFactory(new PropertyValueFactory<>("avgCost"));
        stkColCurrentPrice.setCellValueFactory(new PropertyValueFactory<>("currentPrice"));
        stkColMarketValue.setCellValueFactory(new PropertyValueFactory<>("marketValue"));
        stkColUnrealizedPnl.setCellValueFactory(new PropertyValueFactory<>("unrealizedPnl"));
    }

    private void setupPremiumTableColumns() {
        prmColSymbol.setCellValueFactory(new PropertyValueFactory<>("symbol"));
        prmColStrategy.setCellValueFactory(new PropertyValueFactory<>("strategy"));
        prmColLowStrike.setCellValueFactory(new PropertyValueFactory<>("lowStrike"));
        prmColHighStrike.setCellValueFactory(new PropertyValueFactory<>("highStrike"));
        prmColCurrentPrice.setCellValueFactory(new PropertyValueFactory<>("currentPrice"));
        prmColExpiry.setCellValueFactory(new PropertyValueFactory<>("expiry"));
        prmColDte.setCellValueFactory(new PropertyValueFactory<>("dte"));
        prmColPremium.setCellValueFactory(new PropertyValueFactory<>("premiumCollected"));
        prmColPremium.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setText(null); setTextFill(Color.BLACK); return; }
                setText(value);
                PremiumSellerRow row = getTableView().getItems().get(getIndex());
                setTextFill(row.getMaxProfitRaw() >= 0 ? Color.GREEN : Color.RED);
            }
        });
        prmColMax.setCellValueFactory(new PropertyValueFactory<>("maxProfit"));
        prmColPnl.setCellValueFactory(new PropertyValueFactory<>("currentPnl"));
        prmColPct.setCellValueFactory(new PropertyValueFactory<>("pctCaptured"));
        prmColPnl.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setText(null); setTextFill(Color.BLACK); return; }
                setText(value);
                PremiumSellerRow row = getTableView().getItems().get(getIndex());
                setTextFill(row.getPnlRaw() >= 0 ? Color.GREEN : Color.RED);
            }
        });
    }

    // Safe to call on any thread — no FX node access.
    private List<PremiumSellerRow> buildPremiumRows() {
        List<PremiumSellerRow> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // Put Credit Spreads
        for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e :
                account.getOptionsPositions().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith(PremiumSellerRouter.PUTSPREAD_SHORT)) continue;
            String symbol = e.getValue().getSymbol();
            com.tradingapp.account.OptionsPosition shortPos = e.getValue();
            com.tradingapp.account.OptionsPosition longPos =
                    account.getOptionsPositions().get(symbol + PremiumSellerRouter.PUTSPREAD_LONG);
            if (longPos == null) continue;
            int c = Math.abs(shortPos.getContracts());
            double credit = (shortPos.getPremiumPaid() - longPos.getPremiumPaid()) * 100 * c;
            double sCur = computeOptionCurrentPremium(shortPos);
            double lCur = computeOptionCurrentPremium(longPos);
            double closeCost = (sCur - lCur) * 100 * c;
            double pnl = credit - closeCost;
            long dte = java.time.temporal.ChronoUnit.DAYS.between(today, shortPos.getExpiry());
            List<Double> stockPrices = priceHistory.getPrices(symbol);
            String stockPriceStr = stockPrices.isEmpty() ? "—" : String.format("$%.2f", stockPrices.get(stockPrices.size() - 1));
            rows.add(new PremiumSellerRow(symbol, "Put Credit Spread",
                    String.format("$%.0f", shortPos.getStrike()),
                    shortPos.getExpiry().toString(), Math.max(0, dte), credit, pnl,
                    String.format("$%.0f", longPos.getStrike()),
                    String.format("$%.0f", shortPos.getStrike()),
                    stockPriceStr));
        }

        // Call Credit Spreads
        for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e :
                account.getOptionsPositions().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith(PremiumSellerRouter.CALLSPREAD_SHORT)) continue;
            String symbol = e.getValue().getSymbol();
            com.tradingapp.account.OptionsPosition shortPos = e.getValue();
            com.tradingapp.account.OptionsPosition longPos =
                    account.getOptionsPositions().get(symbol + PremiumSellerRouter.CALLSPREAD_LONG);
            if (longPos == null) continue;
            int c = Math.abs(shortPos.getContracts());
            double credit = (shortPos.getPremiumPaid() - longPos.getPremiumPaid()) * 100 * c;
            double sCur = computeOptionCurrentPremium(shortPos);
            double lCur = computeOptionCurrentPremium(longPos);
            double closeCost = (sCur - lCur) * 100 * c;
            double pnl = credit - closeCost;
            long dte = java.time.temporal.ChronoUnit.DAYS.between(today, shortPos.getExpiry());
            List<Double> stockPrices = priceHistory.getPrices(symbol);
            String stockPriceStr = stockPrices.isEmpty() ? "—" : String.format("$%.2f", stockPrices.get(stockPrices.size() - 1));
            rows.add(new PremiumSellerRow(symbol, "Call Credit Spread",
                    String.format("$%.0f", shortPos.getStrike()),
                    shortPos.getExpiry().toString(), Math.max(0, dte), credit, pnl,
                    String.format("$%.0f", shortPos.getStrike()),
                    String.format("$%.0f", longPos.getStrike()),
                    stockPriceStr));
        }

        // Iron Condors
        for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e :
                account.getOptionsPositions().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith(PremiumSellerRouter.IC_SHORTCALL)) continue;
            String symbol = e.getValue().getSymbol();
            com.tradingapp.account.OptionsPosition scPos = e.getValue();
            com.tradingapp.account.OptionsPosition lcPos = account.getOptionsPositions().get(symbol + PremiumSellerRouter.IC_LONGCALL);
            com.tradingapp.account.OptionsPosition spPos = account.getOptionsPositions().get(symbol + PremiumSellerRouter.IC_SHORTPUT);
            com.tradingapp.account.OptionsPosition lpPos = account.getOptionsPositions().get(symbol + PremiumSellerRouter.IC_LONGPUT);
            if (lcPos == null || spPos == null || lpPos == null) continue;
            int c = Math.abs(scPos.getContracts());
            double credit = ((scPos.getPremiumPaid() - lcPos.getPremiumPaid())
                           + (spPos.getPremiumPaid() - lpPos.getPremiumPaid())) * 100 * c;
            double scCur = computeOptionCurrentPremium(scPos);
            double lcCur = computeOptionCurrentPremium(lcPos);
            double spCur = computeOptionCurrentPremium(spPos);
            double lpCur = computeOptionCurrentPremium(lpPos);
            double closeCost = ((scCur - lcCur) + (spCur - lpCur)) * 100 * c;
            double pnl = credit - closeCost;
            long dte = java.time.temporal.ChronoUnit.DAYS.between(today, scPos.getExpiry());
            String shortStrikes = String.format("$%.0f/$%.0f", spPos.getStrike(), scPos.getStrike());
            List<Double> icStockPrices = priceHistory.getPrices(symbol);
            String icStockPriceStr = icStockPrices.isEmpty() ? "—" : String.format("$%.2f", icStockPrices.get(icStockPrices.size() - 1));
            rows.add(new PremiumSellerRow(symbol, "Iron Condor", shortStrikes,
                    scPos.getExpiry().toString(), Math.max(0, dte), credit, pnl,
                    String.format("$%.0f", spPos.getStrike()),
                    String.format("$%.0f", scPos.getStrike()),
                    icStockPriceStr));
        }

        // Cash-Secured Puts
        for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e :
                account.getOptionsPositions().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith(PremiumSellerRouter.CSP_PUT)) continue;
            com.tradingapp.account.OptionsPosition pos = e.getValue();
            String symbol = pos.getSymbol();
            int c = Math.abs(pos.getContracts());
            double credit = pos.getPremiumPaid() * 100 * c;
            double curPrem = computeOptionCurrentPremium(pos);
            double pnl = credit - curPrem * 100 * c;
            long dte = java.time.temporal.ChronoUnit.DAYS.between(today, pos.getExpiry());
            List<Double> cspStockPrices = priceHistory.getPrices(symbol);
            String cspStockPriceStr = cspStockPrices.isEmpty() ? "—" : String.format("$%.2f", cspStockPrices.get(cspStockPrices.size() - 1));
            String cspStrike = String.format("$%.0f", pos.getStrike());
            rows.add(new PremiumSellerRow(symbol, "Cash-Secured Put",
                    cspStrike, pos.getExpiry().toString(), Math.max(0, dte), credit, pnl,
                    cspStrike, "—", cspStockPriceStr));
        }

        // Covered Calls
        for (Map.Entry<String, com.tradingapp.account.OptionsPosition> e :
                account.getOptionsPositions().entrySet()) {
            String key = e.getKey();
            if (!key.endsWith(PremiumSellerRouter.CC_CALL)) continue;
            com.tradingapp.account.OptionsPosition pos = e.getValue();
            String symbol = pos.getSymbol();
            int c = Math.abs(pos.getContracts());
            double credit = pos.getPremiumPaid() * 100 * c;
            double curPrem = computeOptionCurrentPremium(pos);
            double pnl = credit - curPrem * 100 * c;
            long dte = java.time.temporal.ChronoUnit.DAYS.between(today, pos.getExpiry());
            List<Double> ccStockPrices = priceHistory.getPrices(symbol);
            String ccStockPriceStr = ccStockPrices.isEmpty() ? "—" : String.format("$%.2f", ccStockPrices.get(ccStockPrices.size() - 1));
            String ccStrike = String.format("$%.0f", pos.getStrike());
            rows.add(new PremiumSellerRow(symbol, "Covered Call",
                    ccStrike, pos.getExpiry().toString(), Math.max(0, dte), credit, pnl,
                    "—", ccStrike, ccStockPriceStr));
        }

        return rows;
    }

    // Compute-phase: builds rows from account state. Safe to call on any thread.
    private double buildOptionsRows(List<OptionsPositionRow> rows) {
        double totalUnrealized = 0.0;

        Map<String, List<Map.Entry<String, OptionsPosition>>> groups = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, OptionsPosition> entry : account.getOptionsPositions().entrySet()) {
            if (PremiumSellerRouter.isPremiumKey(entry.getKey())) continue;
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
            case "PUTSPREAD"     -> "Put Credit Spread";
            case "CALLSPREAD"    -> "Call Credit Spread";
            case "CSP"           -> "Cash-Secured Put";
            case "CC"            -> "Covered Call";
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

    // Compute-phase: builds rows from account state. Safe to call on any thread.
    private double buildStockRows(List<StockPositionRow> rows) {
        double totalUnrealized = 0.0;
        for (Position pos : account.getPositions().values()) {
            List<Double> prices = priceHistory.getPrices(pos.getSymbol());
            double currentPrice = prices.isEmpty() ? pos.getAverageCost() : prices.get(prices.size() - 1);
            totalUnrealized += (currentPrice - pos.getAverageCost()) * pos.getQuantity();
            rows.add(new StockPositionRow(pos.getSymbol(), pos.getQuantity(), pos.getAverageCost(), currentPrice));
        }
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
            total += entry.getValue().getMarketValue();
        }
        return total;
    }

    private double computeOptionCurrentPremium(OptionsPosition pos) {
        // Prefer the Alpaca-reported market price (set during broker sync) over Black-Scholes.
        // This eliminates the historical-vol vs implied-vol gap that causes portfolio overvaluation.
        // -1.0 means Alpaca has never reported a price for this position; use Black-Scholes as a fallback.
        // 0.0 means Alpaca explicitly returned no quote — honour it rather than overvaluing with BS.
        double marketPrice = pos.getCurrentMarketPrice();
        if (marketPrice >= 0) return marketPrice;

        // Fall back to Black-Scholes for paper trading or before the first sync tick.
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

    private record UiSnapshot(
            List<TransactionRecord> history,
            double availableCash,
            double stockHoldings,
            double optionHoldings,
            double totalPortfolio,
            double optionsCashDeployed,
            List<OptionsPositionRow> optionRows,
            double optTotalUnrealized,
            List<StockPositionRow> stockRows,
            double stkTotalUnrealized,
            double totalUnrealizedPnl,
            int wins,
            int losses,
            double winRate,
            double realizedPnl,
            boolean tradingHalted,
            boolean dailyLossHalted,
            List<PremiumSellerRow> premiumRows,
            double premiumTotalPnl
    ) {}

    // Safe to call on any thread — no FX node access.
    private UiSnapshot computeUiSnapshot() {
        List<TransactionRecord> history = collapseMultiLegHistory(transactionLog.findAll());
        double availableCash = account.getBalance();
        double stockHoldings = computeStockHoldings();
        double optionHoldings = computeOptionHoldings();
        double optionsCashDeployed = computeOptionsCashDeployed();

        List<OptionsPositionRow> optionRows = new ArrayList<>();
        double optTotalUnrealized = buildOptionsRows(optionRows);

        List<StockPositionRow> stockRows = new ArrayList<>();
        double stkTotalUnrealized = buildStockRows(stockRows);

        List<ClosedTradeRecord> closedTrades = computeClosedTrades();
        int wins   = (int) closedTrades.stream().filter(t -> t.getPnlRaw() >= 0).count();
        int losses = (int) closedTrades.stream().filter(t -> t.getPnlRaw() <  0).count();
        int total  = wins + losses;
        double winRate    = total > 0 ? (wins * 100.0 / total) : 0.0;
        double realizedPnl = closedTrades.stream().mapToDouble(ClosedTradeRecord::getPnlRaw).sum();

        // Prefer Alpaca's own portfolio_value from /account — it's the authoritative number
        // that matches their UI display and eliminates local pricing discrepancies.
        // Fall back to local computation before the first sync tick completes.
        double brokerPv = account.getBrokerPortfolioValue();
        double totalPortfolio = brokerPv > 0 ? brokerPv : availableCash + stockHoldings + optionHoldings;

        // Derive total unrealized P&L from Alpaca's authoritative portfolio value so it
        // stays consistent with the total portfolio figure (brokerPv - starting = total P&L).
        // Fall back to local computation when Alpaca data isn't available yet.
        double totalUnrealizedPnl = brokerPv > 0
                ? brokerPv - Account.STARTING_BALANCE
                : optTotalUnrealized + stkTotalUnrealized;

        List<PremiumSellerRow> premiumRows = buildPremiumRows();
        double premiumTotalPnl = premiumRows.stream().mapToDouble(PremiumSellerRow::getPnlRaw).sum();

        return new UiSnapshot(history, availableCash, stockHoldings, optionHoldings, totalPortfolio,
                optionsCashDeployed, optionRows, optTotalUnrealized, stockRows, stkTotalUnrealized,
                totalUnrealizedPnl, wins, losses, winRate, realizedPnl, account.isTradingHalted(),
                account.isDailyLossHalted(), premiumRows, premiumTotalPnl);
    }

    // Must be called on the FX thread. Only touches FX nodes.
    private void applyUiSnapshot(UiSnapshot s) {
        String msg;

        // --- Signals panel (always auto-scrolls, just a ticker) ---
        StringBuilder signalBuf = new StringBuilder();
        while ((msg = pendingSignals.poll()) != null) signalBuf.append(msg).append('\n');
        if (signalBuf.length() > 0) {
            String existing = signalsArea.getText();
            if (existing.length() > 80_000) {
                int cut = existing.indexOf('\n', existing.length() - 60_000);
                existing = cut > 0 ? existing.substring(cut + 1) : existing.substring(existing.length() - 60_000);
            }
            if (existing.equals(signalsArea.getText())) {
                signalsArea.appendText(signalBuf.toString());
            } else {
                signalsArea.setText(existing + signalBuf.toString());
            }
        }

        // --- Decisions panel (preserve scroll when user has scrolled up to read) ---
        StringBuilder decisionBuf = new StringBuilder();
        while ((msg = pendingDecisions.poll()) != null) decisionBuf.append(msg).append('\n');
        if (decisionBuf.length() > 0) {
            // TextArea layout cost grows with text length; trim from the top when it gets large.
            String existing = decisionsArea.getText();
            if (existing.length() > 80_000) {
                int cut = existing.indexOf('\n', existing.length() - 60_000);
                existing = cut > 0 ? existing.substring(cut + 1) : existing.substring(existing.length() - 60_000);
            }

            if (decisionsUserScrolledUp && decisionsScrollPane != null) {
                // User has scrolled up to read. Suppress tracking so the setText scroll-to-top
                // doesn't corrupt decisionsUserScrolledUp; restore saved position in runLater.
                double savedV = decisionsScrollPane.getVvalue();
                suppressDecisionsScrollTracking = true;
                decisionsArea.setText(existing + decisionBuf.toString());
                final double v = savedV;
                Platform.runLater(() -> { decisionsScrollPane.setVvalue(v); suppressDecisionsScrollTracking = false; });
            } else {
                // User is at the bottom or scroll pane not yet available — normal auto-scroll.
                if (existing.equals(decisionsArea.getText())) {
                    decisionsArea.appendText(decisionBuf.toString());
                } else {
                    // Text was trimmed; setText was needed. Suppress tracking so the transient
                    // scroll-to-top doesn't flip decisionsUserScrolledUp; restore bottom in runLater.
                    suppressDecisionsScrollTracking = true;
                    decisionsArea.setText(existing + decisionBuf.toString());
                    Platform.runLater(() -> {
                        if (decisionsScrollPane != null) decisionsScrollPane.setVvalue(decisionsScrollPane.getVmax());
                        suppressDecisionsScrollTracking = false;
                    });
                }
            }
        }

        premiumTable.setItems(FXCollections.observableArrayList(s.premiumRows()));
        double premPnl = s.premiumTotalPnl();
        premiumTotalLabel.setText(s.premiumRows().isEmpty() ? "No open premium positions"
                : String.format("Open positions: %d  |  Total P&L: %s$%.0f",
                        s.premiumRows().size(),
                        premPnl >= 0 ? "+" : "-",
                        Math.abs(premPnl)));
        tradeHistoryTable.setItems(FXCollections.observableArrayList(s.history()));
        totalPortfolioLabel.setText(String.format("Total Portfolio: $%,.2f", s.totalPortfolio()));
        stockHoldingsLabel.setText(String.format("Stocks: $%,.2f", s.stockHoldings()));
        optionHoldingsLabel.setText(String.format("Options: $%,.2f", s.optionHoldings()));
        availableCashLabel.setText(String.format("Cash: $%,.2f", s.availableCash()));
        optionsCashDeployedLabel.setText(String.format("Options Reserved: $%,.2f", s.optionsCashDeployed()));
        optionsTable.setItems(FXCollections.observableArrayList(s.optionRows()));
        stockPositionsTable.setItems(FXCollections.observableArrayList(s.stockRows()));
        unrealizedPnlLabel.setText(formatUnrealizedPnl("Unrealized P&L", s.totalUnrealizedPnl()));
        optionsTotalUnrealizedLabel.setText(formatUnrealizedPnl("Total P&L", s.optTotalUnrealized()));
        stockTotalUnrealizedLabel.setText(formatUnrealizedPnl("Total Unrealized P&L", s.stkTotalUnrealized()));
        winsLabel.setText("Wins: " + s.wins());
        lossesLabel.setText("Losses: " + s.losses());
        winRateLabel.setText(String.format("Win Rate: %.1f%%", s.winRate()));
        pnlButton.setText(String.format("P&L: $%,.2f", s.realizedPnl()));
        equitySeries.getData().add(new XYChart.Data<>(tickCount++, s.totalPortfolio()));
        if (equitySeries.getData().size() > 200) equitySeries.getData().remove(0);
        if (s.tradingHalted()) {
            haltedLabel.setText("⛔ TRADING HALTED — portfolio exhausted");
            resetDailyLossButton.setVisible(false);
        } else if (s.dailyLossHalted()) {
            haltedLabel.setText("⛔ DAILY LOSS LIMIT HIT — trading halted");
            resetDailyLossButton.setVisible(true);
        } else {
            haltedLabel.setText("");
            resetDailyLossButton.setVisible(false);
        }
    }

    private void refreshUi() {
        applyUiSnapshot(computeUiSnapshot());
    }

    public void refreshBalance() {
        double availableCash = account.getBalance();
        double stockHoldings = computeStockHoldings();
        double optionHoldings = computeOptionHoldings();
        double realizedPnl = computeClosedTrades().stream().mapToDouble(ClosedTradeRecord::getPnlRaw).sum();
        double brokerPv = account.getBrokerPortfolioValue();
        double totalPortfolio = brokerPv > 0 ? brokerPv : availableCash + stockHoldings + optionHoldings;
        double unrealizedPnl = brokerPv > 0
                ? brokerPv - Account.STARTING_BALANCE
                : computeStockUnrealizedPnL() + computeOptionsUnrealizedPnL();

        totalPortfolioLabel.setText(String.format("Total Portfolio: $%,.2f", totalPortfolio));
        stockHoldingsLabel.setText(String.format("Stocks: $%,.2f", stockHoldings));
        optionHoldingsLabel.setText(String.format("Options: $%,.2f", optionHoldings));
        availableCashLabel.setText(String.format("Cash: $%,.2f", availableCash));
        optionsCashDeployedLabel.setText(String.format("Options Reserved: $%,.2f", computeOptionsCashDeployed()));
        pnlButton.setText(String.format("P&L: $%,.2f", realizedPnl));
        unrealizedPnlLabel.setText(formatUnrealizedPnl("Unrealized P&L", unrealizedPnl));

        if (account.isTradingHalted()) {
            haltedLabel.setText("⛔ TRADING HALTED — portfolio exhausted");
            resetDailyLossButton.setVisible(false);
        } else if (account.isDailyLossHalted()) {
            haltedLabel.setText("⛔ DAILY LOSS LIMIT HIT — trading halted");
            resetDailyLossButton.setVisible(true);
        } else {
            haltedLabel.setText("");
            resetDailyLossButton.setVisible(false);
        }
    }

    @FXML
    private void onPnlClicked() {
        showPnlBreakdown();
    }

    @FXML
    private void onResetDailyLossClicked() {
        if (tradingLoop != null) tradingLoop.resetDailyLossHalt();
        account.setDailyLossHalted(false);
        haltedLabel.setText("");
        resetDailyLossButton.setVisible(false);
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
            // Skip Alpaca order-history imports: they duplicate fills already tracked locally
            // and create spurious open/close pairs that pollute the P&L breakdown.
            if (r.getReason() != null && r.getReason().contains("(imported)")) continue;

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
                case CALL_BUY -> {
                    if (r.getQuantity() < 0) {
                        // Negative qty = buy_to_close a short (new recordClose format)
                        processShortClose(r, "_CALL", "Call Option",
                                openShortPremiums, closed, processedGroupIds);
                    } else {
                        accumulateOption(openLongPremiums, r, "_CALL");
                    }
                }
                case PUT_BUY -> {
                    if (r.getQuantity() < 0) {
                        processShortClose(r, "_PUT", "Put Option",
                                openShortPremiums, closed, processedGroupIds);
                    } else {
                        accumulateOption(openLongPremiums, r, "_PUT");
                    }
                }
                case CALL_SELL -> processOptionSell(r, "_CALL", "Call Option",
                        openLongPremiums, openShortPremiums, closed, processedGroupIds);
                case PUT_SELL  -> processOptionSell(r, "_PUT",  "Put Option",
                        openLongPremiums, openShortPremiums, closed, processedGroupIds);
            }
        }

        Collections.reverse(closed); // most recent first
        return mergeMultiLegClosedTrades(closed);
    }

    /**
     * Handles a CALL_BUY/PUT_BUY record with quantity < 0: this is a buy_to_close of a short leg
     * (the new recordClose format writes CALL_BUY for short closes instead of CALL_SELL qty<0).
     */
    private static void processShortClose(TransactionRecord r, String suffix, String type,
                                          Map<String, double[]> openShortPremiums,
                                          List<ClosedTradeRecord> closed,
                                          Set<String> processedGroupIds) {
        String key = r.getSymbol() + suffix;
        int qty = Math.abs(r.getQuantity());
        double[] entry = openShortPremiums.getOrDefault(key, new double[]{r.getPricePerUnit(), qty});

        String groupId = r.getGroupId();
        double reasonPnl = parseReasonPnl(r.getReason());
        boolean useReasonPnl = !Double.isNaN(reasonPnl) && groupId != null && r.getFeeCharged() == 0;

        double pnl;
        if (useReasonPnl && !processedGroupIds.contains(groupId)) {
            pnl = reasonPnl;
            processedGroupIds.add(groupId);
        } else if (useReasonPnl) {
            return; // P&L already recorded for this group — skip duplicate
        } else {
            pnl = (entry[0] - r.getPricePerUnit()) * 100.0 * qty - r.getFeeCharged();
        }
        closed.add(new ClosedTradeRecord(r.getSymbol(), type + " (Short)", qty,
                entry[0], r.getPricePerUnit(), pnl, r.getTimestamp(), r.getGroupId()));
        reduceOptionPosition(openShortPremiums, key, entry, qty);
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
            // Buying back a short (old format: CALL_SELL qty<0 before recordClose fix)
            int qty = Math.abs(r.getQuantity());
            double[] entry = openShortPremiums.getOrDefault(key, new double[]{r.getPricePerUnit(), qty});
            double pnl;
            if (useReasonPnl && !processedGroupIds.contains(groupId)) {
                pnl = reasonPnl;
                processedGroupIds.add(groupId);
            } else if (useReasonPnl) {
                return; // P&L already recorded for this group — skip duplicate
            } else {
                pnl = (entry[0] - r.getPricePerUnit()) * 100.0 * qty - r.getFeeCharged();
            }
            closed.add(new ClosedTradeRecord(r.getSymbol(), type + " (Short)", qty,
                    entry[0], r.getPricePerUnit(), pnl, r.getTimestamp(), r.getGroupId()));
            reduceOptionPosition(openShortPremiums, key, entry, qty);
        } else {
            // Selling a long leg.
            // If this group was already recorded (by the short-leg close), skip to avoid double-counting.
            // But if it hasn't been recorded yet (e.g. only the long-leg close was written to the DB),
            // capture the P&L from the reason string rather than per-leg price arithmetic.
            if (useReasonPnl && processedGroupIds.contains(groupId)) return;
            if (useReasonPnl && !processedGroupIds.contains(groupId)) {
                processedGroupIds.add(groupId);
                double[] entry = openLongPremiums.getOrDefault(key, new double[]{r.getPricePerUnit(), r.getQuantity()});
                closed.add(new ClosedTradeRecord(r.getSymbol(), type + " (Short)", r.getQuantity(),
                        entry[0], r.getPricePerUnit(), reasonPnl, r.getTimestamp(), r.getGroupId()));
                reduceOptionPosition(openLongPremiums, key, entry, r.getQuantity());
                return;
            }
            double[] entry = openLongPremiums.getOrDefault(key, new double[]{r.getPricePerUnit(), r.getQuantity()});
            double pnl = (r.getPricePerUnit() - entry[0]) * 100.0 * r.getQuantity() - r.getFeeCharged();
            closed.add(new ClosedTradeRecord(r.getSymbol(), type, r.getQuantity(),
                    entry[0], r.getPricePerUnit(), pnl, r.getTimestamp(), r.getGroupId()));
            reduceOptionPosition(openLongPremiums, key, entry, r.getQuantity());
        }
    }

    /**
     * Parses the net P&L from a multi-leg strategy close reason string.
     * Handles:
     *   "Profit target 50%: +$1976"  → 1976.0
     *   "credit=X close cost=Y"       → X - Y  (legacy IC format)
     *   "credit=X close=Y"            → X - Y  (legacy spread format)
     * Returns NaN for price-stop and DTE exits where no dollar P&L is embedded.
     */
    private static double parseReasonPnl(String reason) {
        if (reason == null) return Double.NaN;

        // Primary format emitted by PremiumSellerRouter: "Profit target 50%: +$1976"
        int plusDollar = reason.indexOf(": +$");
        if (plusDollar >= 0) return parseReasonDouble(reason, plusDollar + 4);

        // Legacy format with embedded credit/cost fields
        int creditIdx = reason.indexOf("credit=");
        if (creditIdx < 0) return Double.NaN;
        double credit = parseReasonDouble(reason, creditIdx + 7);
        if (Double.isNaN(credit)) return Double.NaN;
        int costIdx = reason.indexOf("close cost=");
        if (costIdx >= 0) {
            double closeCost = parseReasonDouble(reason, costIdx + 11);
            return Double.isNaN(closeCost) ? Double.NaN : credit - closeCost;
        }
        int closeIdx = reason.indexOf("close=");
        if (closeIdx < 0) return Double.NaN;
        double closeCost = parseReasonDouble(reason, closeIdx + 6);
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
                boolean bothCalls = first.getType().contains("Call") && r.getType().contains("Call");
                boolean bothPuts  = first.getType().contains("Put")  && r.getType().contains("Put");
                String type = bothCalls ? "Call Spread"
                            : bothPuts  ? "Put Spread"
                            : "Straddle/Strangle";
                result.add(new ClosedTradeRecord(
                        first.getSymbol(), type, first.getQuantity(),
                        first.getEntryRaw() + r.getEntryRaw(),
                        first.getExitRaw()  + r.getExitRaw(),
                        first.getPnlRaw()   + r.getPnlRaw(),
                        Math.min(first.getTimestampRaw(), r.getTimestampRaw())));
            }
        }
        // Unmatched records with a groupId are orphaned spread legs — relabel to show spread origin.
        for (ClosedTradeRecord orphan : pending.values()) {
            if (orphan.getGroupId() != null) {
                String t = orphan.getType().contains("Call") ? "Call Spread" : "Put Spread";
                result.add(new ClosedTradeRecord(orphan.getSymbol(), t, orphan.getQuantity(),
                        orphan.getEntryRaw(), orphan.getExitRaw(), orphan.getPnlRaw(),
                        orphan.getTimestampRaw(), orphan.getGroupId()));
            } else {
                result.add(orphan);
            }
        }
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
        if (sentimentScheduler != null) sentimentScheduler.stop();
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
