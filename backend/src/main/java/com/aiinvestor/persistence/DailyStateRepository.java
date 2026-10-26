package com.aiinvestor.persistence;

import com.aiinvestor.domain.Enums.RunMode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * daily_pnl doubles as the persistent daily risk lock: once {@code locked} is set for (run, date) it is never
 * cleared by the application, so a restart cannot reset the loss limit.
 */
@Repository
public class DailyStateRepository {

    public record DayState(BigDecimal realizedNet, BigDecimal costs, int trades, int wins, int losses,
                           int consecutiveLosses, boolean locked, String lockReason) {
        public static DayState empty() { return new DayState(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, false, null); }
    }

    private final JdbcClient jdbc;

    public DailyStateRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public DayState get(String runId, LocalDate date) {
        return jdbc.sql("SELECT * FROM daily_pnl WHERE run_id=? AND trade_date=?").params(runId, Db.sqlDate(date))
                .query((rs, n) -> new DayState(rs.getBigDecimal("realized_pnl_net"), rs.getBigDecimal("total_costs"),
                        rs.getInt("trades_count"), rs.getInt("wins"), rs.getInt("losses"), rs.getInt("consecutive_losses"),
                        rs.getInt("locked") == 1, rs.getString("lock_reason")))
                .optional().orElse(DayState.empty());
    }

    private void ensureRow(String runId, RunMode mode, LocalDate date) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM daily_pnl WHERE run_id=? AND trade_date=?").params(runId, Db.sqlDate(date))
                .query(Integer.class).single();
        if (n == 0) {
            try {
                jdbc.sql("INSERT INTO daily_pnl (run_id, run_mode, trade_date, updated_at) VALUES (?,?,?,?)")
                        .params(runId, mode.name(), Db.sqlDate(date), Db.ts(Instant.now())).update();
            } catch (DuplicateKeyException ignored) { /* concurrent creator */ }
        }
    }

    /** Record a closed trade's net P&L and update streaks atomically with respect to this row. */
    public void recordClosedTrade(String runId, RunMode mode, LocalDate date, BigDecimal netPnl, BigDecimal costs) {
        ensureRow(runId, mode, date);
        boolean win = netPnl.signum() > 0;
        jdbc.sql("""
                UPDATE daily_pnl SET realized_pnl_net = realized_pnl_net + ?, total_costs = total_costs + ?,
                  trades_count = trades_count + 1, wins = wins + ?, losses = losses + ?,
                  consecutive_losses = CASE WHEN ? = 1 THEN 0 ELSE consecutive_losses + 1 END, updated_at=?
                WHERE run_id=? AND trade_date=?""")
                .params(netPnl, costs, win ? 1 : 0, win ? 0 : 1, win ? 1 : 0, Db.ts(Instant.now()), runId, Db.sqlDate(date)).update();
    }

    /** Idempotent: only the first lock reason is kept. */
    public boolean lock(String runId, RunMode mode, LocalDate date, String reason, Instant at) {
        ensureRow(runId, mode, date);
        return jdbc.sql("UPDATE daily_pnl SET locked=1, lock_reason=?, locked_at=?, updated_at=? WHERE run_id=? AND trade_date=? AND locked=0")
                .params(reason, Db.ts(at), Db.ts(Instant.now()), runId, Db.sqlDate(date)).update() > 0;
    }

    public List<Map<String, Object>> history(String runId) {
        return jdbc.sql("SELECT * FROM daily_pnl WHERE run_id=? ORDER BY trade_date").param(runId).query().listOfRows();
    }
}
