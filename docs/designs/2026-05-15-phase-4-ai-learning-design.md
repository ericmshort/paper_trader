---
design_type: phase
created_at: 2026-05-15
---

# Phase 4: AI/ML Learning Layer — Design

## Intent Contract

```
intent: Replace raw indicator vote-counting with Weka-trained per-indicator weights.
constraints: No circular Maven deps; ≥10 paired trades before diverging from default
             weights; training once/day after 4 PM ET; no blocking I/O in loop.
success_criteria:
  - All tests pass; weights sum to 5.0
  - MLSignalEvaluator score replaces raw buy count in TradingLoop
  - Features CSV stored in transactions DB on every BUY; weights persisted to JSON
  - After-market callback fires; weights logged to research pane
  - Dashboard opens without errors
risk_level: low
```

## Verification Contract

```
verify_steps:
  - run tests: mvn test -f /Users/ericshort/AIProjects/TradingApp/pom.xml
  - check: mvn test output shows 0 failures, all new tests in trading-ai and trading-engine pass
  - confirm: transactions DB contains features column (SELECT features FROM transactions LIMIT 1)
  - confirm: ~/.tradingapp/signal-weights.json written after training run
  - confirm: dashboard window opens and displays stock signal lines in research pane
```

## Governance Contract

```
approval_gates:
  - Human smoke test: verify dashboard opens, signal lines visible, no startup errors
rollback:
  - Revert TransactionRecord, TransactionLog, OrderExecutor, TradingLoop, DashboardController
    to Phase 3 state (features column is backward-compatible; old rows read features as null)
ownership: ericmshort@gmail.com
```

## Scope

| In | Out |
|---|---|
| Weka InfoGainAttributeEval per-indicator weighting | Implied volatility or options signal weighting |
| 5 features: RSI value, MACD diff, Bollinger % pos, MA crossover diff, vol ratio | Sell-side feature learning (sell stays raw count this phase) |
| features TEXT column in transactions table | UI display of per-indicator weights |
| After-market training trigger (4 PM ET, once/day) | Hyperparameter tuning or cross-validation |
| SignalWeights JSON persistence at ~/.tradingapp/signal-weights.json | Cloud or remote model persistence |
| Minimum 10 paired trades before diverging from default weights | Continuous / intra-day retraining |
| MLSignalEvaluator replaces raw buy count in TradingLoop | Options signal path changes |

## Decisions

| # | Decision | Choice | Rejected Alternatives |
|---|---|---|---|
| 1 | Circular dependency avoidance | `SignalWeightEvaluator` interface in trading-engine; MLSignalEvaluator in trading-ai implements it | Move all ML to trading-engine (bloats engine); invert dependency (circular) |
| 2 | Feature serialization | CSV string in transactions.features column | Separate features table (over-engineered for 5 fields) |
| 3 | Training trigger in TradingLoop | `Runnable afterMarketCallback` injected by DashboardController | TradingLoop directly imports trading-ai classes (circular) |
| 4 | Sell side | Raw countSellSignals unchanged this phase | Weighted sell scoring (can be added in Phase 5 once buy-side is validated) |
| 5 | Weka algorithm | InfoGainAttributeEval + Ranker (feature importance, not prediction) | Naive Bayes classifier (requires richer label space); J48 decision tree (overkill for 5 features) |
| 6 | Weight normalization | Weights sum to 5.0 (average 1.0) so SIGNAL_THRESHOLD=2 semantics are preserved | Normalize to 1.0 (requires changing threshold); no normalization (unbounded) |

## Surface

**New interface — trading-engine:**
`SignalWeightEvaluator` — two methods: `weightedBuyScore(List<SignalResult>)` and `weightedSellScore(List<SignalResult>)`. Injected into TradingLoop alongside the existing `OptionsEvaluator`. Defaults to null; TradingLoop falls back to raw count when null.

**New classes — trading-ai:**
- `FeatureVector` — five named doubles: rsi, macd, bollinger, maCrossover, volRatio.
- `LabeledTrade` — FeatureVector + boolean win (sell price > buy price).
- `TradeFeatureExtractor` — reads BUY records with non-null features from TransactionLog, pairs each with its chronologically next SELL for the same symbol, computes win/loss outcome.
- `SignalWeights` — double[5] weights initialized to 1.0; `toJson()` / `fromJson()`; `save()` / `load()` targeting `~/.tradingapp/signal-weights.json`.
- `SignalWeightTrainer` — accepts `List<LabeledTrade>`; uses `InfoGainAttributeEval + Ranker` on a Weka `Instances` dataset; maps rank-derived scores to normalized weights summing to 5.0; returns `SignalWeights`.
- `MLSignalEvaluator` — implements `SignalWeightEvaluator`; holds a `SignalWeights` instance; `weightedBuyScore` sums the weight for each BUY-direction indicator by name; `weightedSellScore` sums weights for SELL-direction indicators; exposes `retrain(TransactionLog)` which calls TradeFeatureExtractor → SignalWeightTrainer and persists updated weights.

**Modified classes:**
- `TransactionRecord` — add `private String features` field with getter/setter.
- `TransactionLog` — `initDb()` runs `ALTER TABLE transactions ADD COLUMN features TEXT` wrapped in a no-op catch for "duplicate column" SQLiteException; `insert()` and `findAll()` include features.
- `OrderExecutor.buy()` — keep existing 5-param signature (passes null features); add 6-param overload `buy(symbol, shares, price, signals, reason, features)`.
- `TradingLoop` — add `SignalWeightEvaluator weightEval` and `Runnable afterMarketCallback` constructor params (both nullable); add `LocalDate lastTrainingDate`; helper `extractFeatureCsv(List<SignalResult>)` extracts numeric values in fixed order [RSI, MACD, BollingerBands, MACrossover, VolumeSurge] by indicator name; replace `buys >= SIGNAL_THRESHOLD` with `weightedBuyScore(signals) >= SIGNAL_THRESHOLD`; after-market close branch triggers callback once per calendar day.
- `DashboardController` — instantiate `MLSignalEvaluator`; pass `() -> { mlEval.retrain(transactionLog); researchCb.accept("Weights: " + mlEval.getWeights()); }` as `afterMarketCallback` to TradingLoop.
- `trading-ai/pom.xml` — add `trading-engine` dependency.

## Risks & Open Questions

| Risk | Likelihood | Mitigation |
|---|---|---|
| Fewer than 10 paired trades on fresh install | Certain initially | Default weights 1.0 → raw count behavior unchanged |
| Weka InfoGain favors noise features on tiny datasets | Medium | 10-trade minimum; weights normalized so no single indicator dominates |
| ALTER TABLE migration fails on pre-existing DB | Low | Catch SQLiteException with message containing "duplicate column name" and continue |
| Weighted buy score == raw buy count on default weights | Expected | By design: sum(5 × 1.0) = 5 ≥ threshold=2 requires ≥2 BUY signals, same as before |
