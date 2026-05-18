---
intent: Add single-leg call/put options trading with Black-Scholes local pricing, Greeks display, and signal-driven entry/exit
success_criteria: Call/put executes on ≥2 stock signals; BS price and all four Greeks computed; position closes on reversal or <3 days to expiry; $0.65/contract fee; all 34 prior tests pass; new options tests pass
risk_level: low
auto_approve: true
worktree: false
---

## Steps

- [x] **Step 1: Add trading-options Maven module**
action: Create trading-options/pom.xml as a new Maven module. Parent group=com.tradingapp, artifactId=trading-options, packaging=jar. Add dependencies: trading-account, trading-data. Add trading-options to the parent pom.xml <modules> list. Add trading-options as a dependency in trading-ui/pom.xml. Create the standard Maven source directory structure: trading-options/src/main/java/com/tradingapp/options/ and trading-options/src/test/java/com/tradingapp/options/.
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options validate -q && echo "MODULE_OK"

- [x] **Step 2: Add OptionsPosition to trading-account**
action: Create trading-account/src/main/java/com/tradingapp/account/OptionsPosition.java with fields: String symbol, String type (CALL or PUT), double strike, java.time.LocalDate expiry, int contracts, double premiumPaid. Add method long daysToExpiry() using ChronoUnit.DAYS.between(LocalDate.now(), expiry). Add method double getCurrentValue(double currentPremium) returning currentPremium * 100 * contracts. Add a constructor taking all five fields. In Account.java add: private final Map<String,OptionsPosition> optionsPositions = new ConcurrentHashMap<>(), public Map<String,OptionsPosition> getOptionsPositions() returning Collections.unmodifiableMap(optionsPositions), public synchronized void addOptionsPosition(String key, OptionsPosition pos) calling optionsPositions.put(key, pos), public void removeOptionsPosition(String key) calling optionsPositions.remove(key).
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-account compile -q && echo "COMPILE_OK"

- [x] **Step 3: Implement GreeksResult value object**
action: Create trading-options/src/main/java/com/tradingapp/options/GreeksResult.java with fields: double delta, gamma, theta, vega. Constructor taking all four. toString() returning "delta=%.4f gamma=%.4f theta=%.4f vega=%.4f" formatted with the four values.
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options compile -q && echo "COMPILE_OK"

- [x] **Step 4: Implement BlackScholesEngine**
action: Create trading-options/src/main/java/com/tradingapp/options/BlackScholesEngine.java. Implement the following public methods:
  (a) double historicalVol(List<Double> prices): compute log returns ln(p[i]/p[i-1]) for i=1..n-1, then annualized std dev = stdDev(logReturns) * sqrt(252). Return 0.0 if fewer than 2 prices or computed vol < 0.001.
  (b) LocalDate nextMonthlyExpiry(): find the third Friday of the current or a future month that is at least 14 days from LocalDate.now(). Third Friday = the Friday with dayOfMonth between 15 and 21 inclusive.
  (c) double roundStrike(double price): round to nearest 5.0 using Math.round(price / 5.0) * 5.0.
  (d) double timeToExpiry(LocalDate expiry): return ChronoUnit.DAYS.between(LocalDate.now(), expiry) / 365.0.
  (e) double callPrice(double S, double K, double r, double T, double sigma): standard Black-Scholes European call. d1 = (ln(S/K) + (r + sigma*sigma/2) * T) / (sigma * sqrt(T)), d2 = d1 - sigma*sqrt(T), return S*N(d1) - K*exp(-r*T)*N(d2). Use org.apache.commons.math3 NormalDistribution or implement N() as 0.5*(1+erf(x/sqrt(2))) via a series approximation.
  (f) double putPrice(double S, double K, double r, double T, double sigma): K*exp(-r*T)*N(-d2) - S*N(-d1).
  (g) GreeksResult greeks(double S, double K, double r, double T, double sigma, boolean isCall): delta = isCall ? N(d1) : N(d1)-1, gamma = phi(d1)/(S*sigma*sqrt(T)), theta = (-(S*phi(d1)*sigma)/(2*sqrt(T)) - r*K*exp(-r*T)*(isCall ? N(d2) : N(-d2))) / 365, vega = S*phi(d1)*sqrt(T)*0.01. phi(x) = standard normal PDF.
  For the normal CDF N(x), implement using the Horner approximation (Abramowitz & Stegun 26.2.17) rather than importing Apache Commons, so no new dependency is needed: use the standard 6-coefficient rational approximation that is accurate to 7.5e-8.
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options compile -q && echo "COMPILE_OK"

- [x] **Step 5: Write BlackScholesEngine tests**
action: Create trading-options/src/test/java/com/tradingapp/options/BlackScholesEngineTest.java with JUnit 5 tests:
  (a) testCallPriceKnownValue: S=100, K=100, r=0.04, T=0.5, sigma=0.2 — assert callPrice within 0.01 of 9.93.
  (b) testPutPriceKnownValue: same inputs — assert putPrice within 0.01 of 8.03 (put-call parity: P = C - S + K*exp(-r*T)).
  (c) testCallDeltaRange: delta for call must be in [0,1].
  (d) testGammaNonNegative: gamma must be >= 0.
  (e) testThetaNonPositive: theta must be <= 0.
  (f) testVegaNonNegative: vega must be >= 0.
  (g) testHistoricalVolFlatPrices: list of 20 identical prices returns 0.0.
  (h) testHistoricalVolPositive: prices with clear variation return vol > 0.
  (i) testRoundStrike: roundStrike(147.3) == 145.0, roundStrike(147.6) == 150.0.
  (j) testNextMonthlyExpiryIsThirdFriday: returned date is a Friday with dayOfMonth in [15,21].
  (k) testNextMonthlyExpiryAtLeast14DaysOut: returned date is >= LocalDate.now().plusDays(14).
loop: until all tests pass
max_iterations: 4
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options test -q && echo "TESTS_PASS"
gate: human

- [x] **Step 6: Implement OptionsOrderExecutor**
action: Create trading-options/src/main/java/com/tradingapp/options/OptionsOrderExecutor.java. Constructor: (Account account, TransactionLog transactionLog). Use a private constant OPTIONS_CONTRACT_FEE = 0.65 (do NOT import FeeCalculator — options use a flat per-contract fee, not FeeCalculator's per-share fee).
  (a) void buyCall(String symbol, double strike, LocalDate expiry, int contracts, double premium, String signalStr): fee = OPTIONS_CONTRACT_FEE * contracts, totalCost = premium * 100 * contracts + fee. If account.getBalance() < totalCost return without executing. Deduct totalCost from account via account.setBalance(account.getBalance() - totalCost). Create OptionsPosition(symbol, "CALL", strike, expiry, contracts, premium). Call account.addOptionsPosition(symbol + "_CALL", position). Write TransactionRecord with action="CALL_BUY", symbol, quantity=contracts, pricePerUnit=premium, feeCharged=fee, balanceAfter=account.getBalance(), reason=signalStr. Log via transactionLog.save(record).
  (b) void buyPut(String symbol, double strike, LocalDate expiry, int contracts, double premium, String signalStr): same as buyCall but action="PUT_BUY" and key=symbol+"_PUT", type="PUT".
  (c) void closePosition(String positionKey, double currentPremium, String reason): look up position in account.getOptionsPositions(). If not found, return. proceeds = currentPremium * 100 * position.getContracts(), fee = OPTIONS_CONTRACT_FEE * position.getContracts(). net = proceeds - fee. account.setBalance(account.getBalance() + net). account.removeOptionsPosition(positionKey). double pnl = net - position.getPremiumPaid() * 100 * position.getContracts(). account.addRealizedPnL(pnl). String action = position.getType().equals("CALL") ? "CALL_SELL" : "PUT_SELL". Write and save TransactionRecord with action, symbol from position, contracts, currentPremium, fee, balanceAfter, reason.
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options compile -q && echo "COMPILE_OK"

- [x] **Step 7: Write OptionsOrderExecutor tests**
action: Create trading-options/src/test/java/com/tradingapp/options/OptionsOrderExecutorTest.java with JUnit 5 and @TempDir. Use a real Account and TransactionLog(tempDir path). No FeeCalculator import needed.
  (a) testBuyCallDeductsBalanceAndFee: balance=100_000, premium=5.0, 1 contract, strike=150, expiry=LocalDate.now().plusDays(30). After buyCall: balance == 100_000 - 5.0*100 - 0.65 == 99_494.35. One CALL_BUY record in log.
  (b) testBuyCallInsufficientBalance: set balance=0. buyCall does nothing — balance still 0, no position, no log entry.
  (c) testCloseCallPosition: buy 1 contract at premium 5.0 then close at premium 8.0. Net = 8.0*100 - 0.65 = 799.35. Balance after close = 99_494.35 + 799.35 = 100_293.70. One CALL_SELL record in log.
  (d) testBuyPutCreatesPosition: after buyPut, getOptionsPositions() contains key symbol+"_PUT" with type PUT.
loop: until all tests pass
max_iterations: 3
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options test -Dtest=OptionsOrderExecutorTest -q && echo "TESTS_PASS"

- [x] **Step 8: Implement OptionsSignalRouter**
action: Create trading-options/src/main/java/com/tradingapp/options/OptionsSignalRouter.java. Constructor: (BlackScholesEngine bsEngine, OptionsOrderExecutor optExec, Account account, PriceHistory priceHistory, Consumer<String> researchCallback). Import PriceHistory from com.tradingapp.data. Do NOT import FeeCalculator — contract count uses inline arithmetic: Math.min(5, (int)(account.getBalance() * 0.05 / (premium * 100))).
  Method: void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr).
  Logic:
    (1) Close existing positions that have expired or reversed:
        - String callKey = symbol + "_CALL", putKey = symbol + "_PUT"
        - Map<String,OptionsPosition> opts = account.getOptionsPositions()
        - If opts contains callKey: get call pos. If sellSignals >= 2 or pos.daysToExpiry() < 3: compute current BS call price, close via optExec.closePosition(callKey, currentPremium, reason). Log closure to researchCallback.
        - If opts contains putKey: get put pos. If buySignals >= 2 or pos.daysToExpiry() < 3: compute current BS put price, close. Log.
    (2) Open new positions (after potential closures):
        - Get prices = priceHistory.getPrices(symbol). If fewer than 2, return.
        - Compute sigma = bsEngine.historicalVol(prices). If sigma == 0, log "skip: vol=0" and return.
        - LocalDate expiry = bsEngine.nextMonthlyExpiry().
        - double K = bsEngine.roundStrike(price).
        - double T = bsEngine.timeToExpiry(expiry). If T <= 0, return.
        - If buySignals >= 2 and opts does not contain callKey:
            double premium = bsEngine.callPrice(price, K, 0.04, T, sigma).
            int contracts = Math.min(5, (int)(account.getBalance() * 0.05 / (premium * 100))).
            If contracts >= 1: optExec.buyCall(symbol, K, expiry, contracts, premium, signalStr).
            GreeksResult g = bsEngine.greeks(price, K, 0.04, T, sigma, true).
            Log: symbol + " CALL K=" + K + " exp=" + expiry + " x" + contracts + " prem=" + String.format("%.2f", premium) + " | " + g.toString()
        - If sellSignals >= 2 and opts does not contain putKey:
            same for put using putPrice and isCall=false.
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options compile -q && echo "COMPILE_OK"

- [x] **Step 9: Write OptionsSignalRouter tests**
action: Create trading-options/src/test/java/com/tradingapp/options/OptionsSignalRouterTest.java with JUnit 5 and @TempDir.
  Setup: real Account (balance 100_000), real TransactionLog(tempDir path), real BlackScholesEngine, real PriceHistory. No FeeCalculator needed. Load 25 prices into PriceHistory for "AAPL" using a list with slight variation (e.g., 149.0 + i*0.1 for i 0..24). currentPrice = 150.0.
  (a) testCallOpenedOnBuySignals: call evaluate("AAPL", 150.0, 2, 0, "test"). Assert account.getOptionsPositions() contains "AAPL_CALL".
  (b) testPutOpenedOnSellSignals: call evaluate("AAPL", 150.0, 0, 2, "test"). Assert "AAPL_PUT" exists.
  (c) testCallClosedOnReversal: first evaluate with buySignals=2 to open call, then evaluate with sellSignals=2. Assert "AAPL_CALL" no longer in optionsPositions.
  (d) testNoTradeWithZeroVol: load 25 identical prices (150.0). evaluate with buySignals=2. Assert no position opened (vol guard).
  (e) testNoTradeInsufficientPriceHistory: fresh PriceHistory with 1 price. evaluate. Assert no position.
loop: until all tests pass
max_iterations: 3
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-options test -Dtest=OptionsSignalRouterTest -q && echo "TESTS_PASS"

- [x] **Step 10: Wire OptionsSignalRouter into TradingLoop via OptionsEvaluator interface**
action: To avoid a circular dependency (trading-options already depends on trading-account; trading-engine must not depend on trading-options), add a functional interface to trading-engine: create trading-engine/src/main/java/com/tradingapp/engine/OptionsEvaluator.java with a single method: void evaluate(String symbol, double price, int buySignals, int sellSignals, String signalStr). In TradingLoop.java, add field: private final OptionsEvaluator optionsEvaluator. Add a new constructor overload (package-private, clock-injectable) with OptionsEvaluator as the last parameter. Existing constructors without OptionsEvaluator delegate with null. In run(), after trailingStop.updatePeak(symbol, price) and before researchCallback.accept(), add: if (optionsEvaluator != null) { optionsEvaluator.evaluate(symbol, price, buys, sells, signalStr); } In trading-options/pom.xml, add trading-engine as a dependency so OptionsSignalRouter can implement OptionsEvaluator. OptionsSignalRouter implements OptionsEvaluator (add "implements OptionsEvaluator" and ensure method signature matches).
loop: false
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn -pl trading-engine,trading-options compile -q && echo "COMPILE_OK"

- [x] **Step 11: Wire into DashboardController and confirm all existing tests pass**
action: Modify trading-ui/src/main/java/com/tradingapp/ui/DashboardController.java. In the initialize() method, after constructing orderExecutor, add: BlackScholesEngine bsEngine = new BlackScholesEngine(); OptionsOrderExecutor optExec = new OptionsOrderExecutor(account, transactionLog); OptionsSignalRouter optionsRouter = new OptionsSignalRouter(bsEngine, optExec, account, priceHistory, researchCb); Then update the TradingLoop constructor call to use the new overload that accepts OptionsEvaluator as the last argument, passing optionsRouter (which implements OptionsEvaluator). Add imports for com.tradingapp.options.BlackScholesEngine, com.tradingapp.options.OptionsOrderExecutor, com.tradingapp.options.OptionsSignalRouter, and com.tradingapp.engine.OptionsEvaluator. Run the full test suite across all modules to confirm all 34 existing tests plus all new options tests pass.
loop: until all tests pass
max_iterations: 3
verify:
  type: shell
  command: /Users/ericshort/.sdkman/candidates/maven/current/bin/mvn test -q && echo "ALL_TESTS_PASS"

- [x] **Step 12: Smoke test — launch app and verify options activity in research pane**
action: Run the JavaFX application. During market hours, observe the research text area for options-related log lines (e.g., "AAPL CALL K=150.0 exp=2026-06-20 x1 prem=X.XX | delta=..."). Confirm the app launches without errors and the research pane shows both stock signal lines and options signal lines.
loop: false
verify:
  type: human-review
  check: App launches cleanly. Research pane shows options lines with strike, expiry, contracts, premium, and Greeks. No exceptions in the research area.
gate: human
