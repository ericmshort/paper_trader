---
design_type: initiative
created_at: 2026-05-15
---

# AI-Powered Paper Trading Simulator — Strategic Design

**Status:** Draft
**Date:** 2026-05-15
**Owner:** ericmshort@gmail.com
**Co-authors:** @architect (Claude Sonnet 4.6)
**Related:** N/A — greenfield initiative

---

## 1. Problem Statement

There is no low-friction way for an individual to learn algorithmic day trading with realistic market mechanics — fees, signals, options pricing, and position sizing — without risking real money. Existing paper trading platforms hide the trading logic and don't expose how decisions are made or how an AI could learn from its own mistakes.

**Who is affected:** Individual learners who want to understand day trading, technical analysis, and AI-driven algorithmic strategies in a hands-on way. They cannot currently observe, inspect, or learn from a live trading algorithm without either paying for a professional platform or building something from scratch.

**Evidence:** The app does not yet exist. This is a greenfield educational tool. Success is measured by whether a user can sit down, watch the AI trade, read every reason behind each decision, and understand what went wrong or right — without ever touching real money.

---

## 2. Vision / Intent

When this ships, a learner opens the TradingApp desktop window, watches an AI agent research and execute simulated trades against live Yahoo Finance quotes, reads every signal and reason that drove each decision in a scrollable history pane, and sees the simulated account balance grow or shrink in real time — all without touching real money. The ML model retrains after each session, adjusting its signal thresholds based on which patterns produced wins versus losses.

---

## 3. Non-Goals

- No real brokerage connectivity or real-money execution of any kind
- No forex, cryptocurrency, futures, or non-US exchanges
- No multi-user accounts, authentication, or cloud sync
- No multi-leg options spreads (vertical spreads, straddles, iron condors) — Phase 1 covers single-leg calls and puts only
- No tax lot accounting or wash-sale rule simulation
- No portfolio rebalancing across asset classes
- No short selling of stocks (options puts are the bearish vehicle)

---

## 4. Stakeholders

| Role | Interest | Interface |
|---|---|---|
| Primary user (learner) | Observe AI day trading decisions, learn technical analysis | JavaFX dashboard |
| Developer (owner) | Build, extend, and tune the trading algorithm | Source code, ML model files |
| No operators | Standalone desktop app, no production system | N/A |
| No blocked parties | Greenfield — no downstream dependencies | N/A |

---

## 5. Architecture / Module-Level Changes

Five Maven modules, sequential data flow:

```
TradingApp/
├── pom.xml                 # parent POM — dependency versions, plugin config
├── trading-data/           # Yahoo Finance client, quote + options chain models
│   └── YahooFinanceClient, QuoteModel, OptionsChain
├── trading-engine/         # Signal generation, order execution, fee model
│   └── TechnicalIndicators, SignalEngine, OrderExecutor, FeeCalculator
├── trading-ai/             # Weka ML, feature store, model persistence
│   └── TradeFeatureExtractor, WekaModelTrainer, SignalThresholdTuner
├── trading-account/        # Account state, positions, transaction log
│   └── Account, Position, TransactionLog, SafetyStop
└── trading-ui/             # JavaFX dashboard (depends on all above modules)
    └── DashboardController, TradeHistoryPane, BalancePane, ResearchPane
```

**Data flow (each 1-minute tick):**
```
trading-data  →  trading-engine  →  trading-account
                     ↑                    ↑
                trading-ai          trading-ui reads
```

**Key invariant:** The UI layer never writes account or position state directly. All mutations flow through `trading-engine`, which enforces the $100 safety stop and fee deduction before updating `trading-account`.

---

## 6. Maturity Stages

| Stage | Description | Exit criteria |
|---|---|---|
| **L1 (Phase 1)** | Data client live, account model, safety stop | Quotes load for ≥5 large-cap symbols; account starts at $100K |
| **L2 (Phase 2)** | Stock trading engine with 3+ indicators | RSI, MACD, Bollinger Bands fire signals; fees deducted on execution |
| **L3 (Phase 3)** | Options trading (single-leg) | Call/put orders execute with Black-Scholes pricing; Greeks display |
| **L4 (Phase 4)** | AI/ML learning layer | Weka trains on session history; thresholds adjust after each session |
| **L5 (Phase 5)** | Dashboard polish | All panels live, charts, win/loss counter, full history with reasons |

**Current state:** Pre-L1 (no code yet). **Target:** L5 across 5 phases.

---

## 7. Phase Breakdown

| Phase | Intent | Design file |
|---|---|---|
| Phase 1 | Maven scaffold, Yahoo Finance data client, account model, safety stop, JavaFX shell | `docs/designs/YYYY-MM-DD-phase-1-foundation-design.md` |
| Phase 2 | Stock trading engine — RSI, MACD, Bollinger Bands, MA signals, buy/sell execution, fees | `docs/designs/YYYY-MM-DD-phase-2-stock-engine-design.md` |
| Phase 3 | Options trading — Black-Scholes pricing, Greeks, single-leg call/put execution | `docs/designs/YYYY-MM-DD-phase-3-options-design.md` |
| Phase 4 | AI/ML learning — Weka random forest, feature engineering, threshold self-adjustment | `docs/designs/YYYY-MM-DD-phase-4-ai-learning-design.md` |
| Phase 5 | Dashboard polish — real-time charts, win/loss counter, full history pane, performance metrics | `docs/designs/YYYY-MM-DD-phase-5-dashboard-design.md` |

Phases execute sequentially. Each phase produces an executable workflow in `docs/plans/` via `writing-plans`.

---

## 8. Quality Attributes

| Attribute | Goal |
|---|---|
| Performance | UI renders without lag on a single MacBook; 1-minute tick evaluation completes in <5 seconds |
| Data reliability | Yahoo Finance client retries on failure with exponential backoff; stale quotes (>5 min) flagged in UI |
| Safety | Account balance checked before every order; trading halts permanently if balance < $100 |
| Observability | Every trade decision writes a structured log entry: timestamp, symbol, signal name, signal value, reason, order placed |
| Persistence | Transaction log and ML model state survive app restarts (JSON or SQLite on disk) |
| Cost | Zero — no paid APIs, no cloud services; Weka and Yahoo Finance are free |
| Backwards compatibility | N/A — greenfield |

---

## 9. Technical Decisions

| # | Decision | Choice | Rejected alternatives |
|---|---|---|---|
| 1 | UI framework | JavaFX | Swing (dated), Spring Boot+Web (overkill overhead) |
| 2 | Stock data API | Yahoo Finance unofficial (yahoo-finance-api Java library) | Alpha Vantage (25/day limit), Polygon.io (EOD only on free tier) |
| 3 | Build system | Maven multi-module | Gradle (less familiar for Java desktop), single module (poor separation) |
| 4 | ML library | Weka | DL4J (heavyweight for the volume of trades here), OpenAI API (costs money) |
| 5 | Options pricing | Black-Scholes formula (computed locally) | Pulling live options quotes from Yahoo (unreliable on unofficial API) |
| 6 | Trade persistence | SQLite via JDBC | Plain JSON files (no query capability), H2 in-memory (doesn't survive restart) |
| 7 | Large-cap universe | S&P 500 top 50 by market cap (hardcoded list, refreshed periodically) | Full S&P 500 (too many symbols for 1-min polling), dynamic screener (API complexity) |

---

## 10. Trading Signals (Phase 2+)

The engine evaluates the following indicators each 1-minute tick per watched symbol:

| Indicator | Buy signal | Sell signal |
|---|---|---|
| RSI (14-period) | RSI < 30 (oversold) | RSI > 70 (overbought) |
| MACD | MACD line crosses above signal line | MACD line crosses below signal line |
| Bollinger Bands | Price touches lower band | Price touches upper band |
| 50/200 MA crossover | 50-MA crosses above 200-MA (golden cross) | 50-MA crosses below 200-MA (death cross) |
| Volume surge | Volume > 2× 20-day average on up-move | Volume > 2× 20-day average on down-move |

A trade fires only when ≥2 indicators align (configurable threshold, tuned by ML in Phase 4).

---

## 11. Fee Model

| Event | Fee |
|---|---|
| Stock buy or sell | $0.01/share (simulates modern broker spread cost) |
| Options contract buy or sell | $0.65/contract (industry standard, e.g. Schwab/TD Ameritrade) |
| Account minimum check | If balance < $100 after any trade, all trading halts permanently |

---

## 12. HOTL Contracts

### Intent Contract
```
intent: Build a JavaFX paper trading simulator that uses live Yahoo Finance
        quotes, technical indicators, and a Weka ML model to autonomously
        research and trade US large-cap stocks and single-leg options,
        recording every decision with its signals and reasons.
constraints:
  - No real money, no real brokerage connectivity
  - US stocks and options only
  - Account never trades below $100 balance
  - Yahoo Finance unofficial API — must handle outages gracefully
success_criteria:
  - App starts, connects to Yahoo Finance, and displays quotes
  - AI agent places at least one simulated buy and sell with logged reason
  - Transaction history persists across restarts
  - Win/loss counter updates in real time
  - ML model retrains after each completed trade session
risk_level: low
```

### Verification Contract
```
verify_steps:
  - run tests: mvn test (all modules)
  - check: launch TradingApp, confirm $100,000 starting balance displayed
  - check: let run 2 minutes, confirm at least one research action logged
  - check: force account balance to $99 via test fixture, confirm trading halts
  - check: simulate 10 trades, confirm all appear in scrollable history with reasons
  - confirm: ML model file written to disk after session
```

### Governance Contract
```
approval_gates:
  - Phase 1 complete: data client returns valid quotes for ≥5 large-cap symbols
  - Phase 2 complete: at least 3 technical indicators fire buy/sell signals correctly
  - Phase 3 complete: options chain data loads and a call/put order executes
  - Phase 4 complete: Weka model trains on prior session data and adjusts one threshold
  - Phase 5 complete: dashboard shows all required panels without UI jank
rollback: delete /Users/ericshort/AIProjects/TradingApp directory (no prod system touched)
ownership: ericmshort@gmail.com
```

---

## 13. Risks & Open Questions

**Risk: Yahoo Finance API instability.** The unofficial library scrapes Yahoo Finance endpoints that can change without notice. Mitigation: wrap all API calls in a `DataSourceException` handler; display a "data unavailable" banner in the UI rather than crashing; add a fallback to cached last-known quotes for up to 5 minutes.

**Risk: Weka model quality with limited trade history.** Early sessions will have few completed trades, making the ML model unreliable. Mitigation: the model only adjusts thresholds after ≥50 completed trades; before that, it runs with hard-coded defaults and accumulates training data silently.

**Risk: Black-Scholes accuracy for educational options pricing.** Black-Scholes assumes constant volatility, which is unrealistic. Mitigation: clearly label all options prices as "theoretical (B-S)" in the UI; this is acceptable for educational purposes.

**Open question:** Should the Phase 2 stock watch-list be configurable from the UI, or hardcoded in a config file? Deferred to Phase 2 design; default is a hardcoded top-50 list with a config file override.

**Open question:** Should Weka use a random forest or a gradient-boosted classifier? Deferred to Phase 4 design; random forest is the default starting point due to interpretability.

---

## 14. Verification (Design Acceptance)

This design is accepted when:

- Problem statement and vision are coherent (confirmed by owner)
- Non-goals are specific and enforceable
- Phase breakdown is sequential with clear exit criteria at each phase boundary
- All three HOTL contracts are present and internally consistent
- Owner has approved this document
