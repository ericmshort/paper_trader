---
intent: Set up Maven multi-module scaffold, Yahoo Finance data client, account model with safety stop, SQLite transaction log, and basic JavaFX dashboard shell for the AI Paper Trading Simulator.
success_criteria:
  - mvn clean test passes on all 5 modules
  - App launches showing $100,000 starting balance
  - YahooFinanceClient returns valid quotes for ≥5 large-cap symbols (AAPL, MSFT, GOOGL, AMZN, META)
  - SafetyStop unit test confirms trading halts when balance drops below $100
  - TransactionLog persists to SQLite and records survive JVM restart (verified by test)
risk_level: low
auto_approve: true
worktree: false
---

## Steps

- [ ] **Step 1: Create Maven parent POM**
action: Create /Users/ericshort/AIProjects/TradingApp/pom.xml as a Maven parent POM with the following configuration: groupId=com.tradingapp, artifactId=trading-app, version=1.0-SNAPSHOT, packaging=pom. Declare 5 child modules: trading-data, trading-account, trading-engine, trading-ai, trading-ui. Set maven.compiler.source and maven.compiler.target to 21. In dependencyManagement, declare: (1) com.yahoofinance-api:YahooFinanceAPI:3.17.0, (2) org.xerial:sqlite-jdbc:3.45.1.0, (3) org.openjfx:javafx-controls:21.0.2, (4) org.openjfx:javafx-fxml:21.0.2, (5) nz.ac.waikato.cms.weka:weka-stable:3.8.6, (6) org.junit.jupiter:junit-jupiter:5.10.2 scope=test. Add the maven-surefire-plugin version 3.2.5 configured to use JUnit Platform. Add the org.openjfx:javafx-maven-plugin:0.0.8 in pluginManagement with mainClass=com.tradingapp.ui.TradingApp.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: pom.xml
  assert:
    kind: exists

- [ ] **Step 2: Scaffold all 5 Maven module directory trees**
action: For each of the 5 modules (trading-data, trading-account, trading-engine, trading-ai, trading-ui), create the following directories under /Users/ericshort/AIProjects/TradingApp/<module>/: src/main/java/com/tradingapp/<suffix>/ and src/test/java/com/tradingapp/<suffix>/ where suffix is: data, account, engine, ai, ui respectively. Also create src/main/resources/ for trading-ui. Then create a pom.xml in each module directory with: parent pointing to com.tradingapp:trading-app:1.0-SNAPSHOT, artifactId=<module-name>, packaging=jar. Add module-specific dependencies: trading-data gets com.yahoofinance-api:YahooFinanceAPI; trading-account gets org.xerial:sqlite-jdbc; trading-engine depends on trading-data and trading-account (intra-project deps); trading-ai depends on trading-account and nz.ac.waikato.cms.weka:weka-stable; trading-ui depends on trading-data, trading-account, trading-engine, trading-ai, org.openjfx:javafx-controls, org.openjfx:javafx-fxml (also add the javafx-maven-plugin from pluginManagement). Add org.junit.jupiter:junit-jupiter scope=test to every module.
loop: false
max_iterations: 1
verify:
  - type: artifact
    path: trading-data/pom.xml
    assert:
      kind: exists
  - type: artifact
    path: trading-account/pom.xml
    assert:
      kind: exists
  - type: artifact
    path: trading-ui/src/main/resources
    assert:
      kind: exists

- [ ] **Step 3: Verify multi-module structure compiles**
action: From /Users/ericshort/AIProjects/TradingApp run: mvn compile. Fix any POM configuration errors (missing parent relativePath, incorrect dependency scopes, plugin configuration). The goal is a clean compile with all 5 modules resolving their POMs and downloading dependencies.
loop: until mvn compile exits 0
max_iterations: 3
verify: mvn compile

- [ ] **Step 4: Create QuoteModel and LargeCapWatchList**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-data/src/main/java/com/tradingapp/data/QuoteModel.java with fields: String symbol, double price, double bid, double ask, long volume, long timestamp (epoch ms), boolean stale. Add a constructor, getters, and a static factory method fromYahooStock(yahoo.finance.model.Stock stock) that maps Yahoo Finance API fields. Create /Users/ericshort/AIProjects/TradingApp/trading-data/src/main/java/com/tradingapp/data/LargeCapWatchList.java with a public static final List<String> SYMBOLS containing these 50 tickers: AAPL, MSFT, GOOGL, AMZN, META, NVDA, BRK-B, TSLA, UNH, XOM, JPM, JNJ, V, PG, MA, HD, CVX, MRK, LLY, ABBV, PEP, KO, AVGO, COST, WMT, BAC, DIS, CSCO, TMO, ACN, ABT, MCD, NKE, DHR, ADBE, CRM, TXN, NEE, PM, RTX, QCOM, HON, LOW, UPS, IBM, LIN, AMGN, SBUX, MDLZ, CAT.
loop: false
max_iterations: 1
verify:
  - type: artifact
    path: trading-data/src/main/java/com/tradingapp/data/QuoteModel.java
    assert:
      kind: exists
  - type: artifact
    path: trading-data/src/main/java/com/tradingapp/data/LargeCapWatchList.java
    assert:
      kind: exists

- [ ] **Step 5: Create YahooFinanceClient**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-data/src/main/java/com/tradingapp/data/YahooFinanceClient.java. It should: (1) expose a method QuoteModel getQuote(String symbol) that calls YahooFinance.get(symbol) from the yahoo-finance-api library and returns a QuoteModel; (2) expose List<QuoteModel> getQuotes(List<String> symbols) that batches up to 10 symbols per call using YahooFinance.get(String[] symbols); (3) implement exponential-backoff retry: on IOException or NullPointerException from the Yahoo library, wait 500ms then 1000ms then 2000ms before throwing DataUnavailableException (create this unchecked exception in the same package); (4) mark a quote as stale (QuoteModel.stale=true) if the returned price timestamp is more than 5 minutes behind System.currentTimeMillis(). Do not add any logging framework — use System.err.println for error output in this phase.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-data/src/main/java/com/tradingapp/data/YahooFinanceClient.java
  assert:
    kind: exists

- [ ] **Step 6: Write and run YahooFinanceClient integration tests**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-data/src/test/java/com/tradingapp/data/YahooFinanceClientTest.java. Write JUnit 5 tests: (1) testGetQuoteReturnsValidPrice — calls getQuote("AAPL") and asserts price > 0, symbol equals "AAPL", stale is false; (2) testGetQuotesReturnsFiveSymbols — calls getQuotes(List.of("AAPL","MSFT","GOOGL","AMZN","META")) and asserts all 5 results are returned with price > 0; (3) testStaleFlagSetOnOldTimestamp — use a mock/subclass to return a quote with timestamp = System.currentTimeMillis() - 600000 (10 minutes ago) and assert stale=true. These are network-dependent tests; annotate with @Tag("integration") and run them. Fix any compilation or assertion errors before moving on.
loop: until mvn test -pl trading-data exits 0
max_iterations: 3
verify: mvn test -pl trading-data

- [ ] **Step 7: Create Account, Position, and TransactionRecord models**
action: In /Users/ericshort/AIProjects/TradingApp/trading-account/src/main/java/com/tradingapp/account/, create three classes: (1) Position.java — fields: String symbol, int quantity, double averageCost, double currentPrice, PositionType type (enum STOCK or OPTION defined in same file); methods: double getMarketValue(), double getUnrealizedPnL(); (2) TransactionRecord.java — fields: long id (auto-assigned), long timestamp, String symbol, TransactionAction action (enum BUY or SELL defined in same file), int quantity, double pricePerUnit, double feeCharged, double balanceAfter, String reason, String signals; no-arg constructor plus builder-style setters; (3) Account.java — fields: double balance (init 100000.0), Map<String,Position> positions (keyed by symbol), double totalRealizedPnL, boolean tradingHalted; methods: double getBalance(), Map<String,Position> getPositions(), double getTotalUnrealizedPnL() (sum across positions), boolean isTradingHalted(), void updatePositionPrice(String symbol, double currentPrice).
loop: false
max_iterations: 1
verify:
  - type: artifact
    path: trading-account/src/main/java/com/tradingapp/account/Account.java
    assert:
      kind: exists
  - type: artifact
    path: trading-account/src/main/java/com/tradingapp/account/Position.java
    assert:
      kind: exists
  - type: artifact
    path: trading-account/src/main/java/com/tradingapp/account/TransactionRecord.java
    assert:
      kind: exists

- [ ] **Step 8: Create SafetyStop**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-account/src/main/java/com/tradingapp/account/SafetyStop.java. It holds a reference to an Account and exposes: boolean check() — returns true if account.getBalance() < 100.0, and if so sets account.tradingHalted = true and prints "SAFETY STOP: account balance $%.2f is below $100 threshold. All trading halted." to System.out. Also expose boolean isHalted() that returns account.isTradingHalted(). The check() call is idempotent — once halted, always returns true without re-printing.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-account/src/main/java/com/tradingapp/account/SafetyStop.java
  assert:
    kind: exists

- [ ] **Step 9: Create TransactionLog with SQLite persistence**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-account/src/main/java/com/tradingapp/account/TransactionLog.java. It should: (1) on construction, open (or create) a SQLite database at ${user.home}/.tradingapp/transactions.db using the sqlite-jdbc driver (Class.forName("org.sqlite.JDBC"), DriverManager.getConnection("jdbc:sqlite:...")); create the directory if absent; (2) on first open, execute CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER, symbol TEXT, action TEXT, quantity INTEGER, price_per_unit REAL, fee_charged REAL, balance_after REAL, reason TEXT, signals TEXT); (3) expose void insert(TransactionRecord r) that inserts one row; (4) expose List<TransactionRecord> findAll() that queries all rows ORDER BY timestamp DESC; (5) expose int countWins() that counts rows where balance_after > LAG(balance_after) — simplify: count rows where (price_per_unit * quantity - fee_charged) > 0 for BUY, or use a simpler heuristic: count SELL rows where price_per_unit > (SELECT price_per_unit FROM transactions t2 WHERE t2.symbol=transactions.symbol AND t2.action='BUY' ORDER BY t2.timestamp DESC LIMIT 1); for Phase 1 simplify countWins() to return COUNT(*) WHERE action='SELL' AND (price_per_unit * quantity) > fee_charged as a placeholder — exact P&L tracking improves in Phase 2; (6) expose int countLosses() similarly. Wrap all JDBC calls in try-catch and rethrow as RuntimeException.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-account/src/main/java/com/tradingapp/account/TransactionLog.java
  assert:
    kind: exists

- [ ] **Step 10: Write and run Account and TransactionLog unit tests**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-account/src/test/java/com/tradingapp/account/AccountTest.java with JUnit 5 tests: (1) testStartingBalance — new Account() has getBalance() == 100000.0; (2) testTradingNotHaltedAtStart — isTradingHalted() is false; (3) testSafetyStopTriggersBelow100 — create Account, set balance field to 99.0 via reflection or a package-private setter, call new SafetyStop(account).check(), assert isHalted() == true; (4) testSafetyStopDoesNotTriggerAt100 — balance=100.0, check() returns false. Create /Users/ericshort/AIProjects/TradingApp/trading-account/src/test/java/com/tradingapp/account/TransactionLogTest.java with tests: (1) testInsertAndRetrieve — insert one TransactionRecord, call findAll(), assert size==1 and fields match; (2) testPersistsAcrossInstances — insert via one TransactionLog instance, create a second instance pointing to same DB file, call findAll(), assert record is present. Use a temp DB path (Files.createTempFile) to avoid polluting the user home dir in tests. Run mvn test -pl trading-account and fix failures.
loop: until mvn test -pl trading-account exits 0
max_iterations: 3
verify: mvn test -pl trading-account

- [ ] **Step 11: Create JavaFX App entry point and dashboard FXML**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-ui/src/main/java/com/tradingapp/ui/TradingApp.java extending javafx.application.Application. In start(Stage primaryStage): load FXMLLoader from /com/tradingapp/ui/dashboard.fxml on the classpath, set the Scene on primaryStage with width=1200 height=800, set title "TradingApp — Paper Trading Simulator", call primaryStage.show(). Add a main method that calls launch(args). Create /Users/ericshort/AIProjects/TradingApp/trading-ui/src/main/resources/com/tradingapp/ui/dashboard.fxml as a JavaFX BorderPane layout: top region = HBox with Label id="balanceLabel" text="Balance: $100,000.00" style="-fx-font-size:20px;-fx-font-weight:bold;-fx-padding:10", and Label id="haltedLabel" text="" style="-fx-text-fill:red"; left region = VBox prefWidth="300" with Label text="Research" style="-fx-font-size:14px;-fx-font-weight:bold;-fx-padding:5" and TextArea id="researchArea" editable="false" VBox.vgrow="ALWAYS"; center region = VBox with Label text="Trade History" style="-fx-font-size:14px;-fx-font-weight:bold;-fx-padding:5" and TableView id="tradeHistoryTable" VBox.vgrow="ALWAYS"; right region = VBox prefWidth="200" with Label text="Stats" style="-fx-font-size:14px;-fx-font-weight:bold;-fx-padding:5", Label id="winsLabel" text="Wins: 0", Label id="lossesLabel" text="Losses: 0", Label id="pnlLabel" text="P&L: $0.00". Set fx:controller="com.tradingapp.ui.DashboardController" on the root BorderPane.
loop: false
max_iterations: 1
verify:
  - type: artifact
    path: trading-ui/src/main/java/com/tradingapp/ui/TradingApp.java
    assert:
      kind: exists
  - type: artifact
    path: trading-ui/src/main/resources/com/tradingapp/ui/dashboard.fxml
    assert:
      kind: exists

- [ ] **Step 12: Create DashboardController**
action: Create /Users/ericshort/AIProjects/TradingApp/trading-ui/src/main/java/com/tradingapp/ui/DashboardController.java implementing javafx.fxml.Initializable. Add @FXML fields matching the dashboard.fxml ids: Label balanceLabel, Label haltedLabel, TextArea researchArea, TableView<TransactionRecord> tradeHistoryTable, Label winsLabel, Label lossesLabel, Label pnlLabel. In initialize(): (1) Create a new Account (balance=$100,000) and a new TransactionLog; (2) Set balanceLabel text to String.format("Balance: $%,.2f", account.getBalance()); (3) Populate tradeHistoryTable with 3 TableColumns: Timestamp (formatted as HH:mm:ss), Symbol, and Action from TransactionRecord fields; (4) Load existing transactions via transactionLog.findAll() into an ObservableList and set as the table's items; (5) Set winsLabel to "Wins: " + transactionLog.countWins() and lossesLabel to "Losses: " + transactionLog.countLosses(); (6) Set researchArea text to "Waiting for market data...". Import all necessary JavaFX and project classes. The controller does not start any background threads in Phase 1 — that comes in Phase 2.
loop: false
max_iterations: 1
verify:
  type: artifact
  path: trading-ui/src/main/java/com/tradingapp/ui/DashboardController.java
  assert:
    kind: exists

- [ ] **Step 13: Compile trading-ui module**
action: From /Users/ericshort/AIProjects/TradingApp run mvn compile -pl trading-ui --also-make. Fix any JavaFX import errors (ensure javafx.controls and javafx.fxml are on the module path via the maven-compiler-plugin compilerArgs --add-modules if needed), missing @FXML annotations, or FXML binding mismatches. The goal is a clean compile of the UI module with all 5 modules.
loop: until mvn compile -pl trading-ui --also-make exits 0
max_iterations: 3
verify: mvn compile -pl trading-ui --also-make

- [ ] **Step 14: Add placeholder engine and AI stubs**
action: Create stub classes so the full build succeeds without engine or AI logic: (1) /Users/ericshort/AIProjects/TradingApp/trading-engine/src/main/java/com/tradingapp/engine/OrderExecutor.java — public class with a single method void execute(String symbol, String action, int quantity) that prints "STUB: order not implemented" to System.out; (2) /Users/ericshort/AIProjects/TradingApp/trading-ai/src/main/java/com/tradingapp/ai/WekaModelTrainer.java — public class with a single method void train() that prints "STUB: training not implemented". These stubs will be replaced in Phases 2 and 4.
loop: false
max_iterations: 1
verify:
  - type: artifact
    path: trading-engine/src/main/java/com/tradingapp/engine/OrderExecutor.java
    assert:
      kind: exists
  - type: artifact
    path: trading-ai/src/main/java/com/tradingapp/ai/WekaModelTrainer.java
    assert:
      kind: exists

- [ ] **Step 15: Full multi-module build and test**
action: From /Users/ericshort/AIProjects/TradingApp run mvn clean test. All 5 modules must compile and all tests must pass. Fix any remaining issues: dependency resolution failures (check Maven Central availability), test failures in AccountTest or TransactionLogTest, or compile errors in any module. Do not skip tests.
loop: until mvn clean test exits 0
max_iterations: 3
verify: mvn clean test

- [ ] **Step 16: Smoke-test app launch**
action: From /Users/ericshort/AIProjects/TradingApp run mvn -pl trading-ui javafx:run. Confirm the JavaFX window opens with title "TradingApp — Paper Trading Simulator", the balance label shows "Balance: $100,000.00", the trade history table is empty, wins and losses both show 0, and the research area shows "Waiting for market data...". If the window opens correctly, close it. Fix any JavaFX runtime errors (module path, missing FXML controller binding) before proceeding.
loop: false
max_iterations: 1
verify:
  type: human-review
  prompt: Confirm the JavaFX window opened with title "TradingApp — Paper Trading Simulator", balance label shows "Balance: $100,000.00", all 4 panels are visible (balance bar, research area, trade history table, stats pane), and no exceptions appear in console output.

- [ ] **Step 17: Phase 1 acceptance gate**
action: Run mvn test -pl trading-data and confirm YahooFinanceClientTest.testGetQuotesReturnsFiveSymbols passes (quotes returned for AAPL, MSFT, GOOGL, AMZN, META with price > 0). Run mvn test -pl trading-account and confirm SafetyStop halts at <$100 and TransactionLog persistence test passes. Record test output.
loop: false
max_iterations: 1
gate: human
verify: mvn clean test
