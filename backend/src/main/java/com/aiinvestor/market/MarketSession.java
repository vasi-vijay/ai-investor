package com.aiinvestor.market;

import com.aiinvestor.config.AppProperties;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NSE cash/F&O session rules from configuration. The holiday list is operator-maintained: this class never
 * assumes a trading calendar beyond Mon-Fri. Anything outside the configured hours is "closed".
 */
public class MarketSession {
    private final ZoneId zone;
    private final LocalTime open;
    private final LocalTime close;
    private final LocalTime noNewEntriesAfter;
    private final LocalTime squareOffAt;
    private final Set<LocalDate> holidays;

    public MarketSession(AppProperties.Market m) {
        this.zone = ZoneId.of(m.timezone());
        this.open = LocalTime.parse(m.open());
        this.close = LocalTime.parse(m.close());
        this.noNewEntriesAfter = LocalTime.parse(m.noNewEntriesAfter());
        this.squareOffAt = LocalTime.parse(m.squareOffAt());
        this.holidays = new HashSet<>(m.holidays() == null ? List.of() : m.holidays());
    }

    public ZoneId zone() { return zone; }
    public LocalTime openTime() { return open; }
    public LocalTime closeTime() { return close; }
    public LocalTime squareOffTime() { return squareOffAt; }

    public LocalDate dateOf(Instant t) { return t.atZone(zone).toLocalDate(); }

    public boolean isTradingDay(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public Instant openInstant(LocalDate d) { return ZonedDateTime.of(d, open, zone).toInstant(); }
    public Instant closeInstant(LocalDate d) { return ZonedDateTime.of(d, close, zone).toInstant(); }
    public Instant squareOffInstant(LocalDate d) { return ZonedDateTime.of(d, squareOffAt, zone).toInstant(); }

    /** True while the continuous session is running: open <= t < close on a trading day. */
    public boolean isOpen(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        if (!isTradingDay(z.toLocalDate())) return false;
        LocalTime lt = z.toLocalTime();
        return !lt.isBefore(open) && lt.isBefore(close);
    }

    /** New entries allowed: session open and before the configured cutoff. */
    public boolean allowsNewEntries(Instant t) {
        if (!isOpen(t)) return false;
        return t.atZone(zone).toLocalTime().isBefore(noNewEntriesAfter);
    }

    public boolean isSquareOffTime(Instant t) {
        if (!isOpen(t)) return false;
        return !t.atZone(zone).toLocalTime().isBefore(squareOffAt);
    }

    public LocalDate previousTradingDay(LocalDate d) {
        LocalDate p = d.minusDays(1);
        while (!isTradingDay(p)) p = p.minusDays(1);
        return p;
    }

    public LocalDate nextTradingDay(LocalDate d) {
        LocalDate p = d.plusDays(1);
        while (!isTradingDay(p)) p = p.plusDays(1);
        return p;
    }

    public String statusLabel(Instant t) {
        if (isOpen(t)) return "OPEN";
        ZonedDateTime z = t.atZone(zone);
        if (!isTradingDay(z.toLocalDate())) return "CLOSED_NON_TRADING_DAY";
        return z.toLocalTime().isBefore(open) ? "PRE_OPEN" : "CLOSED";
    }
}
