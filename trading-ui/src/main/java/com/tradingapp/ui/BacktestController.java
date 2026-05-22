package com.tradingapp.ui;

import com.tradingapp.broker.AlpacaQuoteProvider;
import com.tradingapp.broker.AppConfig;
import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.QuoteProvider;
import com.tradingapp.data.YahooFinanceQuoteProvider;
import com.tradingapp.engine.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.util.Callback;

import java.net.URL;
import java.time.LocalDate;
import java.util.*;

public class BacktestController implements Initializable {

    @FXML private DatePicker backtestStartDate;
    @FXML private DatePicker backtestEndDate;
    @FXML private TextField backtestSymbols;
    @FXML private Button runBacktestButton;
    @FXML private ProgressIndicator backtestProgress;
    @FXML private Label btReturnLabel;
    @FXML private Label btMaxDrawdownLabel;
    @FXML private Label btWinRateLabel;
    @FXML private Label btTradeCountLabel;
    @FXML private ComboBox<String> quoteSourceCombo;
    @FXML private LineChart<String, Number> backtestChart;

    private QuoteProvider quoteProvider;
    private AppConfig appConfig;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        backtestEndDate.setValue(LocalDate.now().minusDays(1));
        backtestSymbols.setText("AAPL");
        quoteSourceCombo.getItems().add("Yahoo Finance");
        quoteSourceCombo.setValue("Yahoo Finance");
        applyEarliestDate(LocalDate.now().minusYears(1));
        quoteSourceCombo.setOnAction(e -> onSourceChanged());
    }

    /**
     * Called by DashboardController after the active quote provider is known.
     * Populates the source combo and enforces the matching date constraint.
     */
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
                btReturnLabel.setText("Start date before earliest available data (" + earliest + " for " + quoteProvider.getName() + ")");
                return;
            }
        }

        runBacktestButton.setDisable(true);
        backtestProgress.setVisible(true);
        btReturnLabel.setText("Fetching data...");

        QuoteProvider provider = quoteProvider;
        Thread t = new Thread(() -> {
            try {
                Map<String, List<HistoricalBar>> barsBySymbol = new LinkedHashMap<>();
                for (String sym : symbols) {
                    List<HistoricalBar> bars = (provider != null)
                            ? provider.getHistoricalBars(sym, startDate, endDate)
                            : new com.tradingapp.data.HistoricalBarFetcher().fetchDailyBars(sym, startDate, endDate);
                    barsBySymbol.put(sym, bars);
                }

                BacktestConfig cfg = new BacktestConfig(symbols, startDate, endDate, 100_000.0);
                BacktestEngine engine = new BacktestEngine(new IndicatorEngine(), new FeeCalculator());
                BacktestResult result = engine.run(cfg, barsBySymbol);

                Platform.runLater(() -> applyResult(result));
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

    private void applyResult(BacktestResult result) {
        backtestChart.getData().clear();

        XYChart.Series<String, Number> series = new XYChart.Series<>();
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
}
