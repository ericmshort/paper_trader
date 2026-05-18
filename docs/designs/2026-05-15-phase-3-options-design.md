---
design_type: phase
created_at: 2026-05-15
---

# Phase 3: Options Trading — Design

## Intent Contract

```
intent: Add single-leg call/put options to the paper trader using Black-Scholes
        local pricing, signal-driven entry/exit, and Greeks display
constraints:
  - No multi-leg spreads, straddles, or spreads of any kind
  - No live options chain data from Yahoo Finance
  - Historical volatility only (no implied volatility)
  - Options positions tracked separately from stock positions in Account
  - Existing stock trading behaviour must be unchanged
  - All 34 existing tests must continue to pass
success_criteria:
  - A call executes when ≥2 bullish stock signals fire for a watched symbol
  - A put executes when ≥2 bearish stock signals fire for a watched symbol
  - Black-Scholes price and all four Greeks (delta/gamma/theta/vega) computed each tick
  - Position closes on signal reversal (≥2 opposing signals) or <3 days to expiry
  - $0.65/contract fee deducted on both open and close
  - BlackScholesEngine test: S=100, K=100, r=0.04, T=0.5, σ=0.2 → call price within 0.01 of 9.93
  - All new options tests pass; all 34 prior tests pass
risk_level: low
```

## Verification Contract

```
verify_steps:
  - run tests: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test
  - check: BlackScholesEngine reference test passes (known-value assertion within 0.01)
  - check: All four Greeks are finite and in expected ranges (delta ∈ [0,1] for calls,
           gamma ≥ 0, theta ≤ 0, vega ≥ 0)
  - check: OptionsOrderExecutor test confirms balance decreases by premium + fee on buy,
           increases by intrinsic/BS value minus fee on sell
  - check: OptionsSignalRouter test confirms call opened on ≥2 BUY signals and closed
           on ≥2 SELL signals or <3 days to expiry
  - confirm: 34 prior tests still pass; new trading-options module tests all pass
```

## Governance Contract

```
approval_gates:
  - Human review after BlackScholesEngine implementation (math correctness gate)
rollback: Remove trading-options entry from parent pom.xml modules list.
          No database schema changes; TransactionLog action field is VARCHAR — existing rows unaffected.
ownership: ericmshort@gmail.com
```

## Scope

| In scope | Out of scope |
|---|---|
| Single-leg call and put orders | Multi-leg spreads, straddles, iron condors |
| Black-Scholes pricing (European, local computation) | Live options chain data from Yahoo |
| Greeks: delta, gamma, theta, vega | Implied volatility (use historical vol) |
| Historical volatility from 20-bar price history | Options-specific UI tab or chart |
| ATM strike rounded to nearest $5 | Fractional contracts |
| Monthly expiry (third Friday, ≥14 days from today) | Short calls or puts |
| Position sizing: 5% of balance in premium, max 5 contracts | Dynamic risk-free rate (use constant 4%) |
| Exit on ≥2 opposing signals or <3 days to expiry | Greeks-based exit conditions |
| $0.65/contract fee on open and close | Rolling or adjusting existing positions |
| Log with action = CALL_BUY / CALL_SELL / PUT_BUY / PUT_SELL | Separate options trade history panel |
| Greeks printed to research text area | Real-time Greeks chart |

## Decisions

| # | Decision | Choice | Rejected alternatives |
|---|---|---|---|
| 1 | Module structure | New `trading-options` Maven module | Extend trading-engine (mixes concerns); TradingStrategy interface (premature abstraction) |
| 2 | Volatility source | 20-bar historical vol (annualized log-return std dev × √252) | 30 bars (insufficient early data), 10 bars (too noisy), implied vol (no source) |
| 3 | Risk-free rate | Constant 4.0% (approximate 2026 rate) | Live Treasury rate API (no free source, adds complexity) |
| 4 | Strike selection | Round currentPrice to nearest $5 | Exact ATM (strikes don't trade at arbitrary prices), 5% OTM (lower delta, harder to test) |
| 5 | Expiry selection | Next third Friday of a month that is ≥14 days from today | Nearest weekly (high theta decay), hardcoded offset (fragile around month boundaries) |
| 6 | Options position key | `symbol_CALL` and `symbol_PUT` (e.g., `AAPL_CALL`) | Full OCC symbol (hard to read in logs), symbol only (can't hold both) |
| 7 | Per-contract cost | Black-Scholes price × 100 (standard 100-share multiplier) | Price × 1 (omits multiplier — wrong) |
| 8 | Zero-vol guard | If σ < 0.001, skip options trade and log reason | Return NaN (crash), return 0 price (misleading) |
| 9 | Simultaneous stock + options | Allowed — both can exist on same symbol simultaneously | Block options when stock position held (restricts educational use) |
| 10 | Transaction storage | Reuse existing TransactionLog/TransactionRecord with new action strings | New options_transactions table (migration overhead, no benefit) |

## Surface

### New Maven module: `trading-options`

POM depends on `trading-account` and `trading-data`. `trading-ui` gains a dependency on `trading-options`. `trading-engine` is not a dependency of `trading-options` (signal evaluation result type `SignalResult` is passed in by `TradingLoop`).

**`BlackScholesEngine.java`** — stateless, all methods pure functions:
- `double callPrice(double S, double K, double r, double T, double sigma)`
- `double putPrice(double S, double K, double r, double T, double sigma)`
- `GreeksResult greeks(double S, double K, double r, double T, double sigma, boolean isCall)`
- `double historicalVol(List<Double> prices)` — annualized std dev of log returns, guard for σ<0.001
- `double timeToExpiry(LocalDate expiry)` — (expiry − today) / 365.0
- `LocalDate nextMonthlyExpiry()` — finds third Friday of next eligible month (≥14 days from today)
- `double roundStrike(double price)` — rounds to nearest $5

**`GreeksResult.java`** — value object:
- `double delta, gamma, theta, vega`
- `toString()` for research area display

**`OptionsOrderExecutor.java`**:
- Constructor: `(Account account, TransactionLog log, FeeCalculator fees)`
- `buyCall(symbol, strike, expiry, contracts, premium, signalStr)` → deduct premium×100×contracts + fee, add OptionsPosition
- `buyPut(symbol, strike, expiry, contracts, premium, signalStr)` → same
- `closePosition(String positionKey, double currentPremium, String reason)` → add proceeds, deduct fee, remove position, log PnL

**`OptionsEvaluator.java`** (new interface in `trading-engine`):
- `void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr)`
- Keeps `trading-engine` free of a dependency on `trading-options`

**`OptionsSignalRouter.java`** (implements `OptionsEvaluator`):
- Constructor: `(BlackScholesEngine bsEngine, OptionsOrderExecutor optExec, Account account, PriceHistory priceHistory, Consumer<String> researchCallback)`
- `void evaluate(...)` — called by TradingLoop via OptionsEvaluator after stock evaluation
  - Check existing options positions for symbol: close call if sells≥2 or expiry<3d, close put if buys≥2 or expiry<3d
  - Open call if buys≥2 and no CALL position for symbol
  - Open put if sells≥2 and no PUT position for symbol
  - Log Greeks to research via callback
- Constructor also takes `Consumer<String> researchCallback`

### Modified: `trading-account`

**`OptionsPosition.java`** — new class:
- `String symbol, String type (CALL|PUT), double strike, LocalDate expiry, int contracts, double premiumPaid`
- `double getCurrentValue(double currentPremium)` — currentPremium × 100 × contracts
- `long daysToExpiry()` — ChronoUnit.DAYS.between(today, expiry)

**`Account.java`** — add:
- `private final Map<String, OptionsPosition> optionsPositions = new ConcurrentHashMap<>()`
- `public Map<String, OptionsPosition> getOptionsPositions()` — unmodifiable view
- `public synchronized void addOptionsPosition(String key, OptionsPosition pos)`
- `public void removeOptionsPosition(String key)`

### Modified: `trading-engine`

**`OptionsEvaluator.java`** — new functional interface in `trading-engine` to avoid a circular dependency:
- `void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr)`

**`TradingLoop.java`** — add optional `OptionsEvaluator optionsEvaluator` field:
- Add constructor overloads accepting `OptionsEvaluator` (nullable for backward compat with existing tests)
- After the existing buy/sell/trailing-stop block, call `if (optionsEvaluator != null) optionsEvaluator.evaluate(symbol, price, buys, sells, signalStr)`

### Modified: `trading-ui`

**`DashboardController.java`** — instantiate `BlackScholesEngine`, `OptionsOrderExecutor`, `OptionsSignalRouter` and pass to `TradingLoop`.

## Risks & Open Questions

| Risk | Likelihood | Mitigation |
|---|---|---|
| Black-Scholes math error (wrong d1/d2 sign or N() approximation) | Medium | Reference test: S=100, K=100, r=0.04, T=0.5, σ=0.2 → C≈9.93 asserts within 0.01 |
| σ=0 when prices flat (division by zero in BS) | Low | Guard: historicalVol() returns 0; OptionsSignalRouter skips trade, logs "vol=0, skip" |
| Third-Friday algorithm wrong near month boundary | Low | Unit test: verify nextMonthlyExpiry() for dates near end of month and near existing third Fridays |
| T→0 as expiry approaches (theta blow-up day of expiry) | Low | Exit triggered at <3 days, so T is always >2/365 when BS is called on open positions |
| Simultaneous call and put on same symbol | Intentional | Router key `AAPL_CALL` and `AAPL_PUT` are independent; both can exist |
