package com.tradingapp.broker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
            props.setProperty("options.symbol.allowlist", String.join(",", optionsSymbolAllowlist));
            props.setProperty("options.calls.disabled", String.join(",", optionsCallsDisabled));
            props.setProperty("options.puts.disabled",  String.join(",", optionsPutsDisabled));
            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out, "Trading App Configuration — do not commit this file");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save app config", e);
        }
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

    public String getClaudeApiKey() { return claudeApiKey; }
    public void setClaudeApiKey(String key) { this.claudeApiKey = key; }

    public Set<String> getOptionsSymbolAllowlist() { return optionsSymbolAllowlist; }
    public void setOptionsSymbolAllowlist(Set<String> symbols) { this.optionsSymbolAllowlist = new LinkedHashSet<>(symbols); }

    public Set<String> getOptionsCallsDisabled() { return optionsCallsDisabled; }
    public void setOptionsCallsDisabled(Set<String> symbols) { this.optionsCallsDisabled = new LinkedHashSet<>(symbols); }

    public Set<String> getOptionsPutsDisabled() { return optionsPutsDisabled; }
    public void setOptionsPutsDisabled(Set<String> symbols) { this.optionsPutsDisabled = new LinkedHashSet<>(symbols); }

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
