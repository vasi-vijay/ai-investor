package com.aiinvestor.persistence;

import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.OptionType;
import com.aiinvestor.domain.Instrument;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

@Repository
public class InstrumentRepository {
    private final JdbcClient jdbc;

    public InstrumentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Instrument upsert(Instrument i) {
        Optional<Instrument> existing = find(i.exchange(), i.segment(), i.tradingSymbol());
        if (existing.isPresent()) {
            Long id = existing.get().id();
            jdbc.sql("""
                    UPDATE instruments SET groww_symbol=?, name=?, underlying_symbol=?, expiry_date=?, strike=?, option_type=?,
                      lot_size=?, tick_size=?, exchange_token=?, tradable=?, source=?, refreshed_at=? WHERE id=?""")
                    .params(i.growwSymbol(), i.name(), i.underlying(), Db.sqlDate(i.expiry()), i.strike(),
                            i.optionType() == null ? null : i.optionType().name(), i.lotSize(), i.tickSize(),
                            i.exchangeToken(), i.tradable() ? 1 : 0, i.source(), Db.ts(Instant.now()), id)
                    .update();
            return i.withId(id);
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO instruments (exchange, segment, instrument_type, trading_symbol, groww_symbol, name,
                  underlying_symbol, expiry_date, strike, option_type, lot_size, tick_size, exchange_token, tradable, source, refreshed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(i.exchange(), i.segment(), i.type().name(), i.tradingSymbol(), i.growwSymbol(), i.name(),
                        i.underlying(), Db.sqlDate(i.expiry()), i.strike(),
                        i.optionType() == null ? null : i.optionType().name(), i.lotSize(), i.tickSize(),
                        i.exchangeToken(), i.tradable() ? 1 : 0, i.source(), Db.ts(Instant.now()))
                .update(kh);
        return i.withId(kh.getKey().longValue());
    }

    public Optional<Instrument> find(String exchange, String segment, String symbol) {
        return jdbc.sql("SELECT * FROM instruments WHERE exchange=? AND segment=? AND trading_symbol=?")
                .params(exchange, segment, symbol).query(this::map).optional();
    }

    public Optional<Instrument> findById(long id) {
        return jdbc.sql("SELECT * FROM instruments WHERE id=?").param(id).query(this::map).optional();
    }

    private Instrument map(ResultSet rs, int n) throws SQLException {
        String ot = rs.getString("option_type");
        return new Instrument(rs.getLong("id"), rs.getString("exchange"), rs.getString("segment"),
                InstrumentType.valueOf(rs.getString("instrument_type")), rs.getString("trading_symbol"),
                rs.getString("groww_symbol"), rs.getString("name"), rs.getString("underlying_symbol"),
                Db.date(rs, "expiry_date"), rs.getBigDecimal("strike"), ot == null ? null : OptionType.valueOf(ot),
                rs.getInt("lot_size"), rs.getBigDecimal("tick_size"), rs.getString("exchange_token"),
                rs.getInt("tradable") == 1, rs.getString("source"));
    }
}
