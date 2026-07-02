package com.tradingapp.broker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class AppConfig {

    public enum BrokerType { SIMULATED, ALPACA_PAPER, ALPACA_LIVE }
    public enum QuoteProviderType { YAHOO, ALPACA, ALPACA_WEBSOCKET_FREE }

    public static final String APP_PROFILE = "day-trader";

    public static Path getDataDir() {
        return Path.of(System.getProperty("user.home"), ".tradingapp", APP_PROFILE);
    }

    private static final Path CONFIG_PATH = getDataDir().resolve("app.properties");

    private BrokerType brokerType = BrokerType.SIMULATED;
    private String alpacaApiKey = "";
    private String alpacaApiSecret = "";
    private String claudeApiKey = "";
    private QuoteProviderType quoteProviderType = QuoteProviderType.YAHOO;
    private double dailyLossLimitPct = 5.0;
    private double maxPortfolioExposurePct = 60.0;
    private boolean avoidOvernightHolds = true;
    private boolean marketRegimeFilterEnabled = true;
    private int earningsBlackoutDays = 3;
    // Strategies enabled for live trading. Defaults to day-trading set.
    private Set<String> enabledStrategies = new LinkedHashSet<>(
            Arrays.asList("HIGH_DELTA_SCALP", "MOMENTUM_NEAR_TERM", "LONG_CALL", "LONG_PUT", "ZERO_DTE"));
    // If non-empty, only these symbols may trade options. Empty = all symbols allowed.
    private Set<String> optionsSymbolAllowlist = new LinkedHashSet<>();
    // Symbols in this set may trade puts but not calls.
    private Set<String> optionsCallsDisabled   = new LinkedHashSet<>();
    // Symbols in this set may trade calls but not puts.
    private Set<String> optionsPutsDisabled    = new LinkedHashSet<>();
    // Minimum sell signals required to open a put during a confirmed SPY downtrend.
    private int downtrendPutMinSignals = 4;
    // Minimum opposing signals required on each of reversalMinConsecutive ticks to exit on reversal.
    private int reversalMinSignals = 5;
    // Close a winning options position when premium reaches this multiple of entry (e.g. 2.5 = 150% gain).
    private double profitTarget = 2.5;
    private boolean premiumSellerEnabled = false;
    private Set<String> premiumEnabledStrategies = new LinkedHashSet<>();
    private int premiumSellerMaxContracts = 15;
    // Block new premium entries before this ET time (null = no delay; 09:45 avoids open slippage).
    private LocalTime premiumMinEntryTime = null;
    // Max concurrent open PCS+CCS positions (0 = unlimited).
    private int premiumMaxConcurrentSpreads = 0;
    // Max positions per sector (0 = unlimited).
    private int premiumSectorConcentrationLimit = 0;
    // Require non-negative MACD before opening a PCS.
    private boolean premiumPcsRequireNonNegMacd = false;
    // Require at least one SELL signal before opening a CCS.
    private boolean premiumCcsRequireSellSignal = false;
    // Use nearest monthly expiry for all premium spreads (lower DTE, faster theta).
    private boolean premiumUseShortExpiry = false;
    // When false, equity (stock) buys are disabled; only options trades execute.
    private boolean stockTradingEnabled = true;
    // Fraction of entry premium at which an options position is stop-lossed (default 0.50 = 50%).
    private double optionsStopLossFrac = 0.50;
    // No new options entries after this ET time (null = no cutoff).
    private LocalTime optionsEntryCutoff = null;
    // No new options entries before this ET time (null = no delay).
    private LocalTime optionsEntryStartTime = null;
    // EOD force-close time for options positions (null = use default 15:55).
    private LocalTime optionsForceCloseTime = null;
    // Per-trade position sizing: budget fraction of account balance (standard tier, default 0.05).
    private double positionBudgetFrac   = 0.05;
    // Max contracts per trade for standard-tier strategies (default 5).
    private int    maxContractsPerTrade = 5;
    // Bars the portfolio must stay below the daily loss threshold before the halt fires (0 = immediate).
    // In live 5-second tick mode, 12 bars ≈ 1 minute — guards against momentary bad ticks.
    private int lossLimitRecoveryBars = 0;
    // Consecutive same-direction ticks required before opening a new options position (default 1 = no filter).
    private int entryConfirmationTicks = 1;
    // When avoidOvernightHolds=false, close EOD positions below this fraction of entry premium (0.0 = hold all).
    private double overnightMinPremiumFrac = 0.8;
    private boolean optionsTradingEnabled = true;
    private double trailingStopPct = 0.02;
    private double maxLossPerTradePct = 0.005;
    private double circuitBreakerPct = 0.02;
    // IV surge guard: block options entry when recent vol > N× long-term vol (default 1.5×).
    private double ivSurgeThreshold = 1.5;
    private List<String> stockWatchlist = new ArrayList<>(Arrays.asList(
        "AAPL", "MSFT", "AMZN", "META", "NVDA",
        "XOM", "JNJ", "PG", "CVX", "LLY",
        "ABBV", "PEP", "WMT", "CSCO", "TMO",
        "ADBE", "TXN", "NEE", "PM", "QCOM",
        "LIN", "MDLZ", "CAT"
    ));
    private List<String> optionsWatchlist = new ArrayList<>(Arrays.asList(
        "SPY", "NOC", "NVDA", "MSFT", "COST",
        "VRTX", "AMGN", "CRWD", "GS", "PLTR",
        "LRCX", "DE", "ORCL", "LLY", "BLK",
        "NOW", "MA", "REGN", "META", "AMAT",
        "KLAC", "CAT", "UNH", "LMT", "JPM",
        "MU", "HD", "MCD", "V", "AVGO"
    ));

    public static AppConfig load() {
        AppConfig config = new AppConfig();
        if (!Files.exists(CONFIG_PATH)) {
            config.save(); // persist defaults so settings survive restarts
            return config;
        }
        try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
            Properties props = new Properties();
            props.load(in);
            try {
                config.brokerType = BrokerType.valueOf(props.getProperty("broker.type", "SIMULATED"));
            } catch (IllegalArgumentException ignored) {}
            config.alpacaApiKey = props.getProperty("broker.alpaca.api_key", "");
            config.alpacaApiSecret = props.getProperty("broker.alpaca.api_secret", "");
            config.claudeApiKey = props.getProperty("ai.claude.api_key", "");
            try {
                config.quoteProviderType = QuoteProviderType.valueOf(props.getProperty("quote.provider", "YAHOO"));
            } catch (IllegalArgumentException ignored) {}
            try {
                config.dailyLossLimitPct = Double.parseDouble(
                        props.getProperty("risk.daily_loss_limit_pct", "5.0"));
            } catch (NumberFormatException ignored) {}
            try {
                config.maxPortfolioExposurePct = Double.parseDouble(
                        props.getProperty("risk.max_portfolio_exposure_pct", "60.0"));
            } catch (NumberFormatException ignored) {}
            config.avoidOvernightHolds = Boolean.parseBoolean(
                    props.getProperty("risk.avoid_overnight_holds", "true"));
            config.marketRegimeFilterEnabled = Boolean.parseBoolean(
                    props.getProperty("risk.market_regime_filter", "true"));
            try {
                config.earningsBlackoutDays = Integer.parseInt(
                        props.getProperty("risk.earnings_blackout_days", "3"));
            } catch (NumberFormatException ignored) {}
            String strategiesRaw = props.getProperty("strategy.enabled", "");
            if (!strategiesRaw.isBlank()) {
                config.enabledStrategies = Arrays.stream(strategiesRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            config.premiumSellerEnabled = Boolean.parseBoolean(
                    props.getProperty("premium.seller.enabled", "false"));
            String premiumStratsRaw = props.getProperty("premium.strategies", "");
            if (!premiumStratsRaw.isBlank()) {
                config.premiumEnabledStrategies = Arrays.stream(premiumStratsRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            config.premiumSellerMaxContracts = Integer.parseInt(
                    props.getProperty("premium.seller.max_contracts", "15"));
            String premMinEntryRaw = props.getProperty("premium.min_entry_time", "");
            if (!premMinEntryRaw.isBlank()) {
                try { config.premiumMinEntryTime = LocalTime.parse(premMinEntryRaw); }
                catch (DateTimeParseException ignored) {}
            }
            try {
                config.premiumMaxConcurrentSpreads = Integer.parseInt(
                        props.getProperty("premium.max_concurrent_spreads", "0"));
            } catch (NumberFormatException ignored) {}
            try {
                config.premiumSectorConcentrationLimit = Integer.parseInt(
                        props.getProperty("premium.sector_concentration_limit", "0"));
            } catch (NumberFormatException ignored) {}
            config.premiumPcsRequireNonNegMacd = Boolean.parseBoolean(
                    props.getProperty("premium.pcs_require_nonneg_macd", "false"));
            config.premiumCcsRequireSellSignal = Boolean.parseBoolean(
                    props.getProperty("premium.ccs_require_sell_signal", "false"));
            config.premiumUseShortExpiry = Boolean.parseBoolean(
                    props.getProperty("premium.use_short_expiry", "false"));
            String allowlistRaw = props.getProperty("options.symbol.allowlist", "");
            if (!allowlistRaw.isBlank()) {
                config.optionsSymbolAllowlist = Arrays.stream(allowlistRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            String callsDisabledRaw = props.getProperty("options.calls.disabled", "");
            if (!callsDisabledRaw.isBlank()) {
                config.optionsCallsDisabled = Arrays.stream(callsDisabledRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            String putsDisabledRaw = props.getProperty("options.puts.disabled", "");
            if (!putsDisabledRaw.isBlank()) {
                config.optionsPutsDisabled = Arrays.stream(putsDisabledRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
            try {
                config.downtrendPutMinSignals = Integer.parseInt(
                        props.getProperty("options.downtrend_put_min_signals", "4"));
            } catch (NumberFormatException ignored) {}
            try {
                config.reversalMinSignals = Integer.parseInt(
                        props.getProperty("options.reversal_min_signals", "5"));
            } catch (NumberFormatException ignored) {}
            try {
                config.profitTarget = Double.parseDouble(
                        props.getProperty("options.profit_target", "2.0"));
            } catch (NumberFormatException ignored) {}
            config.stockTradingEnabled = Boolean.parseBoolean(
                    props.getProperty("stock.trading.enabled", "true"));
            try {
                config.optionsStopLossFrac = Double.parseDouble(
                        props.getProperty("options.stop_loss_frac", "0.50"));
            } catch (NumberFormatException ignored) {}
            String cutoffRaw = props.getProperty("options.entry_cutoff", "");
            if (!cutoffRaw.isBlank()) {
                try { config.optionsEntryCutoff = LocalTime.parse(cutoffRaw); }
                catch (DateTimeParseException ignored) {}
            }
            String startTimeRaw = props.getProperty("options.entry_start_time", "");
            if (!startTimeRaw.isBlank()) {
                try { config.optionsEntryStartTime = LocalTime.parse(startTimeRaw); }
                catch (DateTimeParseException ignored) {}
            }
            String forceCloseRaw = props.getProperty("options.force_close_time", "");
            if (!forceCloseRaw.isBlank()) {
                try { config.optionsForceCloseTime = LocalTime.parse(forceCloseRaw); }
                catch (DateTimeParseException ignored) {}
            }
            try {
                config.positionBudgetFrac = Double.parseDouble(
                        props.getProperty("options.position_budget_frac", "0.05"));
            } catch (NumberFormatException ignored) {}
            try {
                config.maxContractsPerTrade = Integer.parseInt(
                        props.getProperty("options.max_contracts_per_trade", "5"));
            } catch (NumberFormatException ignored) {}
            try {
                config.lossLimitRecoveryBars = Integer.parseInt(
                        props.getProperty("risk.loss_limit_recovery_bars", "0"));
            } catch (NumberFormatException ignored) {}
            try {
                config.entryConfirmationTicks = Integer.parseInt(
                        props.getProperty("options.entry_confirmation_ticks", "1"));
            } catch (NumberFormatException ignored) {}
            try {
                config.overnightMinPremiumFrac = Double.parseDouble(
                        props.getProperty("options.overnight_min_premium_frac", "0.0"));
            } catch (NumberFormatException ignored) {}
            config.optionsTradingEnabled = Boolean.parseBoolean(
                    props.getProperty("options.trading.enabled", "true"));
            try {
                config.trailingStopPct = Double.parseDouble(
                        props.getProperty("risk.trailing_stop_pct", "0.04"));
            } catch (NumberFormatException ignored) {}
            try {
                config.maxLossPerTradePct = Double.parseDouble(
                        props.getProperty("risk.max_loss_per_trade_pct", "0.003"));
            } catch (NumberFormatException ignored) {}
            try {
                config.circuitBreakerPct = Double.parseDouble(
                        props.getProperty("risk.circuit_breaker_pct", "0.02"));
            } catch (NumberFormatException ignored) {}
            try {
                config.ivSurgeThreshold = Double.parseDouble(
                        props.getProperty("options.iv_surge_threshold", "1.5"));
            } catch (NumberFormatException ignored) {}
            String stockWatchlistRaw = props.getProperty("stock.watchlist", "");
            if (!stockWatchlistRaw.isBlank()) {
                config.stockWatchlist = Arrays.stream(stockWatchlistRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(ArrayList::new));
            }
            String optionsWatchlistRaw = props.getProperty("options.watchlist", "");
            if (!optionsWatchlistRaw.isBlank()) {
                config.optionsWatchlist = Arrays.stream(optionsWatchlistRaw.split(","))
                        .map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        } catch (IOException ignored) {}
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Properties props = new Properties();
            props.setProperty("broker.type", brokerType.name());
            props.setProperty("broker.alpaca.api_key", alpacaApiKey);
            props.setProperty("broker.alpaca.api_secret", alpacaApiSecret);
            props.setProperty("ai.claude.api_key", claudeApiKey);
            props.setProperty("quote.provider", quoteProviderType.name());
            props.setProperty("risk.daily_loss_limit_pct", String.valueOf(dailyLossLimitPct));
            props.setProperty("risk.max_portfolio_exposure_pct", String.valueOf(maxPortfolioExposurePct));
            props.setProperty("risk.avoid_overnight_holds", String.valueOf(avoidOvernightHolds));
            props.setProperty("risk.market_regime_filter", String.valueOf(marketRegimeFilterEnabled));
            props.setProperty("risk.earnings_blackout_days", String.valueOf(earningsBlackoutDays));
            props.setProperty("strategy.enabled", String.join(",", enabledStrategies));
            props.setProperty("premium.seller.enabled", String.valueOf(premiumSellerEnabled));
            props.setProperty("premium.strategies", String.join(",", premiumEnabledStrategies));
            props.setProperty("premium.seller.max_contracts", String.valueOf(premiumSellerMaxContracts));
            props.setProperty("premium.min_entry_time", premiumMinEntryTime != null ? premiumMinEntryTime.toString() : "");
            props.setProperty("premium.max_concurrent_spreads", String.valueOf(premiumMaxConcurrentSpreads));
            props.setProperty("premium.sector_concentration_limit", String.valueOf(premiumSectorConcentrationLimit));
            props.setProperty("premium.pcs_require_nonneg_macd", String.valueOf(premiumPcsRequireNonNegMacd));
            props.setProperty("premium.ccs_require_sell_signal", String.valueOf(premiumCcsRequireSellSignal));
            props.setProperty("premium.use_short_expiry", String.valueOf(premiumUseShortExpiry));
            props.setProperty("options.symbol.allowlist", String.join(",", optionsSymbolAllowlist));
            props.setProperty("options.calls.disabled", String.join(",", optionsCallsDisabled));
            props.setProperty("options.puts.disabled",  String.join(",", optionsPutsDisabled));
            props.setProperty("options.downtrend_put_min_signals", String.valueOf(downtrendPutMinSignals));
            props.setProperty("options.reversal_min_signals", String.valueOf(reversalMinSignals));
            props.setProperty("options.profit_target", String.valueOf(profitTarget));
            props.setProperty("stock.trading.enabled", String.valueOf(stockTradingEnabled));
            props.setProperty("options.stop_loss_frac", String.valueOf(optionsStopLossFrac));
            props.setProperty("options.entry_cutoff", optionsEntryCutoff != null ? optionsEntryCutoff.toString() : "");
            props.setProperty("options.entry_start_time", optionsEntryStartTime != null ? optionsEntryStartTime.toString() : "");
            props.setProperty("options.force_close_time", optionsForceCloseTime != null ? optionsForceCloseTime.toString() : "");
            props.setProperty("options.position_budget_frac", String.valueOf(positionBudgetFrac));
            props.setProperty("options.max_contracts_per_trade", String.valueOf(maxContractsPerTrade));
            props.setProperty("risk.loss_limit_recovery_bars", String.valueOf(lossLimitRecoveryBars));
            props.setProperty("options.entry_confirmation_ticks", String.valueOf(entryConfirmationTicks));
            props.setProperty("options.overnight_min_premium_frac", String.valueOf(overnightMinPremiumFrac));
            props.setProperty("options.trading.enabled", String.valueOf(optionsTradingEnabled));
            props.setProperty("risk.trailing_stop_pct", String.valueOf(trailingStopPct));
            props.setProperty("risk.max_loss_per_trade_pct", String.valueOf(maxLossPerTradePct));
            props.setProperty("risk.circuit_breaker_pct", String.valueOf(circuitBreakerPct));
            props.setProperty("options.iv_surge_threshold", String.valueOf(ivSurgeThreshold));
            props.setProperty("stock.watchlist", String.join(",", stockWatchlist));
            props.setProperty("options.watchlist", String.join(",", optionsWatchlist));
            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out, "Trading App Configuration — do not commit this file");
            }
            appendConfigHistory();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save app config", e);
        }
    }

    private void appendConfigHistory() {
        try {
            Path histPath = CONFIG_PATH.getParent().resolve("config-history.tsv");
            boolean isNew = !Files.exists(histPath);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(histPath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
                if (isNew) {
                    w.println("timestamp\tstrategies\tstop_loss\tentry_cutoff\tdaily_loss_limit\tmax_exposure\tallowlist_count\tdowntrend_put_min");
                }
                String ts = ZonedDateTime.now(ZoneId.of("America/New_York"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                w.printf("%s\t%s\t%.2f\t%s\t%.1f\t%.1f\t%d\t%d%n",
                        ts,
                        String.join("|", enabledStrategies),
                        optionsStopLossFrac,
                        optionsEntryCutoff != null ? optionsEntryCutoff.toString() : "",
                        dailyLossLimitPct,
                        maxPortfolioExposurePct,
                        optionsSymbolAllowlist.size(),
                        downtrendPutMinSignals);
            }
        } catch (Exception ignored) {}
    }

    public BrokerType getBrokerType() { return brokerType; }
    public void setBrokerType(BrokerType brokerType) { this.brokerType = brokerType; }

    public String getAlpacaApiKey() { return alpacaApiKey; }
    public void setAlpacaApiKey(String key) { this.alpacaApiKey = key; }

    public String getAlpacaApiSecret() { return alpacaApiSecret; }
    public void setAlpacaApiSecret(String secret) { this.alpacaApiSecret = secret; }

    public QuoteProviderType getQuoteProviderType() { return quoteProviderType; }
    public void setQuoteProviderType(QuoteProviderType type) { this.quoteProviderType = type; }

    public double getDailyLossLimitPct() { return dailyLossLimitPct; }
    public void setDailyLossLimitPct(double pct) { this.dailyLossLimitPct = pct; }

    public double getMaxPortfolioExposurePct() { return maxPortfolioExposurePct; }
    public void setMaxPortfolioExposurePct(double pct) { this.maxPortfolioExposurePct = pct; }

    public boolean isAvoidOvernightHolds() { return avoidOvernightHolds; }
    public void setAvoidOvernightHolds(boolean v) { this.avoidOvernightHolds = v; }

    public boolean isMarketRegimeFilterEnabled() { return marketRegimeFilterEnabled; }
    public void setMarketRegimeFilterEnabled(boolean v) { this.marketRegimeFilterEnabled = v; }

    public int getEarningsBlackoutDays() { return earningsBlackoutDays; }
    public void setEarningsBlackoutDays(int days) { this.earningsBlackoutDays = days; }

    public Set<String> getEnabledStrategies() { return enabledStrategies; }
    public void setEnabledStrategies(Set<String> strategies) { this.enabledStrategies = new LinkedHashSet<>(strategies); }
    public boolean isStrategyEnabled(String name) { return enabledStrategies.contains(name); }
    public boolean isPremiumSellerEnabled() { return premiumSellerEnabled; }
    public void setPremiumSellerEnabled(boolean v) { this.premiumSellerEnabled = v; }
    public Set<String> getPremiumEnabledStrategies() { return premiumEnabledStrategies; }
    public void setPremiumEnabledStrategies(Set<String> s) { this.premiumEnabledStrategies = new LinkedHashSet<>(s); }
    public int getPremiumSellerMaxContracts() { return premiumSellerMaxContracts; }
    public void setPremiumSellerMaxContracts(int v) { this.premiumSellerMaxContracts = Math.max(1, v); }
    public LocalTime getPremiumMinEntryTime() { return premiumMinEntryTime; }
    public void setPremiumMinEntryTime(LocalTime t) { this.premiumMinEntryTime = t; }
    public int getPremiumMaxConcurrentSpreads() { return premiumMaxConcurrentSpreads; }
    public void setPremiumMaxConcurrentSpreads(int v) { this.premiumMaxConcurrentSpreads = v; }
    public int getPremiumSectorConcentrationLimit() { return premiumSectorConcentrationLimit; }
    public void setPremiumSectorConcentrationLimit(int v) { this.premiumSectorConcentrationLimit = v; }
    public boolean isPremiumPcsRequireNonNegMacd() { return premiumPcsRequireNonNegMacd; }
    public void setPremiumPcsRequireNonNegMacd(boolean v) { this.premiumPcsRequireNonNegMacd = v; }
    public boolean isPremiumCcsRequireSellSignal() { return premiumCcsRequireSellSignal; }
    public void setPremiumCcsRequireSellSignal(boolean v) { this.premiumCcsRequireSellSignal = v; }
    public boolean isPremiumUseShortExpiry() { return premiumUseShortExpiry; }
    public void setPremiumUseShortExpiry(boolean v) { this.premiumUseShortExpiry = v; }
    public int getDowntrendPutMinSignals() { return downtrendPutMinSignals; }
    public void setDowntrendPutMinSignals(int n) { this.downtrendPutMinSignals = n; }

    public int getReversalMinSignals() { return reversalMinSignals; }
    public void setReversalMinSignals(int n) { this.reversalMinSignals = n; }

    public double getProfitTarget() { return profitTarget; }
    public void setProfitTarget(double multiple) { this.profitTarget = multiple; }

    public boolean isStockTradingEnabled() { return stockTradingEnabled; }
    public void setStockTradingEnabled(boolean v) { this.stockTradingEnabled = v; }

    public double getOptionsStopLossFrac() { return optionsStopLossFrac; }
    public void setOptionsStopLossFrac(double frac) { this.optionsStopLossFrac = frac; }

    public String getClaudeApiKey() { return claudeApiKey; }
    public void setClaudeApiKey(String key) { this.claudeApiKey = key; }

    public Set<String> getOptionsSymbolAllowlist() { return optionsSymbolAllowlist; }
    public void setOptionsSymbolAllowlist(Set<String> symbols) { this.optionsSymbolAllowlist = new LinkedHashSet<>(symbols); }

    public Set<String> getOptionsCallsDisabled() { return optionsCallsDisabled; }
    public void setOptionsCallsDisabled(Set<String> symbols) { this.optionsCallsDisabled = new LinkedHashSet<>(symbols); }

    public Set<String> getOptionsPutsDisabled() { return optionsPutsDisabled; }
    public void setOptionsPutsDisabled(Set<String> symbols) { this.optionsPutsDisabled = new LinkedHashSet<>(symbols); }

    public LocalTime getOptionsEntryCutoff() { return optionsEntryCutoff; }
    public void setOptionsEntryCutoff(LocalTime t) { this.optionsEntryCutoff = t; }

    public LocalTime getOptionsEntryStartTime() { return optionsEntryStartTime; }
    public void setOptionsEntryStartTime(LocalTime t) { this.optionsEntryStartTime = t; }
    public LocalTime getOptionsForceCloseTime() { return optionsForceCloseTime; }
    public void setOptionsForceCloseTime(LocalTime t) { this.optionsForceCloseTime = t; }
    public double getPositionBudgetFrac() { return positionBudgetFrac; }
    public void setPositionBudgetFrac(double v) { this.positionBudgetFrac = v; }
    public int getMaxContractsPerTrade() { return maxContractsPerTrade; }
    public void setMaxContractsPerTrade(int v) { this.maxContractsPerTrade = v; }
    public int getLossLimitRecoveryBars() { return lossLimitRecoveryBars; }
    public void setLossLimitRecoveryBars(int v) { this.lossLimitRecoveryBars = v; }

    public int getEntryConfirmationTicks() { return entryConfirmationTicks; }
    public void setEntryConfirmationTicks(int n) { this.entryConfirmationTicks = n; }

    public double getOvernightMinPremiumFrac() { return overnightMinPremiumFrac; }
    public double getIvSurgeThreshold() { return ivSurgeThreshold; }
    public void setIvSurgeThreshold(double v) { this.ivSurgeThreshold = v; }
    public void setOvernightMinPremiumFrac(double frac) { this.overnightMinPremiumFrac = frac; }

    public boolean isOptionsTradingEnabled() { return optionsTradingEnabled; }
    public void setOptionsTradingEnabled(boolean v) { this.optionsTradingEnabled = v; }

    public double getTrailingStopPct() { return trailingStopPct; }
    public void setTrailingStopPct(double pct) { this.trailingStopPct = pct; }

    public double getMaxLossPerTradePct() { return maxLossPerTradePct; }
    public void setMaxLossPerTradePct(double pct) { this.maxLossPerTradePct = pct; }

    public double getCircuitBreakerPct() { return circuitBreakerPct; }
    public void setCircuitBreakerPct(double pct) { this.circuitBreakerPct = pct; }

    public List<String> getStockWatchlist() { return stockWatchlist; }
    public void setStockWatchlist(List<String> symbols) { this.stockWatchlist = new ArrayList<>(symbols); }

    public List<String> getOptionsWatchlist() { return optionsWatchlist; }
    public void setOptionsWatchlist(List<String> symbols) { this.optionsWatchlist = new ArrayList<>(symbols); }

    public boolean isAlpacaBroker() {
        return brokerType == BrokerType.ALPACA_PAPER || brokerType == BrokerType.ALPACA_LIVE;
    }

    public String getAlpacaBaseUrl() {
        return brokerType == BrokerType.ALPACA_LIVE
                ? "https://api.alpaca.markets"
                : "https://paper-api.alpaca.markets/v2";
    }

    public String getAlpacaDataUrl() {
        return "https://data.alpaca.markets";
    }
}
