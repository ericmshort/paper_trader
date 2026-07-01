package com.tradingapp.options;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.function.Function;

public class BlackScholesEngine {

    // ── IV adjustment (backtest realism) ─────────────────────────────────────

    // Market open IV premium: options at 9:30am trade ~30% above theoretical BS value.
    // This premium is absorbed linearly over the first 60 minutes of trading.
    static final double OPEN_IV_MULT  = 1.30;
    static final int    DECAY_MINUTES = 60;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);

    private Function<LocalDate, Double> vixProvider = null;
    private double baselineVix = 18.0;
    private double vixScale    = 1.0;  // updated each day via setReferenceDate
    private LocalTime currentTime = null;

    /**
     * Injects a per-day VIX provider and long-term VIX average.
     * When set, sigma is scaled by vix_today / baseline each trading day.
     * No-op in live trading (provider is never set outside the backtest runner).
     */
    public void setVixProvider(Function<LocalDate, Double> provider, double baseline) {
        this.vixProvider = provider;
        this.baselineVix = baseline > 0 ? baseline : 18.0;
    }

    /** Current virtual time of day — used to compute the intraday IV decay factor. */
    public void setCurrentTime(LocalTime time) { this.currentTime = time; }

    /**
     * Effective sigma for pricing: raw historical vol × VIX scale × intraday open premium.
     * <p>
     * VIX scale: sigma is proportionally larger on high-VIX days and smaller on low-VIX days,
     * reflecting that option market-makers charge more when realized vol is expected to be higher.
     * Capped at [0.5×, 2.5×] to prevent extreme VIX spikes from producing nonsensical premiums.
     * <p>
     * Intraday decay: ATM options at the open carry a 30% IV premium above fair value as the
     * overnight gap risk is priced in. This premium decays to zero by 10:30am as order flow
     * establishes the true intraday range.
     */
    double adjustedSigma(double sigma) {
        double intraday = 1.0;
        if (currentTime != null && !currentTime.isBefore(MARKET_OPEN)) {
            long mins = ChronoUnit.MINUTES.between(MARKET_OPEN, currentTime);
            if (mins < DECAY_MINUTES) {
                intraday = OPEN_IV_MULT - (OPEN_IV_MULT - 1.0) * mins / (double) DECAY_MINUTES;
            }
        }
        return sigma * vixScale * intraday;
    }

    // ── Date / time ───────────────────────────────────────────────────────────

    private LocalDate referenceDate = null;

    /** Override the "today" used for expiry selection and time-to-expiry calculations.
     *  Set to the simulated date during backtesting; leave null for live trading. */
    public void setReferenceDate(LocalDate date) {
        this.referenceDate = date;
        if (vixProvider != null && baselineVix > 0) {
            double vix = vixProvider.apply(date);
            vixScale = Math.max(0.5, Math.min(2.5, vix / baselineVix));
        } else {
            vixScale = 1.0;
        }
    }

    private LocalDate today() { return referenceDate != null ? referenceDate : LocalDate.now(); }

    // Abramowitz & Stegun 26.2.17 — max error 7.5e-8
    private double normalCDF(double x) {
        if (x < -8.0) return 0.0;
        if (x >  8.0) return 1.0;
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530
                    + t * (-0.356563782
                    + t * (1.781477937
                    + t * (-1.821255978
                    + t * 1.330274429))));
        double pdf = Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
        double result = 1.0 - pdf * poly;
        return x >= 0 ? result : 1.0 - result;
    }

    private double normalPDF(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    public double historicalVol(List<Double> prices) {
        if (prices == null || prices.size() < 3) return 0.0;
        double[] logReturns = new double[prices.size() - 1];
        int count = 0;
        for (int i = 1; i < prices.size(); i++) {
            double prev = prices.get(i - 1);
            double curr = prices.get(i);
            if (prev <= 0 || curr <= 0 || !Double.isFinite(prev) || !Double.isFinite(curr)) continue;
            logReturns[count++] = Math.log(curr / prev);
        }
        if (count < 2) return 0.0;
        double mean = 0;
        for (int i = 0; i < count; i++) mean += logReturns[i];
        mean /= count;
        double variance = 0;
        for (int i = 0; i < count; i++) variance += (logReturns[i] - mean) * (logReturns[i] - mean);
        variance /= (count - 1);
        double vol = Math.sqrt(variance) * Math.sqrt(252);
        return (!Double.isFinite(vol) || vol < 0.001) ? 0.0 : vol;
    }

    public LocalDate nextMonthlyExpiry() {
        LocalDate today = today();
        LocalDate earliest = today.plusDays(14);
        // Check current month's third Friday, then advance month by month
        LocalDate candidate = thirdFriday(today.withDayOfMonth(1));
        while (candidate.isBefore(earliest)) {
            candidate = thirdFriday(candidate.plusMonths(1).withDayOfMonth(1));
        }
        return candidate;
    }

    /**
     * Selects an expiry that is at least 21 days away. When the next monthly expiry
     * qualifies, symbols are split deterministically across next and following month
     * so not all contracts expire on the same Friday. When the next expiry is within
     * 21 days, all symbols fall back to the following month to avoid short-dated theta decay.
     */
    public LocalDate selectExpiry(String symbol) {
        LocalDate next = nextMonthlyExpiry();
        LocalDate following = thirdFriday(next.plusMonths(1).withDayOfMonth(1));
        long daysToNext = ChronoUnit.DAYS.between(today(), next);
        if (daysToNext < 21) {
            return following; // too close — everyone shifts to the following month
        }
        // Split symbols across two expiry dates based on symbol hash
        return (Math.abs(symbol.hashCode()) % 2 == 0) ? next : following;
    }

    private LocalDate thirdFriday(LocalDate firstOfMonth) {
        // Find first Friday on or after the 1st, then add 2 weeks
        LocalDate firstFriday = firstOfMonth.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        return firstFriday.plusWeeks(2);
    }

    /**
     * Returns the nearest monthly expiry that is at least 21 days away. Unlike
     * {@link #selectExpiry(String)}, does not split symbols across two months — all
     * symbols use the same (nearest qualifying) expiry, keeping average DTE shorter.
     */
    public LocalDate selectNearestQualifyingExpiry() {
        LocalDate next = nextMonthlyExpiry();
        long daysToNext = ChronoUnit.DAYS.between(today(), next);
        if (daysToNext < 21) {
            return thirdFriday(next.plusMonths(1).withDayOfMonth(1));
        }
        return next;
    }

    /**
     * Picks the next Friday at least 7 days away for near-term/day-trading strategies.
     */
    public LocalDate selectNearTermExpiry() {
        LocalDate candidate = today().plusDays(7);
        while (candidate.getDayOfWeek() != DayOfWeek.FRIDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    public double roundStrike(double price) {
        return Math.round(price / 5.0) * 5.0;
    }

    public double timeToExpiry(LocalDate expiry) {
        long days = ChronoUnit.DAYS.between(today(), expiry);
        return days / 365.0;
    }

    private double[] d1d2(double S, double K, double r, double T, double sigma) {
        double d1 = (Math.log(S / K) + (r + sigma * sigma / 2.0) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);
        return new double[]{d1, d2};
    }

    public double callPrice(double S, double K, double r, double T, double sigma) {
        double[] d = d1d2(S, K, r, T, adjustedSigma(sigma));
        return S * normalCDF(d[0]) - K * Math.exp(-r * T) * normalCDF(d[1]);
    }

    public double putPrice(double S, double K, double r, double T, double sigma) {
        double[] d = d1d2(S, K, r, T, adjustedSigma(sigma));
        return K * Math.exp(-r * T) * normalCDF(-d[1]) - S * normalCDF(-d[0]);
    }

    public GreeksResult greeks(double S, double K, double r, double T, double sigma, boolean isCall) {
        if (T <= 0 || sigma <= 0) return new GreeksResult(0, 0, 0, 0);
        double[] d = d1d2(S, K, r, T, adjustedSigma(sigma));
        double d1 = d[0], d2 = d[1];
        double sqrtT = Math.sqrt(T);
        double phi = normalPDF(d1);
        double Nd1 = normalCDF(d1);
        double Nd2 = normalCDF(d2);

        double delta = isCall ? Nd1 : Nd1 - 1.0;
        double gamma = phi / (S * sigma * sqrtT);
        double thetaCall = (-(S * phi * sigma) / (2.0 * sqrtT) - r * K * Math.exp(-r * T) * Nd2) / 365.0;
        double thetaPut  = (-(S * phi * sigma) / (2.0 * sqrtT) + r * K * Math.exp(-r * T) * normalCDF(-d2)) / 365.0;
        double theta = isCall ? thetaCall : thetaPut;
        double vega = S * phi * sqrtT * 0.01;

        return new GreeksResult(delta, gamma, theta, vega);
    }
}
