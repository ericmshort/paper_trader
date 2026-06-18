package com.tradingapp.options;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;

/**
 * NYSE market calendar utilities.
 *
 * Half-days (early close at 1:00 PM ET):
 *   - Day before Independence Day (if July 4 is a weekday)
 *   - Black Friday (day after Thanksgiving = 4th Thursday of November)
 *   - Christmas Eve (Dec 24 when Dec 25 is Mon-Fri; or Dec 24 when Dec 25 is Saturday)
 */
public final class MarketCalendar {

    public static final LocalTime HALF_DAY_CLOSE    = LocalTime.of(13,  0);
    public static final LocalTime HALF_DAY_ENTRY_CUTOFF  = LocalTime.of(11, 30);
    public static final LocalTime HALF_DAY_FORCE_CLOSE   = LocalTime.of(12, 30);

    private MarketCalendar() {}

    public static boolean isHalfDay(LocalDate date) {
        return isPreIndependenceDay(date)
            || isBlackFriday(date)
            || isChristmasEve(date);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean isPreIndependenceDay(LocalDate date) {
        LocalDate july4 = LocalDate.of(date.getYear(), 7, 4);
        DayOfWeek dow4  = july4.getDayOfWeek();
        // NYSE observes July 4 on the preceding Friday when July 4 falls on Saturday,
        // and on the following Monday when it falls on Sunday — neither case creates a half-day.
        if (dow4 == DayOfWeek.SATURDAY || dow4 == DayOfWeek.SUNDAY) return false;
        // Last trading day before July 4
        LocalDate prev = july4.minusDays(1);
        while (prev.getDayOfWeek() == DayOfWeek.SATURDAY || prev.getDayOfWeek() == DayOfWeek.SUNDAY)
            prev = prev.minusDays(1);
        return date.equals(prev);
    }

    private static boolean isBlackFriday(LocalDate date) {
        if (date.getDayOfWeek() != DayOfWeek.FRIDAY || date.getMonth() != Month.NOVEMBER) return false;
        LocalDate thanksgiving = nthWeekday(date.getYear(), Month.NOVEMBER, DayOfWeek.THURSDAY, 4);
        return date.equals(thanksgiving.plusDays(1));
    }

    private static boolean isChristmasEve(LocalDate date) {
        if (date.getMonth() != Month.DECEMBER) return false;
        LocalDate christmas = LocalDate.of(date.getYear(), 12, 25);
        DayOfWeek dowXmas = christmas.getDayOfWeek();
        // Christmas on Sunday: NYSE observes Dec 26 (Mon) — Dec 24 (Sat) is not a trading day
        if (dowXmas == DayOfWeek.SUNDAY) return false;
        // Christmas on Saturday: NYSE observes Dec 24 (Fri) as the holiday — NOT a half-day
        if (dowXmas == DayOfWeek.SATURDAY) return false;
        // Christmas Mon-Fri: Dec 24 is a half-day
        return date.equals(LocalDate.of(date.getYear(), 12, 24));
    }

    private static LocalDate nthWeekday(int year, Month month, DayOfWeek dow, int n) {
        LocalDate first = LocalDate.of(year, month, 1);
        int offset = (dow.getValue() - first.getDayOfWeek().getValue() + 7) % 7;
        return first.plusDays(offset + (long)(n - 1) * 7);
    }
}
