---
design_type: phase
created_at: 2026-05-15
---

## Intent Contract

```
intent: Implement the 1-minute intraday stock trading engine with RSI, MACD,
        Bollinger Bands, MA crossover, and volume surge signals; a 5% trailing
        stop-loss; $0.01/share fee model; and real-time JavaFX dashboard refresh.
constraints:
  - Must not break Phase 1 tests (mvn clean test still passes)
  - All trades check SafetyStop before execution — never trade below $100 balance
  - No trades outside 9:30 AM – 4:00 PM ET (market-hours guard)
  - Position size capped at 5% of current account balance per trade
  - Every trade writes a TransactionRecord with signals and reason
risk_level: low
success_criteria:
  - TradingLoop starts, evaluates ≥1 symbol, and logs at least one signal check per tick
  - At least one buy fires in a test session (confirmed by transaction log entry)
  - Trailing stop-loss triggers a sell when price drops 5% below entry-peak (unit test)
  - Fee of $0.01/share deducted on every stock trade (unit test)
  - Dashboard balance label and trade history table update on the JavaFX thread after each trade
```

## Verification Contract

```
verify_steps:
  - run tests: mvn clean test (all modules — Phase 1 tests must still pass)
  - check: IndicatorEngineTest — RSI, MACD, Bollinger Bands, MA crossover,
    volume surge return correct signals on synthetic price series
  - check: TrailingStopMonitorTest — sell fires at exactly 5% drawdown from peak
  - check: FeeCalculatorTest — $0.01/share deducted; position size capped at 5% of balance
  - check: OrderExecutorTest — balance decrements on buy, increments on sell,
    TransactionRecord written with correct fields
  - confirm: launch app during market hours, observe researchArea updating with
    signal evaluations each minute; trade history table grows after a buy/sell
```

## Governance Contract

```
approval_gates:
  - Phase 2 complete: all new unit tests pass; at least one full buy→sell cycle
    visible in trade history after a market-hours session
rollback: restore trading-engine/src stubs (OrderExecutor.java only prints STUB);
          all Phase 1 modules are unchanged and their tests still pass
ownership: ericmshort@gmail.com
```

## Scope

| In | Out |
|----|-----|
| RSI (14-period), MACD (12/26/9), Bollinger Bands (20/2σ) | Options signals (Phase 3) |
| 50-MA / 200-MA golden/death cross | Fundamental analysis |
| Volume surge (2× 20-day avg on price move direction) | Short selling |
| Trailing stop-loss at 5% drawdown from peak since entry | Margin / leverage |
| $0.01/share fee on every stock buy or sell | Pre/post-market trading |
| Position sizing: max 5% of balance per new trade | Portfolio rebalancing |
| 1-minute tick loop, market-hours guard 9:30–16:00 ET | Multi-symbol parallelism |
| Real-time dashboard refresh (Platform.runLater) | UI chart rendering (Phase 5) |
| Signal threshold: ≥2 of 5 indicators must agree | ML threshold tuning (Phase 4) |
| PriceHistory ring buffer (200 bars max per symbol) | Historical backfill on startup |

## Decisions

| # | Decision | Choice | Rejected alternatives |
|---|----------|--------|-----------------------|
| 1 | Indicator computation | Pure Java, in-process, no external library | TA-Lib (native lib, hard to package), TulipIndicators (C binding) |
| 2 | Tick scheduler | `ScheduledExecutorService` single-thread, 1-min fixed-rate | Timer (deprecated), Quartz (heavyweight) |
| 3 | Position sizing | Flat 5% of current balance per trade, rounded to whole shares | Kelly criterion (requires win-rate estimate not yet available), fixed dollar amount |
| 4 | Price history storage | In-memory `ArrayDeque` ring buffer (200 bars) per symbol | SQLite (latency for every tick), flat file (I/O overhead) |
| 5 | Trailing stop | Peak-price tracked per position; sell when current < peak × 0.95 | Percentage below entry cost (ignores intraday run-ups), fixed-dollar stop |
| 6 | Signal threshold | Configurable int (default 2); checked before every order | Always trade on any single signal (too noisy), require all 5 (misses trades) |
| 7 | Dashboard refresh | `Platform.runLater` called by TradingLoop after each tick | Polling timer in UI (redundant thread), ObservableList direct mutation (threading violation) |

## Surface

**trading-data — PriceHistory:** New class holding an `ArrayDeque<Double>` of closing prices per symbol (max 200). `YahooFinanceClient` populates it on each tick. `IndicatorEngine` reads it as a `List<Double>`. No persistence — history resets on app restart, accumulates over the session.

**trading-engine — IndicatorEngine:** Stateless utility class. Five static methods: `computeRSI(prices, period)`, `computeMACD(prices)`, `computeBollingerBands(prices, period, stdDevMultiplier)`, `computeMACrossover(prices, shortPeriod, longPeriod)`, `computeVolumeSurge(volumes, currentVolume, period)`. Each returns a `SignalResult` (enum: BUY, SELL, NEUTRAL) plus the computed value for display. Needs minimum bar counts: RSI ≥15, MACD ≥27, Bollinger ≥20, MA crossover ≥200, volume ≥20.

**trading-engine — SignalResult:** Value object holding the indicator name, direction (BUY/SELL/NEUTRAL), and the numeric value (e.g. RSI=28.4). `TradingLoop` collects these into a `List<SignalResult>` per symbol per tick.

**trading-engine — TrailingStopMonitor:** Holds a `Map<String, Double>` of peak prices since position entry. On each tick: if current price > peak, update peak. If current price < peak × 0.95, return true (trigger sell). Resets the peak for a symbol when a position is fully closed.

**trading-engine — FeeCalculator:** Single method `double calculateFee(int shares)` returning `shares * 0.01`. Second method `int maxShares(double balance, double price)` returning `(int)((balance * 0.05) / price)` — the largest whole-share position that fits within the 5% position limit.

**trading-engine — OrderExecutor (replaces stub):** `buy(symbol, shares, price, signals, reason)` and `sell(symbol, shares, price, signals, reason)`. Each: checks SafetyStop, computes fee via FeeCalculator, updates Account balance and positions, writes TransactionRecord to TransactionLog, returns the TransactionRecord.

**trading-engine — TradingLoop:** Implements `Runnable`. Wired by DashboardController. Holds references to YahooFinanceClient, PriceHistory map, IndicatorEngine, TrailingStopMonitor, OrderExecutor, Account, TransactionLog, and a `Consumer<String>` for research-area updates. On each tick, appends a summary line to the consumer (symbol, signals fired, action taken or skipped).

**trading-ui — DashboardController (extended):** Starts TradingLoop in a `ScheduledExecutorService` from `initialize()`. Passes a `Platform.runLater` callback to TradingLoop for research-area and balance/history updates. Exposes `refreshFromTrade(TransactionRecord)` called after each completed order.

## Risks & Open Questions

**Risk: Insufficient price history on startup.** The 200-bar MA crossover signal requires 200 1-minute bars (~3.3 hours). On startup, only the current session's bars exist. Mitigation: IndicatorEngine returns NEUTRAL when bar count is below the required minimum; no spurious signals fire during the warmup window.

**Risk: Yahoo Finance rate limiting during multi-symbol polling.** Fetching 50 symbols sequentially with 200ms inter-request delays takes ~10 seconds per tick, leaving 50 seconds of slack within the 1-minute window. If rate limiting occurs, the tick skips affected symbols and logs a data-unavailable note to the research area.

**Risk: JavaFX threading violations.** All Account and UI mutations must happen on the JavaFX Application Thread. Mitigation: TradingLoop executes trade logic on its scheduler thread, then calls `Platform.runLater` for all UI updates. Account state mutations happen on the scheduler thread; only label/table updates go through Platform.runLater.

**Open question:** Should `TradingLoop` continue polling outside market hours to pre-warm price history, or fully pause? Deferred: default is to fully pause (no API calls outside 9:30–16:00 ET) to stay within rate limits.
