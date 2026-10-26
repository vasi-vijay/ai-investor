package com.aiinvestor.persistence;

import com.aiinvestor.domain.Candle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/** Archive of the completed candles the engine analysed (source-labelled, so mock and real data never mix). */
@Repository
public class CandleRepository {
    private final JdbcClient jdbc;

    public CandleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Idempotent: returns false if the (instrument, interval, bucket, source) candle already exists. */
    public boolean insertIfAbsent(long instrumentId, String interval, Candle c, String source) {
        try {
            jdbc.sql("""
                    INSERT INTO market_candles (instrument_id, interval_code, bucket_start, open_price, high_price, low_price, close_price, volume, open_interest, source)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""")
                    .params(instrumentId, interval, Db.ts(c.start()), BigDecimal.valueOf(c.open()), BigDecimal.valueOf(c.high()), BigDecimal.valueOf(c.low()),
                            BigDecimal.valueOf(c.close()), c.volume(), c.openInterest(), source).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public int count() { return jdbc.sql("SELECT COUNT(*) FROM market_candles").query(Integer.class).single(); }
}
