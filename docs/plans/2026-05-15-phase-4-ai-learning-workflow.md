---
intent: Replace raw indicator vote-counting with Weka-trained per-indicator weights that
        learn from historical trade outcomes, so profitable indicators gain influence.
success_criteria: All tests pass; MLSignalEvaluator score drives TradingLoop buy decisions;
                  features CSV in DB; weights JSON persisted; dashboard opens clean.
risk_level: low
auto_approve: true
worktree: false
---

## Steps

- [x] **Step 1: Add features column to TransactionRecord and TransactionLog**
action: Add `private String features` field to TransactionRecord with getter/setter. In TransactionLog.initDb(), after the CREATE TABLE statement, add a second stmt.execute to run `ALTER TABLE transactions ADD COLUMN features TEXT`. Catch the SQLException and silently continue only when e.getMessage() contains "duplicate column name" (pre-existing DB). Update insert() to bind features as parameter 10 (setString(10, record.getFeatures())). Update the INSERT SQL to include features in the column list and add the ? placeholder. Update findAll() to call r.setFeatures(rs.getString("features")).
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 2: Add SignalWeightEvaluator interface and update trading-ai pom.xml**
action: Create `trading-engine/src/main/java/com/tradingapp/engine/SignalWeightEvaluator.java` with two methods: `double weightedBuyScore(java.util.List<SignalResult> signals)` and `double weightedSellScore(java.util.List<SignalResult> signals)`. In trading-ai/pom.xml, add a dependency on trading-engine (groupId=com.tradingapp, artifactId=trading-engine, no version needed — parent manages it).
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-engine,trading-ai compile -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 3: Add FeatureVector, LabeledTrade, and TradeFeatureExtractor to trading-ai**
action: Create FeatureVector in com.tradingapp.ai with five double fields (rsi, macd, bollinger, maCrossover, volRatio) and a constructor taking all five. Create LabeledTrade with a FeatureVector field and a boolean win field. Create TradeFeatureExtractor with a single method `List<LabeledTrade> extract(TransactionLog log)`: call log.findAll(), iterate to find BUY records whose features field is non-null, then scan forward in the list for the next SELL record with the same symbol; if found, set win = (sell.getPricePerUnit() > buy.getPricePerUnit()); parse the features CSV string by splitting on comma and constructing a FeatureVector. Return only paired trades. Write TradeFeatureExtractorTest that inserts 4 trades (2 BUY with features, 2 SELL) into a temp SQLite DB, calls extract(), and asserts 2 LabeledTrade results with correct win flags.
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account,trading-engine,trading-ai test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 4: Add SignalWeights and SignalWeightTrainer**
action: Create SignalWeights in com.tradingapp.ai: field `double[] weights` of length 5 (indices 0=RSI,1=MACD,2=Bollinger,3=MACrossover,4=VolumeSurge), default value 1.0 each. Add `getWeight(int i)`. Add `toJson()` that produces a JSON object with named keys (e.g., `{"RSI":1.0,"MACD":1.0,...}`). Add static `fromJson(String)`. Add `save(Path)` and static `load(Path)` using toJson/fromJson. Create SignalWeightTrainer in com.tradingapp.ai with method `SignalWeights train(List<LabeledTrade> trades)`: if trades.size() < 10, return default SignalWeights. Otherwise build a Weka Instances with 5 numeric attributes plus a nominal class attribute (WIN/LOSE), add each LabeledTrade as an Instance. Run InfoGainAttributeEval with Ranker. Map the ranked info-gain scores to weights: weight[i] = score[i] + 0.01 (avoid zero), then normalize so sum(weights) = 5.0. Return a new SignalWeights with those values. Write SignalWeightTrainerTest: create 20 LabeledTrades where RSI is always high for wins and low for losses, verify returned weights sum to 5.0 and all weights are positive.
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account,trading-engine,trading-ai test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 5: Add MLSignalEvaluator**
action: Create MLSignalEvaluator in com.tradingapp.ai implementing SignalWeightEvaluator. Constructor takes SignalWeights (stored as field). `weightedBuyScore(List<SignalResult> signals)`: for each SignalResult where direction==BUY, look up index by indicator name (RSI→0, MACD→1, BollingerBands→2, MACrossover→3, VolumeSurge→4), add weights.getWeight(index) to total; return total. `weightedSellScore(List<SignalResult> signals)`: same but for SELL direction signals. Add `void retrain(TransactionLog log)` method: extract labeled trades, train new weights, update the held SignalWeights fields by copying new values (do not replace reference — weights field is final), save to Path.of(System.getProperty("user.home"), ".tradingapp", "signal-weights.json"). Add `String getWeightsSummary()` that returns "RSI=%.2f MACD=%.2f Bollinger=%.2f MACrossover=%.2f VolumeSurge=%.2f" with current weights. Write MLSignalEvaluatorTest: construct with weights [2,1,1,0.5,0.5]; call weightedBuyScore with signals [RSI=BUY, MACD=SELL, Bollinger=BUY]; expect 3.0 (2+1).
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account,trading-engine,trading-ai test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 6: Add features param overload to OrderExecutor.buy()**
action: In OrderExecutor, add a new method `public TransactionRecord buy(String symbol, int shares, double price, String signals, String reason, String features)`. This method mirrors the existing 5-param buy() but calls `r.setFeatures(features)` on the TransactionRecord before calling log.insert(r). Keep the existing 5-param buy() as-is (it effectively passes null features since the record's features field defaults to null). Write a test in OrderExecutorTest confirming that the 6-param buy() stores the features string and it round-trips via TransactionLog.findAll().
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account,trading-engine test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 7: Modify TradingLoop for weighted scoring, feature capture, and training trigger**
action: Add two constructor params to TradingLoop: `SignalWeightEvaluator weightEval` (nullable) and `Runnable afterMarketCallback` (nullable). Update all existing public constructors to pass null for both. Add the fully-wired package-private constructor that accepts all params including these two. Add field `private LocalDate lastTrainingDate`. Add private helper `String extractFeatureCsv(List<SignalResult> signals)` that extracts the double value from each signal by name in order [RSI, MACD, BollingerBands, MACrossover, VolumeSurge], returning a comma-separated string of the 5 values (0.0 if the indicator is absent in the list). In run(): change `buys >= SIGNAL_THRESHOLD` to `weightedBuyScore(signals) >= SIGNAL_THRESHOLD` where `weightedBuyScore = (weightEval != null) ? weightEval.weightedBuyScore(signals) : (double) buys`. Change `sells >= SIGNAL_THRESHOLD` to `weightedSellScore = (weightEval != null) ? weightEval.weightedSellScore(signals) : (double) sells`. When the weighted buy branch fires and shares > 0, compute `String featureCsv = extractFeatureCsv(signals)` and call the 6-param `orderExecutor.buy(symbol, shares, price, signalStr, reason, featureCsv)`. In the after-market-close early-return block, before returning, check: `if (afterMarketCallback != null && !LocalDate.now(ET).equals(lastTrainingDate)) { lastTrainingDate = LocalDate.now(ET); afterMarketCallback.run(); }`. Write TradingLoopTest cases: (a) with a mock SignalWeightEvaluator that returns 3.0 for buys, verify buy fires; (b) verify extractFeatureCsv returns 5 comma-separated values.
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account,trading-data,trading-engine test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 8: Wire MLSignalEvaluator in DashboardController**
action: In DashboardController.initialize(), after creating transactionLog and account, add: `java.nio.file.Path weightsPath = java.nio.file.Path.of(System.getProperty("user.home"), ".tradingapp", "signal-weights.json"); com.tradingapp.ai.SignalWeights initialWeights = java.nio.file.Files.exists(weightsPath) ? com.tradingapp.ai.SignalWeights.load(weightsPath) : new com.tradingapp.ai.SignalWeights(); com.tradingapp.ai.MLSignalEvaluator mlEval = new com.tradingapp.ai.MLSignalEvaluator(initialWeights);`. Add training-ui/pom.xml dependency on trading-ai. In the TradingLoop constructor call, pass mlEval as the SignalWeightEvaluator and pass `() -> { mlEval.retrain(transactionLog); Platform.runLater(() -> researchArea.appendText("ML weights updated: " + mlEval.getWeightsSummary() + "\n")); }` as the afterMarketCallback.
loop: false
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account,trading-data,trading-engine,trading-ai,trading-ui compile -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 9: Full test suite**
action: Run the complete Maven test suite to confirm all existing and new tests pass with zero failures.
loop: until all tests pass
max_iterations: 3
verify: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -f /Users/ericshort/AIProjects/TradingApp/pom.xml

- [x] **Step 10: Smoke test — dashboard launch**
action: Install all modules then launch the JavaFX dashboard. Confirm the window opens, the research pane shows stock signal lines, and no exceptions appear in the console.
loop: false
verify:
  type: human-review
  prompt: Dashboard window opens; research pane shows stock signal lines; no startup errors in console
gate: human
