package com.tradingapp.options;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

public class BlackScholesEngine {

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
        LocalDate today = LocalDate.now();
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
        long daysToNext = ChronoUnit.DAYS.between(LocalDate.now(), next);
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

    public double roundStrike(double price) {
        return Math.round(price / 5.0) * 5.0;
    }

    public double timeToExpiry(LocalDate expiry) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), expiry);
        return days / 365.0;
    }

    private double[] d1d2(double S, double K, double r, double T, double sigma) {
        double d1 = (Math.log(S / K) + (r + sigma * sigma / 2.0) * T) / (sigma * Math.sqrt(T));
        double d2 = d1 - sigma * Math.sqrt(T);
        return new double[]{d1, d2};
    }

    public double callPrice(double S, double K, double r, double T, double sigma) {
        double[] d = d1d2(S, K, r, T, sigma);
        return S * normalCDF(d[0]) - K * Math.exp(-r * T) * normalCDF(d[1]);
    }

    public double putPrice(double S, double K, double r, double T, double sigma) {
        double[] d = d1d2(S, K, r, T, sigma);
        return K * Math.exp(-r * T) * normalCDF(-d[1]) - S * normalCDF(-d[0]);
    }

    public GreeksResult greeks(double S, double K, double r, double T, double sigma, boolean isCall) {
        if (T <= 0 || sigma <= 0) return new GreeksResult(0, 0, 0, 0);
        double[] d = d1d2(S, K, r, T, sigma);
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
