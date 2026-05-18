package com.tradingapp.ui;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.HistoricalBarFetcher;
import com.tradingapp.engine.*;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;

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
    @FXML private LineChart<String, Number> backtestChart;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        backtestStartDate.setValue(LocalDate.now().minusYears(1));
        backtestEndDate.setValue(LocalDate.now().minusDays(1));
        backtestSymbols.setText("AAPL");
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

        runBacktestButton.setDisable(true);
        backtestProgress.setVisible(true);
        btReturnLabel.setText("Fetching data...");

        Thread t = new Thread(() -> {
            try {
                HistoricalBarFetcher fetcher = new HistoricalBarFetcher();
                Map<String, List<HistoricalBar>> barsBySymbol = new LinkedHashMap<>();
                for (String sym : symbols) {
                    barsBySymbol.put(sym, fetcher.fetchDailyBars(sym, startDate, endDate));
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
