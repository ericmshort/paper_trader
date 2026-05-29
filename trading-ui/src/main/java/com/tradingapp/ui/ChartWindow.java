package com.tradingapp.ui;

import com.tradingapp.data.HistoricalBar;
import com.tradingapp.data.HistoricalBarFetcher;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ChartWindow {

    private Stage stage;
    private TextField symbolField;
    private ToggleGroup periodGroup;
    private ProgressIndicator progress;
    private Label lastLabel, openLabel, highLabel, lowLabel, volumeLabel,
            changeLabel, rangeLabel, rsiLabel;
    private CheckBox sma20CB, sma50CB, sma200CB, bbCB;
    private LineChart<String, Number> priceChart;
    private AreaChart<String, Number> volumeChart;
    private List<HistoricalBar> currentBars = new ArrayList<>();

    public static void open(String initialSymbol) {
        new ChartWindow().show(initialSymbol != null ? initialSymbol.trim().toUpperCase() : "");
    }

    private void show(String initialSymbol) {
        stage = new Stage();
        stage.setTitle("Stock Chart");

        VBox root = new VBox(0);
        root.setStyle("-fx-background-color: #1a1a2e;");

        root.getChildren().addAll(
                buildControls(initialSymbol),
                buildStats(),
                buildIndicatorToggles(),
                buildPriceChart(),
                buildVolumeChart()
        );

        Scene scene = new Scene(root, 1100, 760);
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            darkPlot(priceChart);
            darkPlot(volumeChart);
        });

        if (!initialSymbol.isEmpty()) {
            loadChart(initialSymbol, "1Y");
        }
    }

    // ── Controls row ──────────────────────────────────────────────────────────

    private HBox buildControls(String initialSymbol) {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-background-color: #16213e;");

        symbolField = new TextField(initialSymbol.isEmpty() ? "AAPL" : initialSymbol);
        symbolField.setPrefWidth(85);
        symbolField.setStyle("-fx-background-color: #2a2a4e; -fx-text-fill: #e0e0e0; -fx-font-size: 13px;");
        symbolField.setOnAction(e -> loadChart());

        periodGroup = new ToggleGroup();
        String[] periods = {"1W", "1M", "3M", "6M", "1Y", "2Y", "5Y"};
        List<ToggleButton> btns = new ArrayList<>();
        for (String p : periods) {
            ToggleButton btn = new ToggleButton(p);
            btn.setToggleGroup(periodGroup);
            btn.setUserData(p);
            btn.setStyle("-fx-background-color: #2a2a4e; -fx-text-fill: #c0c0c0; "
                    + "-fx-font-size: 11px; -fx-padding: 4 9;");
            if ("1Y".equals(p)) btn.setSelected(true);
            btn.setOnAction(e -> loadChart());
            btns.add(btn);
        }

        Button loadBtn = new Button("Load");
        loadBtn.setStyle("-fx-background-color: #00aa55; -fx-text-fill: white; -fx-font-weight: bold;");
        loadBtn.setOnAction(e -> loadChart());

        progress = new ProgressIndicator();
        progress.setVisible(false);
        progress.setPrefSize(20, 20);

        box.getChildren().add(label("Symbol:"));
        box.getChildren().add(symbolField);
        box.getChildren().add(gap(8));
        box.getChildren().addAll(btns);
        box.getChildren().add(gap(8));
        box.getChildren().add(loadBtn);
        box.getChildren().add(progress);
        return box;
    }

    // ── Stats row ─────────────────────────────────────────────────────────────

    private HBox buildStats() {
        HBox box = new HBox(18);
        box.setPadding(new Insets(7, 12, 7, 12));
        box.setStyle("-fx-background-color: #16213e;");
        box.setAlignment(Pos.CENTER_LEFT);

        lastLabel   = bigLabel("—");
        openLabel   = label("Open: —");
        highLabel   = label("High: —");
        lowLabel    = label("Low: —");
        volumeLabel = label("Vol: —");
        changeLabel = label("Chg: —");
        rangeLabel  = label("Period Range: —");
        rsiLabel    = label("RSI(14): —");

        box.getChildren().addAll(lastLabel, openLabel, highLabel, lowLabel,
                volumeLabel, changeLabel, rangeLabel, rsiLabel);
        return box;
    }

    // ── Indicator toggles ─────────────────────────────────────────────────────

    private HBox buildIndicatorToggles() {
        HBox box = new HBox(14);
        box.setPadding(new Insets(5, 12, 5, 12));
        box.setStyle("-fx-background-color: #1a1a2e;");
        box.setAlignment(Pos.CENTER_LEFT);

        sma20CB  = checkbox("SMA 20");
        sma50CB  = checkbox("SMA 50");
        sma200CB = checkbox("SMA 200");
        bbCB     = checkbox("Bollinger Bands");

        sma20CB .setOnAction(e -> updateOverlays());
        sma50CB .setOnAction(e -> updateOverlays());
        sma200CB.setOnAction(e -> updateOverlays());
        bbCB    .setOnAction(e -> updateOverlays());

        box.getChildren().addAll(label("Indicators:"), sma20CB, sma50CB, sma200CB, bbCB);
        return box;
    }

    // ── Charts ────────────────────────────────────────────────────────────────

    private LineChart<String, Number> buildPriceChart() {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setTickLabelRotation(45);
        xAxis.setStyle("-fx-tick-label-fill: #909090; -fx-font-size: 10px;");

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Price ($)");
        yAxis.setForceZeroInRange(false);
        yAxis.setStyle("-fx-tick-label-fill: #909090;");

        priceChart = new LineChart<>(xAxis, yAxis);
        priceChart.setAnimated(false);
        priceChart.setCreateSymbols(false);
        priceChart.setLegendVisible(true);
        priceChart.setStyle("-fx-background-color: #1a1a2e;");
        VBox.setVgrow(priceChart, Priority.ALWAYS);
        return priceChart;
    }

    private AreaChart<String, Number> buildVolumeChart() {
        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setTickLabelsVisible(false);
        xAxis.setTickMarkVisible(false);

        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Volume");
        yAxis.setForceZeroInRange(true);
        yAxis.setStyle("-fx-tick-label-fill: #909090;");
        yAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) {
                long v = n.longValue();
                if (v >= 1_000_000_000) return String.format("%.1fB", v / 1_000_000_000.0);
                if (v >= 1_000_000)     return String.format("%.0fM", v / 1_000_000.0);
                if (v >= 1_000)         return String.format("%.0fK", v / 1_000.0);
                return String.valueOf(v);
            }
            @Override public Number fromString(String s) { return 0; }
        });

        volumeChart = new AreaChart<>(xAxis, yAxis);
        volumeChart.setAnimated(false);
        volumeChart.setCreateSymbols(false);
        volumeChart.setLegendVisible(false);
        volumeChart.setMaxHeight(190);
        volumeChart.setMinHeight(140);
        volumeChart.setStyle("-fx-background-color: #1a1a2e;");
        return volumeChart;
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadChart() {
        String sym = symbolField.getText().trim().toUpperCase();
        if (sym.isEmpty()) return;
        String period = periodGroup.getSelectedToggle() != null
                ? (String) periodGroup.getSelectedToggle().getUserData() : "1Y";
        loadChart(sym, period);
    }

    private void loadChart(String symbol, String period) {
        progress.setVisible(true);

        Thread t = new Thread(() -> {
            try {
                LocalDate end   = LocalDate.now();
                LocalDate start = switch (period) {
                    case "1W" -> end.minusWeeks(1);
                    case "1M" -> end.minusMonths(1);
                    case "3M" -> end.minusMonths(3);
                    case "6M" -> end.minusMonths(6);
                    case "2Y" -> end.minusYears(2);
                    case "5Y" -> end.minusYears(5);
                    default   -> end.minusYears(1);
                };
                // Use weekly bars for multi-year periods to keep category count manageable
                String interval = (period.equals("2Y") || period.equals("5Y")) ? "1wk" : "1d";

                List<HistoricalBar> bars = new HistoricalBarFetcher()
                        .fetchBarsWithInterval(symbol, start, end, interval);

                Platform.runLater(() -> {
                    currentBars = bars;
                    renderChart(symbol, bars);
                    stage.setTitle("Chart: " + symbol);
                    progress.setVisible(false);
                });
            } catch (Exception ex) {
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    lastLabel.setText("Error: " + msg);
                    lastLabel.setStyle("-fx-text-fill: #ff4444; -fx-font-size: 13px;");
                    progress.setVisible(false);
                });
            }
        }, "chart-fetch");
        t.setDaemon(true);
        t.start();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderChart(String symbol, List<HistoricalBar> bars) {
        if (bars.isEmpty()) {
            lastLabel.setText("No data for " + symbol);
            return;
        }

        updateStats(bars);
        priceChart.getData().clear();
        volumeChart.getData().clear();

        DateTimeFormatter fmt = labelFmt(bars);

        XYChart.Series<String, Number> priceSeries = new XYChart.Series<>();
        priceSeries.setName(symbol);
        XYChart.Series<String, Number> volSeries = new XYChart.Series<>();

        for (HistoricalBar bar : bars) {
            String lbl = bar.getDate().format(fmt);
            priceSeries.getData().add(new XYChart.Data<>(lbl, bar.getAdjClose()));
            volSeries.getData().add(new XYChart.Data<>(lbl, bar.getVolume()));
        }

        priceChart.getData().add(priceSeries);
        volumeChart.getData().add(volSeries);

        // Defer style application until after JavaFX lays out the nodes
        Platform.runLater(() -> Platform.runLater(() -> {
            styleLineNode(priceSeries, "#00e5ff", 2.0);
            styleVolumeArea(volSeries);
            darkPlot(priceChart);
            darkPlot(volumeChart);
        }));

        updateOverlays();
    }

    private void updateOverlays() {
        // Retain series 0 (price), replace all indicator series
        while (priceChart.getData().size() > 1) {
            priceChart.getData().remove(1);
        }
        if (currentBars.isEmpty()) return;

        DateTimeFormatter fmt = labelFmt(currentBars);
        double[] closes = currentBars.stream().mapToDouble(HistoricalBar::getAdjClose).toArray();

        if (sma20CB.isSelected())  addSma("SMA 20",  20,  closes, fmt, "#ff9800");
        if (sma50CB.isSelected())  addSma("SMA 50",  50,  closes, fmt, "#2196f3");
        if (sma200CB.isSelected()) addSma("SMA 200", 200, closes, fmt, "#f44336");
        if (bbCB.isSelected())     addBollingerBands(closes, fmt);
    }

    private void addSma(String name, int period, double[] closes,
                        DateTimeFormatter fmt, String color) {
        if (closes.length < period) return;
        XYChart.Series<String, Number> s = new XYChart.Series<>();
        s.setName(name);
        for (int i = period - 1; i < closes.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) sum += closes[j];
            s.getData().add(new XYChart.Data<>(
                    currentBars.get(i).getDate().format(fmt), sum / period));
        }
        priceChart.getData().add(s);
        Platform.runLater(() -> Platform.runLater(() -> styleLineNode(s, color, 1.5)));
    }

    private void addBollingerBands(double[] closes, DateTimeFormatter fmt) {
        if (closes.length < 20) return;
        XYChart.Series<String, Number> upper = new XYChart.Series<>();
        upper.setName("BB Upper");
        XYChart.Series<String, Number> lower = new XYChart.Series<>();
        lower.setName("BB Lower");

        for (int i = 19; i < closes.length; i++) {
            double sum = 0;
            for (int j = i - 19; j <= i; j++) sum += closes[j];
            double mean = sum / 20;
            double var  = 0;
            for (int j = i - 19; j <= i; j++) var += (closes[j] - mean) * (closes[j] - mean);
            double std = Math.sqrt(var / 20);
            String lbl = currentBars.get(i).getDate().format(fmt);
            upper.getData().add(new XYChart.Data<>(lbl, mean + 2 * std));
            lower.getData().add(new XYChart.Data<>(lbl, mean - 2 * std));
        }

        priceChart.getData().addAll(upper, lower);
        Platform.runLater(() -> Platform.runLater(() -> {
            styleLineNode(upper, "#78909c", 1.0);
            styleLineNode(lower, "#78909c", 1.0);
        }));
    }

    // ── Stats computation ─────────────────────────────────────────────────────

    private void updateStats(List<HistoricalBar> bars) {
        HistoricalBar last = bars.get(bars.size() - 1);
        HistoricalBar prev = bars.size() > 1 ? bars.get(bars.size() - 2) : last;

        double change    = last.getClose() - prev.getClose();
        double changePct = prev.getClose() > 0 ? change / prev.getClose() * 100 : 0;
        double hi        = bars.stream().mapToDouble(HistoricalBar::getHigh).max().orElse(0);
        double lo        = bars.stream().mapToDouble(HistoricalBar::getLow).min().orElse(0);
        double rsi       = computeRsi(bars, 14);

        lastLabel.setText(String.format("$%.2f", last.getAdjClose()));
        lastLabel.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 16px; -fx-font-weight: bold;");
        openLabel.setText(String.format("Open: $%.2f",  last.getOpen()));
        highLabel.setText(String.format("High: $%.2f",  last.getHigh()));
        lowLabel .setText(String.format("Low: $%.2f",   last.getLow()));
        volumeLabel.setText("Vol: " + fmtVol(last.getVolume()));

        changeLabel.setText(String.format("Chg: %+.2f (%+.2f%%)", change, changePct));
        changeLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: "
                + (change >= 0 ? "#00ff88" : "#ff4444") + ";");

        rangeLabel.setText(String.format("Period Range: $%.2f – $%.2f", lo, hi));

        if (rsi >= 0) {
            rsiLabel.setText(String.format("RSI(14): %.1f", rsi));
            String color = rsi > 70 ? "#ff4444" : rsi < 30 ? "#00ff88" : "#e0e0e0";
            rsiLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        } else {
            rsiLabel.setText("RSI(14): —");
        }
    }

    private static double computeRsi(List<HistoricalBar> bars, int period) {
        if (bars.size() < period + 1) return -1;
        double gains = 0, losses = 0;
        for (int i = bars.size() - period; i < bars.size(); i++) {
            double d = bars.get(i).getClose() - bars.get(i - 1).getClose();
            if (d > 0) gains += d; else losses -= d;
        }
        if (losses == 0) return 100;
        double rs = (gains / period) / (losses / period);
        return 100 - 100.0 / (1 + rs);
    }

    // ── Style helpers ─────────────────────────────────────────────────────────

    private static void styleLineNode(XYChart.Series<?, ?> series, String hex, double width) {
        if (series.getNode() != null) {
            series.getNode().setStyle(
                    "-fx-stroke: " + hex + "; -fx-stroke-width: " + width + "px;");
        }
        // Suppress symbol dots on all data points
        for (XYChart.Data<?, ?> d : series.getData()) {
            if (d.getNode() != null) d.getNode().setVisible(false);
        }
    }

    private static void styleVolumeArea(XYChart.Series<?, ?> series) {
        if (series.getNode() != null) {
            Node line = series.getNode().lookup(".chart-series-area-line");
            if (line != null) line.setStyle("-fx-stroke: #00aa88; -fx-stroke-width: 1.5px;");
            Node fill = series.getNode().lookup(".chart-series-area-fill");
            if (fill != null) fill.setStyle("-fx-fill: rgba(0,170,136,0.25); -fx-stroke: transparent;");
        }
    }

    private static void darkPlot(XYChart<?, ?> chart) {
        Node bg = chart.lookup(".chart-plot-background");
        if (bg != null) bg.setStyle("-fx-background-color: #0e0e22;");
        Node legend = chart.lookup(".chart-legend");
        if (legend != null) legend.setStyle(
                "-fx-background-color: rgba(10,10,30,0.7); -fx-text-fill: #c0c0c0;");
        for (Node n : chart.lookupAll(".chart-legend-item-symbol")) {
            n.setStyle("-fx-background-radius: 2;");
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static DateTimeFormatter labelFmt(List<HistoricalBar> bars) {
        if (bars.size() <= 260) return DateTimeFormatter.ofPattern("MMM dd");
        return DateTimeFormatter.ofPattern("MMM yy");
    }

    private static String fmtVol(long v) {
        if (v >= 1_000_000_000) return String.format("%.2fB", v / 1_000_000_000.0);
        if (v >= 1_000_000)     return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000)         return String.format("%.1fK", v / 1_000.0);
        return String.valueOf(v);
    }

    private static Region gap(double w) {
        Region r = new Region();
        r.setPrefWidth(w);
        return r;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
        return l;
    }

    private static Label bigLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 16px; -fx-font-weight: bold;");
        return l;
    }

    private static CheckBox checkbox(String text) {
        CheckBox cb = new CheckBox(text);
        cb.setStyle("-fx-text-fill: #c0c0c0;");
        return cb;
    }
}
