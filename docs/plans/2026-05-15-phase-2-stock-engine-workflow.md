---
intent: Implement the 1-minute intraday stock trading engine with RSI, MACD, Bollinger Bands, MA crossover, and volume surge signals; a 5% trailing stop-loss; $0.01/share fee model; and real-time JavaFX dashboard refresh.
success_criteria:
  - mvn clean test passes (all Phase 1 tests still pass, all new tests pass)
  - IndicatorEngine computes correct RSI, MACD, Bollinger Bands, MA crossover, volume surge on synthetic series
  - TrailingStopMonitor triggers sell at exactly 5% drawdown from peak (unit test)
  - FeeCalculator returns $0.01/share; maxShares caps at 5% of balance (unit test)
  - OrderExecutor decrements balance on buy, increments on sell, writes TransactionRecord (unit test)
  - TradingLoop starts without exception and respects market-hours guard (unit test with mocked time)
risk_level: low
auto_approve: true
worktree: false
---

## Steps

- [ ] **Step 1: Create PriceHistory in trading-data**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-data/src/main/java/com/tradingapp/data/PriceHistory.java. The class holds two private fields: Map<String,ArrayDeque<Double>> prices and Map<String,ArrayDeque<Double>> volumes, both initialized as new HashMap<>(). Define a private static final int MAX_BARS = 200. Implement: (1) void record(String symbol, double price, double volume) — adds price to prices.computeIfAbsent(symbol, k->new ArrayDeque<>()) and volume to volumes.computeIfAbsent, then if the deque size exceeds MAX_BARS, calls pollFirst() to drop the oldest; (2) List<Double> getPrices(String symbol) — returns new ArrayList<>(prices.getOrDefault(symbol, new ArrayDeque<>())); (3) List<Double> getVolumes(String symbol) — same pattern for volumes; (4) int size(String symbol) — returns prices.getOrDefault(symbol, new ArrayDeque<>()).size(); (5) void clear(String symbol) — removes the symbol from both maps.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-data/src/main/java/com/tradingapp/data/PriceHistory.java
  assert:
    kind: exists

- [ ] **Step 2: Create SignalResult value object in trading-engine**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/SignalResult.java. Add a public enum Direction { BUY, SELL, NEUTRAL } as a nested type. Fields: private final String indicatorName, private final Direction direction, private final double value. Constructor: SignalResult(String indicatorName, Direction direction, double value). Static factory methods: static SignalResult buy(String name, double value), static SignalResult sell(String name, double value), static SignalResult neutral(String name, double value) — each calls the constructor with the matching Direction. Getters: getIndicatorName(), getDirection(), getValue(). Override toString() to return indicatorName + "=" + String.format("%.4f",value) + " [" + direction + "]".
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-engine/src/main/java/com/tradingapp/engine/SignalResult.java
  assert:
    kind: exists

- [ ] **Step 3: Write failing IndicatorEngine tests for RSI and insufficient-data cases**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/test/java/com/tradingapp/engine/IndicatorEngineTest.java. Declare a field `private final IndicatorEngine engine = new IndicatorEngine()` in the test class. Add JUnit 5 tests: (1) testRSI_BuySignal — build List<Double> of 15 prices with steady decline [100,99,98,97,96,95,94,93,92,91,90,89,88,87,86], call engine.computeRSI(prices), assert direction==BUY and value < 30; (2) testRSI_SellSignal — 15 prices with steady rise [86,87,88,89,90,91,92,93,94,95,96,97,98,99,100], assert direction==SELL and value > 70; (3) testRSI_InsufficientData — 13 prices (below the 14+1 minimum), assert direction==NEUTRAL; (4) testBollingerBands_BuySignal — 20 prices all equal to 100.0, currentPrice=94.0, call engine.computeBollingerBands(prices, 94.0), assert direction==BUY; (5) testBollingerBands_SellSignal — 20 prices all 100.0, currentPrice=106.0, assert direction==SELL; (6) testBollingerBands_InsufficientData — 19 prices, assert direction==NEUTRAL. Create a minimal stub IndicatorEngine class at /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/IndicatorEngine.java as a non-final class with instance methods computeRSI(List<Double>) and computeBollingerBands(List<Double>, double) that each return SignalResult.neutral("stub", 0) so the project compiles. Run mvn test -pl trading-engine --also-make to confirm tests compile but the RSI/BB tests fail (expected — RED phase).
loop: false
max_iterations: 1
verify:
  - type: artifact
    path: trading-engine/src/test/java/com/tradingapp/engine/IndicatorEngineTest.java
    assert:
      kind: exists
  - type: artifact
    path: trading-engine/src/main/java/com/tradingapp/engine/IndicatorEngine.java
    assert:
      kind: exists

- [ ] **Step 4: Implement RSI and Bollinger Bands in IndicatorEngine**
action: Replace the stub IndicatorEngine.java at /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/IndicatorEngine.java with real instance methods. Implement computeRSI(List<Double> prices): requires prices.size() >= 15 else return SignalResult.neutral("RSI", 0). Use 14-period RSI: iterate from index 1 to 14 summing gains (max(0, prices[i]-prices[i-1])) and losses (max(0, prices[i-1]-prices[i])). avgGain = sumGains/14, avgLoss = sumLosses/14. If avgLoss==0 return SignalResult.sell("RSI", 100). double rs = avgGain/avgLoss; double rsi = 100 - (100/(1+rs)). Return sell if rsi > 70, buy if rsi < 30, else neutral, always passing the computed rsi as the value. Implement computeBollingerBands(List<Double> prices, double currentPrice): requires prices.size() >= 20 else neutral. Take last 20 prices. Compute SMA = sum/20. Compute variance = sum of (p-SMA)^2 / 20, stdDev = Math.sqrt(variance). upperBand = SMA + 2*stdDev, lowerBand = SMA - 2*stdDev. Return buy if currentPrice <= lowerBand, sell if currentPrice >= upperBand, else neutral, passing currentPrice as value. Run mvn test -pl trading-engine --also-make to confirm testRSI_* and testBollingerBands_* tests pass.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 5: Add MACD tests to IndicatorEngineTest and implement MACD**
action: Add three tests to /Users/ericshort/AIProjects/TradingApp/trading-engine/src/test/java/com/tradingapp/engine/IndicatorEngineTest.java: (1) testMACD_BuySignal — 30 prices with steady rise [70,71,72,73,74,75,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99], call IndicatorEngine.computeMACD(prices), assert direction==BUY (rising prices → fast EMA > slow EMA → MACD line > signal or just positive MACD); (2) testMACD_SellSignal — 30 prices with steady decline [99,98,97,...,70], assert direction==SELL; (3) testMACD_InsufficientData — 25 prices (below 26+1 minimum), assert direction==NEUTRAL. Then add static computeMACD(List<Double> prices) to IndicatorEngine.java: requires prices.size() >= 27 else neutral. Compute 12-EMA and 26-EMA using the standard EMA formula: startEMA = SMA of first N prices; then for each subsequent price ema = price * k + prevEma * (1-k) where k=2/(N+1). macdLine = ema12 - ema26. Compute 9-EMA of macdLine over the last 9 MACD values (collect daily MACD values from index 26 onward). signalLine = ema9 of collected MACD values. Return buy if macdLine > signalLine, sell if macdLine < signalLine, neutral if equal, value=macdLine. Run mvn test -pl trading-engine --also-make until all MACD tests pass.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 6: Add MA crossover and volume surge tests and implementation**
action: Add tests to IndicatorEngineTest.java using the shared `engine` field: (1) testMACrossover_BuySignal — 200 prices: first 150 = 50.0, last 50 = 100.0; call IndicatorEngine.computeMACrossover(prices); assert direction==BUY (50-MA of last 50 prices=100 > 200-MA≈62.5); (2) testMACrossover_SellSignal — 200 prices: first 150 = 100.0, last 50 = 50.0; assert direction==SELL; (3) testMACrossover_InsufficientData — 199 prices, assert direction==NEUTRAL; (4) testVolumeSurge_Buy — List of 21 doubles where first 20 volumes = 1000.0 and current (index 20) = 2500.0, priceChange = +2.0 (positive), call IndicatorEngine.computeVolumeSurge(volumes, 2500.0, 2.0), assert direction==BUY; (5) testVolumeSurge_Sell — same but priceChange = -2.0, assert direction==SELL; (6) testVolumeSurge_NoSurge — current volume = 1500.0 (< 2× avg of 1000), assert direction==NEUTRAL. Implement in IndicatorEngine.java: computeMACrossover(List<Double> prices) — requires size>=200; shortMA = average of last 50 prices; longMA = average of all 200 prices; return buy if shortMA > longMA, sell if shortMA < longMA, neutral if equal; value = shortMA - longMA. computeVolumeSurge(List<Double> recentVolumes, double currentVolume, double priceChange) — requires recentVolumes.size()>=20; avgVolume = sum of first 20 / 20; return neutral if currentVolume < avgVolume*2; return buy if priceChange > 0, sell if priceChange < 0, neutral if priceChange==0; value = currentVolume/avgVolume. Run mvn test -pl trading-engine --also-make until all 12 IndicatorEngine tests pass.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 7: Add evaluateAll helper and run complete IndicatorEngine test suite**
action: Add instance methods to IndicatorEngine.java: (1) List<SignalResult> evaluateAll(List<Double> prices, List<Double> volumes, double currentPrice) — calls this.computeRSI(prices), this.computeMACD(prices), this.computeBollingerBands(prices, currentPrice); if prices.size()>=200 also call this.computeMACrossover(prices); if volumes.size()>=20 compute priceChange = prices.size()>=2 ? prices.get(prices.size()-1)-prices.get(prices.size()-2) : 0 then call this.computeVolumeSurge(volumes.subList(0, Math.min(20,volumes.size())), volumes.isEmpty()?0:volumes.get(volumes.size()-1), priceChange); return all non-null results as a List<SignalResult>; (2) int countBuySignals(List<SignalResult> signals) — count where direction==BUY; (3) int countSellSignals(List<SignalResult> signals) — count where direction==SELL. Add one test to IndicatorEngineTest using the `engine` field: testEvaluateAll_ReturnsAtLeastThreeSignals — 30 rising prices [70..99], 25 volumes all 1000.0, currentPrice = 100.0; call engine.evaluateAll(prices, volumes, 100.0); assert results.size() >= 3 (RSI, MACD, Bollinger at minimum). Run full test suite to confirm all 13 IndicatorEngine tests pass.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 8: Write and run FeeCalculator tests and implementation**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/test/java/com/tradingapp/engine/FeeCalculatorTest.java with JUnit 5 tests: (1) testFeePerShare — new FeeCalculator().calculateFee(100) == 1.00 (delta 0.001); (2) testFeeZeroShares — calculateFee(0) == 0.0; (3) testMaxShares_FivePercent — maxShares(100000.0, 200.0) == (int)(100000*0.05/200) == 25; (4) testMaxShares_SmallBalance — maxShares(500.0, 300.0) == (int)(500*0.05/300) == 0 (can't afford any shares at 5%); (5) testMaxShares_RoundsDown — maxShares(10000.0, 333.0) == (int)(500/333) == 1. Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/FeeCalculator.java with: double calculateFee(int shares) returning shares * 0.01; int maxShares(double balance, double pricePerShare) returning pricePerShare <= 0 ? 0 : (int)((balance * 0.05) / pricePerShare). Run mvn test -pl trading-engine --also-make until all FeeCalculator tests pass.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 9: Write and run TrailingStopMonitor tests and implementation**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/test/java/com/tradingapp/engine/TrailingStopMonitorTest.java with tests: (1) testNoTriggerWhenPriceRises — create TrailingStopMonitor, call updatePeak("AAPL", 100.0), then check("AAPL", 101.0) should return false; (2) testTriggerAtExactlyFivePercent — updatePeak("AAPL", 100.0), check("AAPL", 95.0) should return true; (3) testNoTriggerJustAboveFivePercent — updatePeak("AAPL", 100.0), check("AAPL", 95.01) should return false; (4) testPeakUpdatesOnHigherPrice — updatePeak("AAPL", 100.0), check("AAPL", 110.0) returns false and updates peak to 110, then check("AAPL", 104.4) returns false, check("AAPL", 104.49) returns false, check("AAPL", 104.5) returns true; (5) testResetClearsPeak — updatePeak("AAPL", 100.0), reset("AAPL"), updatePeak("AAPL", 50.0), check("AAPL", 47.5) returns true. Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/TrailingStopMonitor.java: field Map<String,Double> peaks = new HashMap<>(). void updatePeak(String symbol, double price) sets peaks.put(symbol, Math.max(price, peaks.getOrDefault(symbol, price))). boolean check(String symbol, double currentPrice) gets peak = peaks.getOrDefault(symbol, currentPrice); if currentPrice > peak updates peak and returns false; returns currentPrice <= peak * 0.95. void reset(String symbol) removes symbol from peaks. Run tests.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 10: Write failing OrderExecutor tests**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/test/java/com/tradingapp/engine/OrderExecutorTest.java. First create a temp SQLite DB path for TransactionLog in each test using @TempDir. Tests: (1) testBuyDeductsBalanceAndFee — new Account() (balance=100000), SafetyStop, TransactionLog(tempDb), FeeCalculator, new OrderExecutor(account, safetyStop, log, new FeeCalculator()). Call executor.buy("AAPL", 10, 150.0, "RSI=28", "RSI oversold"). Assert account.getBalance() == 100000 - (10*150) - 0.10 == 98499.90 (delta 0.01). Assert log.findAll().size()==1, record.getAction()==BUY, record.getSymbol()=="AAPL", record.getQuantity()==10, record.getPricePerUnit()==150.0, record.getFeeCharged()==0.10; (2) testSellIncreasesBalanceMinusFee — buy 10 AAPL at 150, then sell 10 AAPL at 160. Balance after sell = 98499.90 + (10*160) - 0.10 == 99099.80 (delta 0.01). log.findAll().size()==2; (3) testBuyRefusedWhenSafetyStopHalted — set account.setBalance(50.0) and account.setTradingHalted(true). Call executor.buy("AAPL", 1, 10.0, "", ""). Assert result is null (no trade) and balance unchanged at 50.0; (4) testBuyRefusedWhenBalanceBelowStop — new Account, setBalance(99.0). SafetyStop.check() triggers halt. Call executor.buy(). Assert null returned.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-engine/src/test/java/com/tradingapp/engine/OrderExecutorTest.java
  assert:
    kind: exists

- [ ] **Step 11: Implement OrderExecutor and run its tests**
action: Replace /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/OrderExecutor.java with a full implementation. Constructor takes (Account account, SafetyStop safetyStop, TransactionLog log, FeeCalculator fees). Import com.tradingapp.account.*. Method TransactionRecord buy(String symbol, int shares, double price, String signals, String reason): (1) if safetyStop.check() || account.isTradingHalted() return null; (2) double fee = fees.calculateFee(shares); (3) double totalCost = shares * price + fee; (4) if account.getBalance() < totalCost return null; (5) account.setBalance(account.getBalance() - totalCost); (6) account.addOrUpdatePosition(symbol, shares, price, Position.PositionType.STOCK); (7) TransactionRecord r = new TransactionRecord(symbol, TransactionAction.BUY, shares, price, fee, account.getBalance(), reason, signals); (8) log.insert(r); return r. Method TransactionRecord sell(String symbol, int shares, double price, String signals, String reason): (1) if safetyStop.check() return null; (2) double fee = fees.calculateFee(shares); (3) double proceeds = shares * price - fee; (4) account.setBalance(account.getBalance() + proceeds); (5) account.removePosition(symbol); (6) account.addRealizedPnL(proceeds - (shares * price)); wait, that's wrong — realized PnL = proceeds - cost basis. Simplify: just add proceeds to balance, skip PnL for now (Phase 4 will refine). (7) TransactionRecord r = new TransactionRecord(symbol, TransactionAction.SELL, shares, price, fee, account.getBalance(), reason, signals); (8) log.insert(r); return r. Run mvn test -pl trading-engine --also-make until all tests including OrderExecutorTest pass.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -pl trading-engine --also-make

- [ ] **Step 12: Implement TradingLoop**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/TradingLoop.java. Import java.time.*, java.util.*, java.util.function.Consumer, com.tradingapp.data.*, com.tradingapp.account.*. Fields: private static final int SIGNAL_THRESHOLD = 2; private static final LocalTime MARKET_OPEN = LocalTime.of(9,30); private static final LocalTime MARKET_CLOSE = LocalTime.of(16,0); private static final ZoneId ET = ZoneId.of("America/New_York"); private final YahooFinanceClient dataClient; private final PriceHistory priceHistory; private final IndicatorEngine indicators; private final TrailingStopMonitor trailingStop; private final OrderExecutor orderExecutor; private final FeeCalculator fees; private final List<String> watchList; private final Consumer<String> researchCallback; private final Runnable uiRefreshCallback. Constructor sets all fields. Implement run(): (1) ZonedDateTime now = ZonedDateTime.now(ET); if now.toLocalTime() is before MARKET_OPEN or after MARKET_CLOSE, researchCallback.accept("Market closed. Next open: " + MARKET_OPEN + " ET."); return. (2) if orderExecutor's account is halted, researchCallback.accept("TRADING HALTED — balance below $100."); return. (3) List<QuoteModel> quotes = dataClient.getQuotes(watchList); for each quote: priceHistory.record(symbol, price, volume); List<Double> prices = priceHistory.getPrices(symbol); List<Double> volumes = priceHistory.getVolumes(symbol); List<SignalResult> signals = indicators.evaluateAll(prices, volumes, price); int buys = indicators.countBuySignals(signals); int sells = indicators.countSellSignals(signals); String signalStr = signals stream map SignalResult::toString joined with ", "; boolean hasPosition = account.getPositions().containsKey(symbol); (4) check trailingStop.check(symbol, price): if true and hasPosition, int qty = account.getPositions().get(symbol).getQuantity(); orderExecutor.sell(symbol, qty, price, signalStr, "Trailing stop: 5% drawdown from peak"); uiRefreshCallback.run(); (5) else if sells >= SIGNAL_THRESHOLD and hasPosition: sell all shares; (6) else if buys >= SIGNAL_THRESHOLD and !hasPosition: int shares = fees.maxShares(account.getBalance(), price); if shares > 0: orderExecutor.buy(symbol, shares, price, signalStr, "Signals: " + buys + "/" + signals.size() + " BUY"); uiRefreshCallback.run(); (7) trailingStop.updatePeak(symbol, price); (8) researchCallback.accept(now.toLocalTime() + " | " + symbol + " $" + String.format("%.2f",price) + " | " + signalStr + " | BUY=" + buys + " SELL=" + sells). The Account reference is obtained by calling a package-private getter on OrderExecutor or pass it separately — add a private final Account account field to TradingLoop, set in constructor.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-engine/src/main/java/com/tradingapp/engine/TradingLoop.java
  assert:
    kind: exists

- [ ] **Step 13: Extend DashboardController to wire and start TradingLoop**
action: Modify /Users/ericshort/AIProjects/TradingApp/trading-ui/src/main/java/com/tradingapp/ui/DashboardController.java. Add imports: com.tradingapp.engine.IndicatorEngine, com.tradingapp.engine.TrailingStopMonitor, com.tradingapp.engine.FeeCalculator, com.tradingapp.engine.OrderExecutor, com.tradingapp.engine.TradingLoop, com.tradingapp.data.PriceHistory, com.tradingapp.data.LargeCapWatchList, java.util.concurrent.Executors, java.util.concurrent.ScheduledExecutorService, java.util.concurrent.TimeUnit, java.util.function.Consumer, javafx.application.Platform. Add fields: private ScheduledExecutorService scheduler; private TradingLoop tradingLoop. At the end of initialize(), after setting up the UI: (1) YahooFinanceClient dataClient = new YahooFinanceClient(); (2) PriceHistory priceHistory = new PriceHistory(); (3) IndicatorEngine indicatorEngine = new IndicatorEngine(); (4) TrailingStopMonitor trailingStop = new TrailingStopMonitor(); (5) FeeCalculator feeCalc = new FeeCalculator(); (6) SafetyStop safetyStop = new SafetyStop(account); (7) OrderExecutor orderExecutor = new OrderExecutor(account, safetyStop, transactionLog, feeCalc); (8) Consumer<String> researchCb = msg -> Platform.runLater(() -> researchArea.appendText(msg + "\n")); (9) Runnable uiRefresh = () -> Platform.runLater(this::refreshUi); (10) tradingLoop = new TradingLoop(dataClient, priceHistory, indicatorEngine, trailingStop, orderExecutor, feeCalc, LargeCapWatchList.SYMBOLS, researchCb, uiRefresh, account); (11) scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "trading-loop"); t.setDaemon(true); return t; }); (12) scheduler.scheduleAtFixedRate(tradingLoop, 0, 60, TimeUnit.SECONDS). Add private void refreshUi(): reloads transactionLog.findAll() into the table's ObservableList, updates balanceLabel, winsLabel, lossesLabel, pnlLabel, and haltedLabel (show "⛔ TRADING HALTED" if account.isTradingHalted()). Add public void stop(): calls scheduler.shutdownNow(). In TradingApp.java override public void stop() to retrieve the controller via a stored field (add private DashboardController controller; set it after FXMLLoader.load() using loader.getController()) and call controller.stop().
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-ui/src/main/java/com/tradingapp/ui/DashboardController.java
  assert:
    kind: exists

- [ ] **Step 14: Compile all modules**
action: From /Users/ericshort/AIProjects/TradingApp run: source ~/.sdkman/bin/sdkman-init.sh && mvn compile --also-make -pl trading-ui. Fix any compilation errors in TradingLoop (missing imports, incorrect method calls on Account or OrderExecutor), DashboardController (missing imports, wrong Consumer/Runnable wiring), or TradingApp.java (controller reference). Common fixes: IndicatorEngine may need to be instantiated (change static methods to instance methods if needed, or keep static and remove the new IndicatorEngine() instantiation — use IndicatorEngine as a utility class with all-static methods and call IndicatorEngine.evaluateAll() directly). Also fix the Account access in TradingLoop — add a getAccount() getter to OrderExecutor or pass Account separately to TradingLoop. Goal is a clean compile.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn compile -pl trading-ui --also-make exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn compile -pl trading-ui --also-make

- [ ] **Step 15: Full multi-module build and test**
action: From /Users/ericshort/AIProjects/TradingApp run: source ~/.sdkman/bin/sdkman-init.sh && mvn clean test. All Phase 1 tests (YahooFinanceClientTest, AccountTest, TransactionLogTest) must still pass. All new Phase 2 tests (IndicatorEngineTest, FeeCalculatorTest, TrailingStopMonitorTest, OrderExecutorTest) must pass. Fix any remaining test failures. Do not skip tests.
loop: until /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn clean test exits 0
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn clean test

- [ ] **Step 16: Smoke-test app launch with trading loop active**
action: From /Users/ericshort/AIProjects/TradingApp run: source ~/.sdkman/bin/sdkman-init.sh && mvn install -DskipTests -q && mvn -pl trading-ui javafx:run. Observe the JavaFX window. The research area should show tick output within 60 seconds: either "Market closed. Next open: 09:30 ET." if outside market hours, or live signal evaluations (symbol, price, signal values) if during 9:30–16:00 ET. Confirm no Java exceptions appear in the console output. Close the window.
loop: false
max_iterations: 1
verify:
  type: human-review
  prompt: Confirm the app launched without Java exceptions. The research area shows either "Market closed" (if outside 9:30-16:00 ET) or live signal lines (symbol, price, signal values) within 60 seconds. The balance still shows $100,000.00. Close the window when done.

- [ ] **Step 17: Phase 2 acceptance gate**
action: Run source ~/.sdkman/bin/sdkman-init.sh && mvn clean test to confirm all tests pass. Record the test output showing IndicatorEngine, FeeCalculator, TrailingStopMonitor, and OrderExecutor tests all green.
loop: false
max_iterations: 1
gate: human
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn clean test
