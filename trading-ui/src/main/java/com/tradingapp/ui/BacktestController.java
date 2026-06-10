package com.tradingapp.ui;

import com.tradingapp.account.Account;
import com.tradingapp.broker.AlpacaHistoricalClient;
import com.tradingapp.broker.AlpacaQuoteProvider;
import com.tradingapp.broker.AppConfig;
import com.tradingapp.data.DayTraderWatchList;
import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.PriceHistory;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.data.YahooFinanceQuoteProvider;
import com.tradingapp.engine.*;
import com.tradingapp.options.BlackScholesEngine;
import com.tradingapp.options.OptionsBacktestEngine;
import com.tradingapp.options.OptionsOrderExecutor;
import com.tradingapp.options.OptionsSignalRouter;
import com.tradingapp.options.OptionsStrategy;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

public class BacktestController implements Initializable {

    @FXML private DatePicker backtestStartDate;
    @FXML private DatePicker backtestEndDate;
    @FXML private TextField backtestSymbols;
    @FXML private Button runBacktestButton;
    @FXML private Button openChartButton;
    @FXML private Button runIntradaySimButton;
    @FXML private ProgressIndicator backtestProgress;
    @FXML private Label btReturnLabel;
    @FXML private Label btMaxDrawdownLabel;
    @FXML private Label btWinRateLabel;
    @FXML private Label btTradeCountLabel;
    @FXML private ComboBox<String> quoteSourceCombo;
    @FXML private ComboBox<String> strategyCombo;
    @FXML private HBox statsBox;
    @FXML private TableView<StrategyResult> comparisonTable;
    @FXML private LineChart<String, Number> backtestChart;

    private QuoteProvider quoteProvider;
    private AppConfig appConfig;

    private static final String EQUITY_LABEL = "Equity (Current)";
    private static final String COMPARE_ALL_LABEL = "Compare All";

    record StrategyResult(String name, double returnPct, double maxDrawdownPct,
                          double winRate, int trades) {
        double riskReward() {
            return maxDrawdownPct > 0 ? returnPct / maxDrawdownPct : 0;
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        backtestEndDate.setValue(LocalDate.now().minusDays(1));
        backtestSymbols.setText("AAPL");
        quoteSourceCombo.getItems().add("Yahoo Finance");
        quoteSourceCombo.setValue("Yahoo Finance");
        applyEarliestDate(LocalDate.now().minusYears(1));
        quoteSourceCombo.setOnAction(e -> onSourceChanged());

        // Strategy selector
        strategyCombo.getItems().add(EQUITY_LABEL);
        for (OptionsStrategy s : OptionsStrategy.values()) {
            strategyCombo.getItems().add(s.getDisplayName());
        }
        strategyCombo.getItems().add(COMPARE_ALL_LABEL);
        strategyCombo.setValue(EQUITY_LABEL);
        strategyCombo.setOnAction(e -> onStrategyChanged());

        // Comparison table columns
        VBox.setVgrow(comparisonTable, Priority.ALWAYS);
        setupComparisonTable();
    }

    private void setupComparisonTable() {
        comparisonTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        comparisonTable.setStyle("-fx-base: #1a1a2e; -fx-control-inner-background: #1a1a2e; -fx-text-fill: #e0e0e0;");

        // Strategy column stretches to fill whatever the other fixed-width columns leave behind
        TableColumn<StrategyResult, String> nameCol = new TableColumn<>("Strategy");
        nameCol.setCellValueFactory(r -> new SimpleStringProperty(r.getValue().name()));
        nameCol.setMinWidth(155);

        TableColumn<StrategyResult, String> retCol = new TableColumn<>("Return %");
        retCol.setCellValueFactory(r -> new SimpleStringProperty(
                String.format("%+.1f%%", r.getValue().returnPct())));
        retCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle(item.startsWith("+") ? "-fx-text-fill: #00ff88;" : "-fx-text-fill: #ff4444;");
            }
        });

        TableColumn<StrategyResult, String> ddCol = new TableColumn<>("Max Drawdown");
        ddCol.setCellValueFactory(r -> new SimpleStringProperty(
                String.format("%.1f%%", r.getValue().maxDrawdownPct())));

        TableColumn<StrategyResult, String> rrCol = new TableColumn<>("Return/Risk");
        rrCol.setCellValueFactory(r -> new SimpleStringProperty(
                String.format("%.2f", r.getValue().riskReward())));

        TableColumn<StrategyResult, String> wrCol = new TableColumn<>("Win Rate");
        wrCol.setCellValueFactory(r -> new SimpleStringProperty(
                String.format("%.1f%%", r.getValue().winRate())));

        TableColumn<StrategyResult, String> tradeCol = new TableColumn<>("Trades");
        tradeCol.setCellValueFactory(r -> new SimpleStringProperty(
                String.valueOf(r.getValue().trades())));

        // Fix all data columns to the same width so header and cells can never drift
        fixedWidth(retCol,   92);
        fixedWidth(ddCol,   110);
        fixedWidth(rrCol,    92);
        fixedWidth(wrCol,    82);
        fixedWidth(tradeCol, 62);

        comparisonTable.getColumns().addAll(nameCol, retCol, ddCol, rrCol, wrCol, tradeCol);
    }

    private static void fixedWidth(TableColumn<?, ?> col, double width) {
        col.setMinWidth(width);
        col.setMaxWidth(width);
        col.setPrefWidth(width);
    }

    private void onStrategyChanged() {
        boolean isCompare = COMPARE_ALL_LABEL.equals(strategyCombo.getValue());
        comparisonTable.setVisible(isCompare);
        comparisonTable.setManaged(isCompare);
        backtestChart.setVisible(!isCompare);
        backtestChart.setManaged(!isCompare);
        backtestChart.setLegendVisible(false);
    }

    public void setContext(AppConfig config, QuoteProvider activeProvider) {
        this.appConfig = config;
        quoteSourceCombo.getItems().setAll("Yahoo Finance");
        if (!config.getAlpacaApiKey().isBlank() && !config.getAlpacaApiSecret().isBlank()) {
            quoteSourceCombo.getItems().add("Alpaca");
        }
        quoteSourceCombo.setValue(activeProvider.getName());
        this.quoteProvider = activeProvider;
        applyEarliestDate(activeProvider.getEarliestBacktestDate());
    }

    private void onSourceChanged() {
        String selected = quoteSourceCombo.getValue();
        if (selected == null) return;
        QuoteProvider newProvider = "Alpaca".equals(selected) && appConfig != null
                ? new AlpacaQuoteProvider(appConfig)
                : new YahooFinanceQuoteProvider();
        quoteProvider = newProvider;
        applyEarliestDate(newProvider.getEarliestBacktestDate());
    }

    private void applyEarliestDate(LocalDate earliest) {
        Callback<DatePicker, DateCell> factory = dp -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisabled(empty || date.isBefore(earliest));
            }
        };
        backtestStartDate.setDayCellFactory(factory);
        backtestEndDate.setDayCellFactory(factory);
        LocalDate defaultStart = LocalDate.now().minusYears(1);
        backtestStartDate.setValue(defaultStart.isBefore(earliest) ? earliest : defaultStart);
    }

    @FXML
    public void openChart() {
        String raw = backtestSymbols.getText();
        String firstSymbol = raw.contains(",") ? raw.substring(0, raw.indexOf(',')).trim() : raw.trim();
        ChartWindow.open(firstSymbol.isEmpty() ? "AAPL" : firstSymbol.toUpperCase());
    }

    @FXML
    public void runBacktest() {
        String rawSymbols = backtestSymbols.getText();
        List<String> symbols = new ArrayList<>();
        for (String s : rawSymbols.split(",")) {
            String trimmed = s.trim().toUpperCase();
            if (!trimmed.isEmpty()) symbols.add(trimmed);
        }
        if (symbols.isEmpty()) {
            btReturnLabel.setText("Enter at least one symbol");
            return;
        }

        LocalDate startDate = backtestStartDate.getValue();
        LocalDate endDate = backtestEndDate.getValue();
        if (startDate == null || endDate == null || !startDate.isBefore(endDate)) {
            btReturnLabel.setText("Invalid date range");
            return;
        }

        if (quoteProvider != null) {
            LocalDate earliest = quoteProvider.getEarliestBacktestDate();
            if (startDate.isBefore(earliest)) {
                btReturnLabel.setText("Start date before earliest available data ("
                        + earliest + " for " + quoteProvider.getName() + ")");
                return;
            }
        }

        runBacktestButton.setDisable(true);
        backtestProgress.setVisible(true);
        btReturnLabel.setText("Fetching data...");

        String selected = strategyCombo.getValue();
        QuoteProvider provider = quoteProvider;

        Thread t = new Thread(() -> {
            try {
                Map<String, List<HistoricalBar>> barsBySymbol = fetchBars(symbols, startDate, endDate, provider);
                BacktestConfig cfg = new BacktestConfig(symbols, startDate, endDate, 100_000.0);

                if (EQUITY_LABEL.equals(selected)) {
                    BacktestResult result = new BacktestEngine(new IndicatorEngine(), new FeeCalculator())
                            .run(cfg, barsBySymbol);
                    Platform.runLater(() -> applySingleResult(result, EQUITY_LABEL));

                } else if (COMPARE_ALL_LABEL.equals(selected)) {
                    List<StrategyResult> rows = runAllStrategies(cfg, barsBySymbol);
                    Platform.runLater(() -> applyComparisonResults(rows));

                } else {
                    OptionsStrategy optStrat = strategyForName(selected);
                    if (optStrat == null) return;
                    BacktestResult result = new OptionsBacktestEngine(
                            new IndicatorEngine(), new BlackScholesEngine(), new FeeCalculator())
                            .run(optStrat, cfg, barsBySymbol);
                    Platform.runLater(() -> applySingleResult(result, selected));
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    btReturnLabel.setText("Error: " + msg);
                });
            } finally {
                Platform.runLater(() -> {
                    runBacktestButton.setDisable(false);
                    backtestProgress.setVisible(false);
                });
            }
        }, "backtest");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void runIntradaySim() {
        if (appConfig == null) {
            btReturnLabel.setText("Alpaca config required for intraday sim");
            return;
        }
        if (appConfig.getAlpacaApiKey().isBlank() || appConfig.getAlpacaApiSecret().isBlank()) {
            btReturnLabel.setText("Set Alpaca API keys in Settings first");
            return;
        }

        runIntradaySimButton.setDisable(true);
        runBacktestButton.setDisable(true);
        backtestProgress.setVisible(true);
        btReturnLabel.setText("Fetching minute bars...");

        List<String> watchlist = new ArrayList<>(DayTraderWatchList.SYMBOLS);
        AppConfig cfg = appConfig;

        Thread t = new Thread(() -> {
            try {
                AlpacaHistoricalClient client = new AlpacaHistoricalClient(cfg);

                LocalDate endDate = LocalDate.now().minusDays(1);
                while (endDate.getDayOfWeek() == DayOfWeek.SATURDAY
                        || endDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    endDate = endDate.minusDays(1);
                }
                LocalDate startDate = endDate.minusDays(140);

                Map<String, List<IntradayBar>> barsBySymbol = new LinkedHashMap<>();
                int idx = 0;
                for (String sym : watchlist) {
                    idx++;
                    final int cur = idx;
                    final int tot = watchlist.size();
                    try {
                        List<IntradayBar> bars = client.fetchMinuteBars(sym, startDate, endDate,
                                msg -> Platform.runLater(() -> btReturnLabel.setText("[" + cur + "/" + tot + "] " + msg)));
                        if (!bars.isEmpty()) barsBySymbol.put(sym, bars);
                    } catch (Exception e) {
                        Platform.runLater(() -> btReturnLabel.setText("Skip " + sym + ": " + e.getMessage()));
                    }
                }

                if (barsBySymbol.isEmpty()) {
                    Platform.runLater(() -> btReturnLabel.setText("No data fetched"));
                    return;
                }

                Platform.runLater(() -> btReturnLabel.setText("Running intraday sim..."));

                // Placeholder account/history — replaced by engine's shared objects in onBacktestInit
                OptionsOrderExecutor optExec = new OptionsOrderExecutor(new Account(), null);
                OptionsSignalRouter optRouter = new OptionsSignalRouter(
                        new BlackScholesEngine(), optExec, new Account(), new PriceHistory(),
                        msg -> {}, null);
                optRouter.setOptionsAllowlist(Set.of("SPY","AMZN","PLTR","META","MSFT","NVDA","AAPL","NOK","F"));
                optRouter.setCallsDisabledSymbols(Set.of("MSFT"));
                optRouter.setPutsDisabledSymbols(Set.of("NVDA"));

                IntradayBacktestEngine engine = new IntradayBacktestEngine(new IndicatorEngine(), new FeeCalculator());
                IntradayBacktestResult result = engine.run(watchlist, barsBySymbol, 100_000.0, optRouter,
                        msg -> Platform.runLater(() -> btReturnLabel.setText(msg)));

                Platform.runLater(() -> applySingleResult(result, "Intraday 100d"));

            } catch (Exception e) {
                Platform.runLater(() -> {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    btReturnLabel.setText("Error: " + msg);
                });
            } finally {
                Platform.runLater(() -> {
                    runIntradaySimButton.setDisable(false);
                    runBacktestButton.setDisable(false);
                    backtestProgress.setVisible(false);
                });
            }
        }, "intraday-sim");
        t.setDaemon(true);
        t.start();
    }

    private Map<String, List<HistoricalBar>> fetchBars(List<String> symbols, LocalDate start, LocalDate end,
                                                        QuoteProvider provider) throws Exception {
        Map<String, List<HistoricalBar>> barsBySymbol = new LinkedHashMap<>();
        for (String sym : symbols) {
            List<HistoricalBar> bars = (provider != null)
                    ? provider.getHistoricalBars(sym, start, end)
                    : new com.tradingapp.data.HistoricalBarFetcher().fetchDailyBars(sym, start, end);
            barsBySymbol.put(sym, bars);
        }
        return barsBySymbol;
    }

    private List<StrategyResult> runAllStrategies(BacktestConfig cfg,
                                                   Map<String, List<HistoricalBar>> barsBySymbol) {
        List<StrategyResult> rows = new ArrayList<>();

        // Equity baseline
        BacktestResult eq = new BacktestEngine(new IndicatorEngine(), new FeeCalculator())
                .run(cfg, barsBySymbol);
        rows.add(toRow(EQUITY_LABEL, eq));

        // All options strategies
        OptionsBacktestEngine optEngine = new OptionsBacktestEngine(
                new IndicatorEngine(), new BlackScholesEngine(), new FeeCalculator());
        for (OptionsStrategy s : OptionsStrategy.values()) {
            BacktestResult r = optEngine.run(s, cfg, barsBySymbol);
            rows.add(toRow(s.getDisplayName(), r));
        }

        // Sort by return/risk descending (risk-adjusted performance)
        rows.sort(Comparator.comparingDouble(StrategyResult::riskReward).reversed());
        return rows;
    }

    private StrategyResult toRow(String name, BacktestResult r) {
        return new StrategyResult(name, r.getTotalReturnPct(), r.getMaxDrawdownPct(),
                r.winRate(), r.getTotalTrades());
    }

    private void applySingleResult(BacktestResult result, String strategyName) {
        backtestChart.getData().clear();
        backtestChart.setLegendVisible(false);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(strategyName);
        for (BacktestDataPoint pt : result.getEquityCurve()) {
            series.getData().add(new XYChart.Data<>(pt.getDate().toString(), pt.getPortfolioValue()));
        }
        backtestChart.getData().add(series);

        double ret = result.getTotalReturnPct();
        btReturnLabel.setText(String.format("Return: %+.1f%%", ret));
        btReturnLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + (ret >= 0 ? "#00ff88;" : "#ff4444;"));
        btMaxDrawdownLabel.setText(String.format("Max Drawdown: %.1f%%", result.getMaxDrawdownPct()));
        btWinRateLabel.setText(String.format("Win Rate: %.1f%%", result.winRate()));
        btTradeCountLabel.setText("Trades: " + result.getTotalTrades());
    }

    private void applyComparisonResults(List<StrategyResult> rows) {
        comparisonTable.getItems().setAll(rows);

        // Show range summary in stat labels for quick reference
        if (rows.isEmpty()) return;
        double minRet = rows.stream().mapToDouble(StrategyResult::returnPct).min().orElse(0);
        double maxRet = rows.stream().mapToDouble(StrategyResult::returnPct).max().orElse(0);
        StrategyResult best = rows.get(0);
        btReturnLabel.setText(String.format("Returns: %+.1f%% to %+.1f%%", minRet, maxRet));
        btReturnLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #e0e0e0;");
        btMaxDrawdownLabel.setText(String.format("Best Risk/Reward: %.2f (%s)", best.riskReward(), best.name()));
        btWinRateLabel.setText(String.format("Best Return: %+.1f%% (%s)",
                rows.stream().mapToDouble(StrategyResult::returnPct).max().orElse(0),
                rows.stream().max(Comparator.comparingDouble(StrategyResult::returnPct)).map(StrategyResult::name).orElse("")));
        btTradeCountLabel.setText(String.format("Strategies: %d", rows.size()));
    }

    private static OptionsStrategy strategyForName(String name) {
        for (OptionsStrategy s : OptionsStrategy.values()) {
            if (s.getDisplayName().equals(name)) return s;
        }
        return null;
    }
}
