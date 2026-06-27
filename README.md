# PaperTrader — AI-Powered Paper Trading Simulator

A production-grade desktop paper trading application built with Java 21 and JavaFX. It combines technical analysis, real market data, and AI-powered sentiment analysis to simulate intraday stock and options trading — without risking real money.

---

## Features

- **Live market data** via Alpaca WebSocket (real-time quotes) and Yahoo Finance (historical bars)
- **Multi-signal trading engine** — RSI, MACD, Bollinger Bands, VWAP, MA Crossover, Volume Surge; orders require ≥2 agreeing signals
- **Options trading** — Black-Scholes pricing with full Greeks (Δ, Γ, Θ, ν); single-leg calls and puts
- **AI-powered sentiment** — Claude (Haiku) classifies daily news headlines as BULLISH/BEARISH/NEUTRAL with 0.0–2.0 confidence weights
- **Risk controls** — trailing stops (2–5%), daily loss limit, portfolio exposure cap, earnings blackouts, IV surge guard, hard $100 safety floor
- **Real-time JavaFX dashboard** — equity curve, position tables, live trade history, signal research pane, settings panel
- **Backtesting engine** — multi-year historical replay with daily P&L, win rate, Sharpe ratio, and drawdown reports
- **Persistent account state** — SQLite database; trades survive app restarts

---

## Architecture

The project is a Maven multi-module application:

```
paper_trader_main/
├── trading-data/        Market data & quote providers (Alpaca, Yahoo Finance)
├── trading-account/     SQLite-backed account, positions, and P&L
├── trading-engine/      Technical indicators, signal evaluation, 1-min trading loop
├── trading-options/     Black-Scholes pricing, Greeks, options execution
├── trading-ai/          ML signal weight optimization (Weka, training stubs)
├── trading-sentiment/   Claude API news sentiment analysis
├── trading-broker/      Alpaca broker integration, backtest runners, AppConfig
└── trading-ui/          JavaFX desktop dashboard (FXML, controllers, charts)
```

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 21 |
| UI | JavaFX 21.0.2 |
| Build | Maven 3.x (multi-module) |
| Persistence | SQLite JDBC 3.45.1 |
| ML | Weka 3.8.6 |
| Live broker | Alpaca Paper Trading API |
| Quote data | Alpaca WebSocket, Yahoo Finance |
| AI sentiment | Anthropic Claude API (Haiku) |
| Testing | JUnit 5 |

---

## Prerequisites

- **Java 21** (e.g., via [SDKMAN](https://sdkman.io/))
- **Maven** (via SDKMAN or system install)
- **Alpaca account** — free paper trading, no credit card required → [alpaca.markets](https://alpaca.markets)
- **Anthropic API key** — optional, enables news sentiment; [console.anthropic.com](https://console.anthropic.com)

---

## Configuration

Copy the example config and fill in your API credentials:

```bash
mkdir -p ~/.tradingapp/day-trader
cp app.properties.example ~/.tradingapp/day-trader/app.properties
```

Key settings in `app.properties`:

```properties
# Alpaca paper trading
broker.type=ALPACA_PAPER
broker.alpaca.api_key=YOUR_KEY
broker.alpaca.api_secret=YOUR_SECRET

# Optional: AI news sentiment
ai.claude.api_key=YOUR_KEY

# Risk controls
risk.daily_loss_limit_pct=5.0
risk.max_portfolio_exposure_pct=70.0
risk.avoid_overnight_holds=true

# Strategies to enable
strategy.enabled=HIGH_DELTA_SCALP,MOMENTUM_NEAR_TERM,LONG_CALL,LONG_PUT,OPENING_BREAKOUT,MACD_CROSSOVER

# Symbol watchlist (free Alpaca tier: max 30)
options.symbol.allowlist=SPY,NVDA,MSFT,AMZN,COST,ORCL,META,GS,MA,AVGO,...

# Disable stock trading (options-only mode)
stock.trading.enabled=false
```

---

## Build & Run

### Build all modules

```bash
mvn clean install -DskipTests -q
```

### Launch the desktop app

```bash
./run.sh
```

Or manually:

```bash
mvn clean install -DskipTests -q && mvn -pl trading-ui javafx:run
```

### Run backtests

```bash
# Main intraday backtest (2-year historical replay)
mvn -pl trading-broker exec:java -Dexec.mainClass="com.tradingapp.broker.IntradayBacktestRunner"

# Compare strategies side-by-side
mvn -pl trading-broker exec:java -Dexec.mainClass="com.tradingapp.broker.CombinedBacktestRunner"

# Premium-selling strategy comparison
mvn -pl trading-broker exec:java -Dexec.mainClass="com.tradingapp.broker.PremiumSellerComparisonRunner"

# Symbol ranking / screening
mvn -pl trading-broker exec:java -Dexec.mainClass="com.tradingapp.broker.SymbolRankingRunner"
```

---

## Trading Signals

| Indicator | Buy Signal | Sell Signal |
|-----------|-----------|------------|
| RSI (14) | RSI < 30 | RSI > 70 |
| MACD (12/26/9) | MACD crosses above signal | MACD crosses below signal |
| Bollinger Bands (20, 2σ) | Price < lower band | Price > upper band |
| VWAP | Price > VWAP | Price < VWAP |
| MA Crossover (50/200) | Golden cross | Death cross |
| Volume Surge | Vol > 1.5× avg + up move | Vol > 1.5× avg + down move |
| News Sentiment (Claude) | BULLISH weight > 1.0 | BEARISH weight > 1.0 |

Orders execute only when **≥2 signals agree** to reduce false positives.

---

## Risk Management

- **Position sizing:** 5% of account balance per trade (max ~20 concurrent positions)
- **Trailing stop-loss:** 2–5% drawdown (configurable)
- **Portfolio exposure cap:** 70% of account value
- **Daily loss limit:** 5% of starting balance; trading halts for the day if exceeded
- **Safety floor:** Trading halts permanently if balance drops below $100
- **Market hours guard:** Orders only placed 9:30 AM – 4:00 PM ET
- **Earnings blackout:** 3-day skip window around earnings announcements
- **IV surge guard:** No options entry if 20-bar historical volatility exceeds 1.5× baseline
- **Overnight hold prevention:** All positions closed before market close (configurable)

---

## Fee Model

| Asset | Fee |
|-------|-----|
| Stock trade | $0.01 per share |
| Options contract | $0.65 per contract (open and close) |

---

## Running Tests

```bash
mvn clean test
```

Key test suites:
- `IndicatorEngineTest` — RSI, MACD, Bollinger, VWAP, volume surge calculations
- `BlackScholesEngineTest` — Option pricing assertions against known values
- `OrderExecutorTest` — Position sizing, fee deduction, balance updates
- `TrailingStopMonitorTest` — Stop-loss trigger verification
- `FeeCalculatorTest` — Commission calculations

Integration tests (require live Alpaca credentials) are excluded from the default test run.

---

## Data & Persistence

- **Database:** `~/.tradingapp/day-trader/trading.db` (SQLite)
- **Tables:** accounts, positions, options\_positions, transactions, transaction\_log
- **Backtest reports:** Written to the project root as timestamped `.txt` files
- **Config:** `~/.tradingapp/day-trader/app.properties`

---

## Project Status

| Phase | Feature | Status |
|-------|---------|--------|
| 1 | Foundation (account, data client, safety stop) | Complete |
| 2 | Stock trading engine (6 indicators, trailing stops) | Complete |
| 3 | Options trading (Black-Scholes, Greeks, single-leg) | Complete |
| 4 | ML signal optimization (Weka) | Training stubs only |
| 5 | JavaFX dashboard | Complete |
| 6 | AI news sentiment (Claude API) | Complete |
| — | Premium seller strategies (PCS, CCS) | Complete |
| — | Alpaca live broker integration | Complete |
| — | Backtesting engine | Complete |

---

## Disclaimer

This application is for **educational and research purposes only**. It uses paper trading accounts with simulated money. Past backtest performance does not guarantee future results. This is not financial advice.
