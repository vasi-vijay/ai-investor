package com.aiinvestor.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Small JDBC helpers. The JVM default time zone is forced to UTC at startup so DATETIME(3) round-trips as UTC. */
public final class Db {
    private Db() {}

    public static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    public static java.sql.Date sqlDate(LocalDate d) { return d == null ? null : java.sql.Date.valueOf(d); }

    public static BigDecimal dec(ResultSet rs, String col) throws SQLException { return rs.getBigDecimal(col); }

    /** Row-map values differ by driver (H2: Timestamp, MySQL Connector/J: LocalDateTime). All DATETIME columns hold UTC. */
    public static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.LocalDateTime l) return l.toInstant(java.time.ZoneOffset.UTC);
        if (o instanceof java.time.OffsetDateTime od) return od.toInstant();
        if (o instanceof Instant i) return i;
        if (o instanceof java.util.Date d) return d.toInstant();
        throw new IllegalArgumentException("unsupported timestamp type " + o.getClass());
    }

    public static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.time.LocalDateTime l) return l.toLocalDate();
        throw new IllegalArgumentException("unsupported date type " + o.getClass());
    }

    /** TINYINT(1) comes back as Boolean from MySQL Connector/J but as a number from H2; normalise. */
    public static Boolean bool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(o.toString());
    }

    public static String newId() { return UUID.randomUUID().toString(); }

    public static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
