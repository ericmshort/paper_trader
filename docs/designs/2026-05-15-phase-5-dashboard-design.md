---
design_type: phase
created_at: 2026-05-15
---

# Phase 5: Dashboard Polish — Design

## Intent Contract

```
intent: Replace single balance label with four portfolio-value fields, add equity curve
        chart + open options panel, fix halt threshold to $0 instead of $100.
constraints: No trading logic changes except SafetyStop threshold; no new Maven modules;
             all data derived from already-live in-memory state; existing tests pass.
success_criteria: Four portfolio labels live; equity curve plots ≤200 points; options
  table renders positions with current premium; SafetyStop halts at $0; win rate %
  visible; all tests pass; dashboard opens clean.
risk_level: low
```

## Verification Contract

```
verify_steps:
  - run tests: mvn test -f /Users/ericshort/AIProjects/TradingApp/pom.xml
  - check: all four portfolio labels render and update each tick
  - check: equity curve chart appears in top-left with data points accumulating
  - check: options positions table renders (empty on fresh start is fine)
  - check: SafetyStop halts at balance <= 0 (AccountTest covers this)
  - confirm: dashboard window opens without errors
```

## Governance Contract

```
approval_gates:
  - Human smoke test: dashboard opens; four portfolio labels visible; equity chart
    accumulates data points; options table renders; no startup errors
rollback: Revert dashboard.fxml, DashboardController, SafetyStop to Phase 4 state
ownership: ericmshort@gmail.com
```

## Scope

| In | Out |
|---|---|
| Four portfolio-value labels replacing single balance label | Any change to buy/sell trading logic |
| Win rate % label in performance bar | Candlestick or per-symbol price charts |
| Equity curve LineChart (total portfolio value, last 200 ticks) | Historical chart data surviving restarts |
| Open options positions TableView (current theoretical premium + unrealized P&L) | Options Greeks panel |
| SafetyStop threshold: `< 100.0` → `<= 0.0` | Configurable halt threshold |
| Halt label text: "portfolio exhausted" | Margin / credit trading |
| Promote priceHistory + bsEngine to DashboardController instance fields | Any new Maven module |

## Decisions

| # | Decision | Choice | Rejected Alternatives |
|---|---|---|---|
| 1 | Equity X-axis | Sequential tick counter (int) | Wall-clock time (gaps when market closed confuse the chart) |
| 2 | Chart data cap | Last 200 points in-memory ObservableList | Unbounded list (memory leak on long sessions) |
| 3 | Options row type | Plain POJO (OptionsPositionRow) rebuilt each tick | JavaFX ObservableList with property binding (over-engineered for tick-refresh pattern) |
| 4 | Option current premium | Black-Scholes via existing bsEngine (already available in DashboardController) | Re-fetch from Yahoo (unreliable) |
| 5 | Layout root | Replace BorderPane with VBox (3 rows: labels / chart+options / research+history) | Keep BorderPane and overload regions (awkward fit for 3-row content) |
| 6 | SafetyStop halt message | "SAFETY STOP: balance $X is zero or negative. All trading halted." | No change (misleading "below $100" message) |

## Surface

**Modified: `trading-account/SafetyStop.java`** — `MINIMUM_BALANCE` constant changes from `100.0` to `0.0`; comparison changes from `< MINIMUM_BALANCE` to `<= MINIMUM_BALANCE`; console message updated to reflect zero balance.

**New: `trading-ui/OptionsPositionRow.java`** — plain POJO with eight getter-compatible fields: symbol (String), type (String), strike (double), expiry (String), contracts (int), entryPremium (double), currentPremium (double), unrealizedPnl (double). Used as the item type for the options positions TableView.

**Modified: `dashboard.fxml`** — layout changes from `BorderPane` to `VBox` with three rows: (1) two `HBox` bars for portfolio labels and performance stats; (2) `HBox` with `LineChart<Number,Number>` (equity curve, left) and `TableView` (options positions, right); (3) `HBox` with `TextArea` (research, left) and `TableView` (trade history, right). Adds chart imports, eight new `fx:id` label bindings, and the options table column declarations.

**Modified: `DashboardController.java`** — promotes `priceHistory` and `bsEngine` to instance fields; adds `@FXML` bindings for `totalPortfolioLabel`, `stockHoldingsLabel`, `optionHoldingsLabel`, `availableCashLabel`, `winRateLabel`, `equityChart`, `optionsTable` and its columns; adds `tickCount` int and `XYChart.Series<Number,Number> equitySeries`; adds private helpers `computeStockHoldings()`, `computeOptionHoldings()`, `computeOptionCurrentPremium(OptionsPosition)`; overhauls `refreshUi()` and `refreshBalance()` to populate all new fields; updates halt label text.

## Risks & Open Questions

| Risk | Likelihood | Mitigation |
|---|---|---|
| priceHistory empty for a symbol (no tick yet) | Low — only at startup | Guard: `prices.isEmpty() ? 0.0 : prices.get(prices.size()-1)` |
| B-S premium returns 0 if vol=0 or T<=0 | Medium — near expiry or fresh start | Guard already in bsEngine.callPrice/putPrice; options row shows $0.00 |
| Chart accumulates indefinitely | Low — capped at 200 points | Remove oldest point when series size exceeds 200 |
| SafetyStop threshold change breaks existing AccountTest | Certain | AccountTest.testSafetyStopHaltsTrading must be updated to use $0 |
