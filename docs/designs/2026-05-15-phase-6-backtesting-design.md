---
design_type: phase
created_at: 2026-05-15
---

# Phase 6: Backtesting — Design

## Intent Contract

```
intent: Add a Backtest tab to the dashboard that fetches Yahoo Finance daily OHLCV history
        for user-specified symbols and date range, replays the existing indicator strategy
        day by day, and displays an equity curve plus summary stats (total return,
        max drawdown, win rate, trade count).
constraints: No new Maven modules; no changes to live trading logic or account state;
             historical fetch uses the same Yahoo Finance v8 chart API already in the project;
             backtest runs on a background thread and never blocks the FX thread;
             options backtesting is out of scope (stocks only).
success_criteria: Backtest tab visible alongside Live tab; user can enter symbols, date range,
  and click Run; equity curve chart renders; all four stat labels update; all tests pass;
  dashboard opens clean.
risk_level: low
```

## Verification Contract

```
verify_steps:
  - run tests: mvn test -f /Users/ericshort/AIProjects/TradingApp/pom.xml
  - check: dashboard opens with two tabs ("Live" and "Backtest")
  - check: entering "AAPL", 1-year range, clicking Run — equity curve populates
  - check: total return %, max drawdown %, win rate %, trade count all show values
  - check: entering an invalid symbol — error message appears in stats row (no crash)
  - confirm: live trading tab continues functioning normally while backtest tab is open
```

## Governance Contract

```
approval_gates:
  - Human smoke test: Backtest tab renders; Run completes for AAPL 1-year; equity curve visible
rollback: Revert dashboard.fxml (restore VBox root), delete backtest-panel.fxml and
          BacktestController.java, delete trading-engine backtest classes and trading-data
          HistoricalBarFetcher/HistoricalBar
ownership: ericmshort@gmail.com
```

## Scope

| In | Out |
|---|---|
| `HistoricalBar` POJO and `HistoricalBarFetcher` in `trading-data` | Options backtesting |
| `BacktestConfig`, `BacktestDataPoint`, `BacktestResult`, `BacktestEngine` in `trading-engine` | Intraday (1-min) historical bars |
| `BacktestController` and `backtest-panel.fxml` in `trading-ui` | Parameter sweeps / strategy optimization |
| `dashboard.fxml` root changed from `VBox` to `TabPane` (Live + Backtest tabs) | Benchmark comparison (buy-and-hold) |
| Equity curve `LineChart<String,Number>` with date strings on X-axis | Per-trade log table in backtest panel |
| Summary stats: total return %, max drawdown %, win rate %, trade count | ML weight usage in backtest (plain vote counting only) |
| `BacktestEngineTest` with synthetic price history | Network-dependent HistoricalBarFetcher integration test |
| Progress indicator during fetch + run | Caching of historical data to disk |

## Decisions

| # | Decision | Choice | Rejected Alternatives |
|---|---|---|---|
| 1 | Historical data endpoint | Yahoo Finance v8 chart API (`?interval=1d&period1=...&period2=...`) | yahoofinance-api third-party library (not already in project); Alpha Vantage (rate limits) |
| 2 | Engine module | Add classes to `trading-engine` (no new module) | New `trading-backtest` module (more Maven setup overhead for limited gain) |
| 3 | UI structure | Separate `backtest-panel.fxml` + `BacktestController` via `fx:include` | All backtest fields added to `DashboardController` (would bloat the existing controller) |
| 4 | Dashboard root element | `TabPane` replacing the current `VBox` root | 4th collapsible section in existing VBox (too tall); separate Stage window (two-window UX) |
| 5 | Chart X-axis type | `CategoryAxis` with date strings ("2025-01-15") | `NumberAxis` with epoch timestamps (harder to read); `DateAxis` (not in standard JavaFX) |
| 6 | Signal scoring in backtest | Plain vote counting (buys ≥ 2) | Reusing in-memory MLSignalEvaluator weights (couples backtest to live session state) |
| 7 | Position sizing | Reuse `FeeCalculator.maxShares()` (5% of cash per trade) | Fixed share count (ignores account size); fixed dollar amount |
| 8 | Multi-symbol capital | Shared portfolio — all symbols draw from one cash pool, buying at most one position per symbol at a time | Separate $100K per symbol (inflates apparent returns) |

## Surface

**New: `trading-data/HistoricalBar.java`** — POJO with fields `String symbol`, `LocalDate date`, `double open`, `double high`, `double low`, `double close`, `long volume`. Used as the unit of historical data passed to `BacktestEngine`.

**New: `trading-data/HistoricalBarFetcher.java`** — Fetches daily OHLCV bars from the Yahoo Finance v8 chart API (`/v8/finance/chart/{symbol}?interval=1d&period1={epochSecs}&period2={epochSecs}`). Parses the `timestamp`, `quote[0].close`, `quote[0].volume` arrays from the JSON response. Reuses the same `HttpClient` retry pattern as `YahooFinanceClient`. Returns `List<HistoricalBar>` sorted oldest-first. Throws `DataUnavailableException` on fetch failure.

**New: `trading-engine/BacktestConfig.java`** — Value object: `List<String> symbols`, `LocalDate startDate`, `LocalDate endDate`, `double startingBalance` (default $100,000).

**New: `trading-engine/BacktestDataPoint.java`** — Value object: `LocalDate date`, `double portfolioValue`. Used to populate the equity curve series.

**New: `trading-engine/BacktestResult.java`** — Holds `List<BacktestDataPoint> equityCurve`, `double totalReturnPct`, `double maxDrawdownPct`, `int wins`, `int losses`, `int totalTrades`. `winRate()` is derived (`wins * 100.0 / totalTrades`).

**New: `trading-engine/BacktestEngine.java`** — Constructor takes `IndicatorEngine indicatorEngine`, `FeeCalculator feeCalc`. Method `BacktestResult run(BacktestConfig config, Map<String, List<HistoricalBar>> barsBySymbol)`: iterates trading days oldest-to-newest; for each day and each symbol accumulates running price and volume lists, calls `indicatorEngine.evaluateAll()`, counts buy/sell votes; buys when `buys >= 2` and no current position and `maxShares() > 0`; sells when `sells >= 2` and holding a position; records cash + holdings value as an equity data point; computes final stats. Max drawdown is `(peak - trough) / peak` over the equity curve.

**New: `trading-ui/BacktestController.java`** — `@FXML` fields: `DatePicker backtestStartDate`, `DatePicker backtestEndDate`, `TextField backtestSymbols`, `Button runBacktestButton`, `ProgressIndicator backtestProgress`, `Label btReturnLabel`, `Label btMaxDrawdownLabel`, `Label btWinRateLabel`, `Label btTradeCountLabel`, `LineChart<String,Number> backtestChart`. `initialize()` sets default start date to one year ago and end date to yesterday. `@FXML runBacktest()` handler: parses symbols (comma-split, trim, uppercase), validates date range, disables button, shows progress indicator, launches `Task<BacktestResult>` on a daemon thread that calls `HistoricalBarFetcher.fetch()` then `BacktestEngine.run()`, on success posts chart data and label updates to FX thread, on failure sets a descriptive error string in `btReturnLabel`, always re-enables button.

**New: `trading-ui/backtest-panel.fxml`** — Root `VBox` (dark background). Row 1: `HBox` config bar with two `DatePicker` controls, a `TextField` for symbols, a "Run Backtest" `Button`, and a `ProgressIndicator`. Row 2: `HBox` stats bar with four `Label` controls. Row 3: `LineChart` with `CategoryAxis` (X, no label) and `NumberAxis` (Y, label "Portfolio ($)"), `animated="false"`, `createSymbols="false"`, `legendVisible="false"`, `VBox.vgrow="ALWAYS"`.

**Modified: `trading-ui/dashboard.fxml`** — Root element changes from `VBox` to `TabPane` (`tabClosingPolicy="UNAVAILABLE"`). Tab 1 (`text="Live"`): wraps the existing three-section VBox content unchanged. Tab 2 (`text="Backtest"`): contains `<fx:include fx:id="backtestPanel" source="backtest-panel.fxml"/>`.

**Modified: `trading-ui/DashboardController.java`** — Adds `@FXML private BacktestController backtestPanelController;` field (JavaFX nested controller injection via `fx:id="backtestPanel"` + `Controller` suffix). No other changes to `DashboardController`.

## Risks & Open Questions

| Risk | Likelihood | Mitigation |
|---|---|---|
| Yahoo Finance v8 historical endpoint returns no data for date range | Medium — thin trading days near start/end of range | Guard: if fewer than 20 bars returned, show "Insufficient data for range" in stats row |
| CategoryAxis with many date labels causes X-axis overcrowding | Medium — 1-year range = ~252 trading days | Set `tickLabelRotation="90"` and show every 20th label via `tickUnit` workaround or skip-label CSS |
| `IndicatorEngine.evaluateAll()` returns empty signals for first N bars (insufficient history for RSI-14, MA-200) | Certain — by design | Guard: skip buy/sell evaluation for any day where `prices.size() < 200` |
| BacktestEngine producing unrealistic returns due to look-ahead bias | Low — all indicators use only prices up to and including the current bar | Design invariant: `evaluateAll(prices.subList(0, i+1), volumes.subList(0, i+1), close)` |
| fx:include nested controller injection not wired correctly | Low — standard JavaFX pattern | Step 3 compile verify catches missing @FXML field linkage; smoke test catches runtime injection |
