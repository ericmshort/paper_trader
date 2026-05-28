package com.tradingapp.ui;

import com.tradingapp.account.Account;
import com.tradingapp.account.OptionsPosition;
import com.tradingapp.account.Position;
import com.tradingapp.account.SafetyStop;
import com.tradingapp.account.TransactionLog;
import com.tradingapp.account.TransactionRecord;
import com.tradingapp.broker.AlpacaBroker;
import com.tradingapp.broker.AlpacaQuoteProvider;
import com.tradingapp.broker.AppConfig;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
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
    @FXML private TableColumn<OptionsPositionRow, String> optColEntryPrem;
    @FXML private TableColumn<OptionsPositionRow, String> optColCurrentPrem;
    @FXML private TableColumn<OptionsPositionRow, String> optColUnrealizedPnl;
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
    private BlackScholesEngine bsEngine;
    private ScheduledExecutorService scheduler;
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
            settingsPanelController.setOnSettingsSaved(cfg ->
                    Platform.runLater(() -> researchArea.appendText(
                            "\nSettings saved. Broker/quote changes take effect on next restart.\n")));
            settingsPanelController.setOnBrokerReset(this::handleBrokerReset);
        }
    }

    private void startTradingComponents(AppConfig appConfig) {
        researchArea.setText("Waiting for market data...\n\nMarket hours: 9:30 AM – 4:00 PM ET"
                + "\nWatching 100 large-cap and small-cap US stocks."
                + "\nBroker: " + SettingsController.brokerTypeLabel(appConfig.getBrokerType())
                + " | Quotes: " + appConfig.getQuoteProviderType().name());

        QuoteProvider quoteProvider;
        if (appConfig.getQuoteProviderType() == AppConfig.QuoteProviderType.ALPACA && appConfig.isAlpacaBroker()) {
            quoteProvider = new AlpacaQuoteProvider(appConfig);
        } else {
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
        OptionsSignalRouter optionsRouter = new OptionsSignalRouter(
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

        List<String> allSymbols = new ArrayList<>();
        allSymbols.addAll(LargeCapWatchList.SYMBOLS);
        allSymbols.addAll(SmallCapWatchList.SYMBOLS);

        TradingLoop tradingLoop = new TradingLoop(quoteProvider, priceHistory,
                new IndicatorEngine(), new TrailingStopMonitor(), brokerClient, feeCalc,
                allSymbols, researchCb, uiRefresh, account,
                optionsRouter, mlEval, trainingCallback);
        tradingLoop.setDailyLossLimitPct(appConfig.getDailyLossLimitPct() / 100.0);
        tradingLoop.setAvoidOvernightHolds(appConfig.isAvoidOvernightHolds());
        tradingLoop.setMarketRegimeFilterEnabled(appConfig.isMarketRegimeFilterEnabled());
        tradingLoop.setEarningsCalendar(earningsCalendar);
        tradingLoop.setEarningsBlackoutDays(appConfig.getEarningsBlackoutDays());
        IndicatorEngine rsiEngine = new IndicatorEngine();
        for (String sym : allSymbols) {
            tradingLoop.registerStrategy(sym, new RSIMomentumStrategy(rsiEngine));
        }
        optionsRouter.setUptrendSupplier(tradingLoop::isUptrend);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trading-loop");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(tradingLoop, 0, 30, TimeUnit.SECONDS);

        // Seed price history from 200 days of daily bars so MACrossover is immediately usable.
        // Runs in background — does not block the trading loop or the UI.
        QuoteProvider seedProvider = quoteProvider;
        Thread seedThread = new Thread(() -> {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(280); // extra buffer for weekends/holidays
            int seeded = 0;
            // Include SPY for the market-regime filter (not traded, just tracked)
            List<String> symbolsToSeed = new ArrayList<>(allSymbols);
            symbolsToSeed.add("SPY");
            for (String sym : symbolsToSeed) {
                try {
                    var bars = seedProvider.getHistoricalBars(sym, start, end);
                    if (bars != null && !bars.isEmpty()) {
                        priceHistory.seed(sym, bars);
                        seeded++;
                    }
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

            // Reset chart and price history
            priceHistory = new PriceHistory();
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
        colAction.setCellValueFactory(new PropertyValueFactory<>("action"));
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
        optColEntryPrem.setCellValueFactory(new PropertyValueFactory<>("entryPremium"));
        optColCurrentPrem.setCellValueFactory(new PropertyValueFactory<>("currentPremium"));
        optColUnrealizedPnl.setCellValueFactory(new PropertyValueFactory<>("unrealizedPnl"));
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
        for (OptionsPosition pos : account.getOptionsPositions().values()) {
            double currentPrem = computeOptionCurrentPremium(pos);
            totalUnrealized += (currentPrem - pos.getPremiumPaid()) * 100 * pos.getContracts();
            rows.add(new OptionsPositionRow(
                    pos.getSymbol(), pos.getType(), pos.getStrike(),
                    pos.getExpiry().toString(), pos.getContracts(),
                    pos.getPremiumPaid(), currentPrem));
        }
        optionsTable.setItems(FXCollections.observableArrayList(rows));
        return totalUnrealized;
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
        List<Double> prices = priceHistory.getPrices(pos.getSymbol());
        if (prices.isEmpty()) return pos.getPremiumPaid();
        double S = prices.get(prices.size() - 1);
        double T = bsEngine.timeToExpiry(pos.getExpiry());
        if (T <= 0) return 0.0;
        double vol = bsEngine.historicalVol(prices);
        if (!Double.isFinite(vol) || vol == 0) return 0.0;
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
        List<TransactionRecord> history = transactionLog.findAll();
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
        optionsTotalUnrealizedLabel.setText(formatUnrealizedPnl("Total Unrealized P&L", optTotalUnrealized));
        stockTotalUnrealizedLabel.setText(formatUnrealizedPnl("Total Unrealized P&L", stkTotalUnrealized));

        List<ClosedTradeRecord> closedTrades = computeClosedTrades();
        int wins = (int) closedTrades.stream().filter(t -> t.getPnlRaw() > 0).count();
        int losses = (int) closedTrades.stream().filter(t -> t.getPnlRaw() <= 0).count();
        int total = wins + losses;
        double winRate = total > 0 ? (wins * 100.0 / total) : 0.0;
        winsLabel.setText("Wins: " + wins);
        lossesLabel.setText("Losses: " + losses);
        winRateLabel.setText(String.format("Win Rate: %.1f%%", winRate));
        // Derive realized P&L from actual Alpaca cash/positions rather than transaction log prices,
        // which may have stale BS-computed buy prices that haven't been corrected yet.
        double realizedPnl = totalPortfolio - Account.STARTING_BALANCE - (optTotalUnrealized + stkTotalUnrealized);
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
        double realizedPnl = totalPortfolio - Account.STARTING_BALANCE - unrealizedPnl;
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
        Map<String, double[]> openOptionPremiums = new HashMap<>(); // key -> {premium, contracts}
        List<ClosedTradeRecord> closed = new ArrayList<>();

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
                case CALL_BUY -> accumulateOption(openOptionPremiums, r, "_CALL");
                case PUT_BUY  -> accumulateOption(openOptionPremiums, r, "_PUT");
                case CALL_SELL -> {
                    double[] entry = openOptionPremiums.getOrDefault(r.getSymbol() + "_CALL",
                            new double[]{r.getPricePerUnit(), r.getQuantity()});
                    int sellQty = r.getQuantity();
                    double pnl = (r.getPricePerUnit() - entry[0]) * 100 * sellQty - r.getFeeCharged();
                    closed.add(new ClosedTradeRecord(r.getSymbol(), "Call Option", sellQty,
                            entry[0], r.getPricePerUnit(), pnl, r.getTimestamp()));
                    reduceOptionPosition(openOptionPremiums, r.getSymbol() + "_CALL", entry, sellQty);
                }
                case PUT_SELL -> {
                    double[] entry = openOptionPremiums.getOrDefault(r.getSymbol() + "_PUT",
                            new double[]{r.getPricePerUnit(), r.getQuantity()});
                    int sellQty = r.getQuantity();
                    double pnl = (r.getPricePerUnit() - entry[0]) * 100 * sellQty - r.getFeeCharged();
                    closed.add(new ClosedTradeRecord(r.getSymbol(), "Put Option", sellQty,
                            entry[0], r.getPricePerUnit(), pnl, r.getTimestamp()));
                    reduceOptionPosition(openOptionPremiums, r.getSymbol() + "_PUT", entry, sellQty);
                }
            }
        }

        Collections.reverse(closed); // most recent first
        return closed;
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

        double availableCash = account.getBalance();
        double totalPortfolio = availableCash + computeStockHoldings() + computeOptionHoldings();
        double unrealizedPnl = computeStockUnrealizedPnL() + computeOptionsUnrealizedPnL();
        double totalPnl = totalPortfolio - Account.STARTING_BALANCE - unrealizedPnl;
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
