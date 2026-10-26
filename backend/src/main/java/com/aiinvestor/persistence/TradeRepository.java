package com.aiinvestor.persistence;

import com.aiinvestor.domain.Enums.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Orders, executions and positions. Every row carries run_id + run_mode. */
@Repository
public class TradeRepository {

    public record OrderRec(String id, String runId, RunMode runMode, String clientOrderId, String signalId, String positionId,
                           long instrumentId, Side side, OrderType type, OrderPurpose purpose, int quantity,
                           BigDecimal limitPrice, OrderStatus status, int filledQuantity, BigDecimal avgFillPrice,
                           Instant createdAt, BigDecimal planStop, BigDecimal planTarget, BigDecimal planInitialRisk,
                           String planStrategy) {}

    public record PositionRec(String id, String runId, RunMode runMode, long instrumentId, String signalId, String strategy,
                              Direction side, int quantity, int openQuantity, BigDecimal avgEntry, BigDecimal stop,
                              BigDecimal target, String status, Instant openedAt, BigDecimal grossPnl, BigDecimal costs,
                              BigDecimal initialRisk, BigDecimal exitedValue, int exitedQuantity) {}

    private final JdbcClient jdbc;

    public TradeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- orders ----

    /** Returns empty when (run, clientOrderId) already exists: duplicate-order protection at the DB level. */
    public Optional<OrderRec> insertOrder(OrderRec o, boolean simulated) {
        try {
            jdbc.sql("""
                    INSERT INTO trade_orders (id, run_id, run_mode, client_order_id, signal_id, position_id, instrument_id, side,
                      order_type, purpose, quantity, limit_price, status, filled_quantity, avg_fill_price, simulated, created_at, updated_at,
                      plan_stop_loss, plan_target, plan_initial_risk, plan_strategy)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                    .params(o.id(), o.runId(), o.runMode().name(), o.clientOrderId(), o.signalId(), o.positionId(), o.instrumentId(),
                            o.side().name(), o.type().name(), o.purpose().name(), o.quantity(), o.limitPrice(), o.status().name(),
                            o.filledQuantity(), o.avgFillPrice(), simulated ? 1 : 0, Db.ts(o.createdAt()), Db.ts(o.createdAt()),
                            o.planStop(), o.planTarget(), o.planInitialRisk(), o.planStrategy())
                    .update();
            return Optional.of(o);
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public void updateOrder(String id, OrderStatus status, int filledQty, BigDecimal avgFill, String rejectReason, Instant at) {
        jdbc.sql("UPDATE trade_orders SET status=?, filled_quantity=?, avg_fill_price=?, reject_reason=?, updated_at=? WHERE id=?")
                .params(status.name(), filledQty, avgFill, rejectReason == null ? null : rejectReason.substring(0, Math.min(500, rejectReason.length())),
                        Db.ts(at), id).update();
    }

    /** LIVE orders only: link the local row to the broker's order and mirror its status. */
    public void syncBroker(String orderId, String brokerOrderId, String brokerStatus, OrderStatus status, int filledQty, BigDecimal avgFill, Instant at) {
        jdbc.sql("UPDATE trade_orders SET broker_order_id=?, broker_status=?, status=?, filled_quantity=?, avg_fill_price=?, updated_at=? WHERE id=? AND simulated=0")
                .params(brokerOrderId, brokerStatus, status.name(), filledQty, avgFill, Db.ts(at), orderId).update();
    }

    public void attachPosition(String orderId, String positionId) {
        jdbc.sql("UPDATE trade_orders SET position_id=? WHERE id=?").params(positionId, orderId).update();
    }

    public List<OrderRec> openOrders(String runId) {
        return jdbc.sql("SELECT * FROM trade_orders WHERE run_id=? AND status IN ('PENDING','PARTIAL') ORDER BY created_at")
                .param(runId).query((rs, n) -> mapOrder(rs)).list();
    }

    public Optional<OrderRec> order(String id) {
        return jdbc.sql("SELECT * FROM trade_orders WHERE id=?").param(id).query((rs, n) -> mapOrder(rs)).optional();
    }

    public boolean hasOrderWithClientId(String runId, String clientOrderId) {
        return jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE run_id=? AND client_order_id=?")
                .params(runId, clientOrderId).query(Integer.class).single() > 0;
    }

    public List<Map<String, Object>> recentOrders(String runId, int limit) {
        return jdbc.sql("""
                SELECT o.*, i.trading_symbol AS instrument_symbol FROM trade_orders o JOIN instruments i ON i.id=o.instrument_id
                WHERE o.run_id=? ORDER BY o.created_at DESC, o.id LIMIT ?""").params(runId, limit).query().listOfRows();
    }

    // ---- executions ----

    public void insertExecution(String id, String runId, RunMode mode, String orderId, long instrumentId, Side side, int qty,
                                BigDecimal marketPrice, BigDecimal fillPrice, BigDecimal slippageAmount, BigDecimal brokerage,
                                BigDecimal statutory, BigDecimal totalCosts, Instant at) {
        jdbc.sql("""
                INSERT INTO trade_executions (id, run_id, run_mode, order_id, instrument_id, side, quantity, market_price,
                  fill_price, slippage_amount, brokerage, statutory_costs, total_costs, simulated, executed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(id, runId, mode.name(), orderId, instrumentId, side.name(), qty, marketPrice, fillPrice, slippageAmount,
                        brokerage, statutory, totalCosts, 1, Db.ts(at)).update();
    }

    public List<Map<String, Object>> recentExecutions(String runId, int limit) {
        return jdbc.sql("""
                SELECT e.*, i.trading_symbol AS instrument_symbol FROM trade_executions e JOIN instruments i ON i.id=e.instrument_id
                WHERE e.run_id=? ORDER BY e.executed_at DESC, e.id LIMIT ?""").params(runId, limit).query().listOfRows();
    }

    // ---- positions ----

    public void insertPosition(PositionRec p) {
        jdbc.sql("""
                INSERT INTO positions (id, run_id, run_mode, instrument_id, signal_id, strategy_name, side, quantity, open_quantity,
                  avg_entry_price, stop_loss, target_price, status, opened_at, realized_pnl_gross, total_costs, realized_pnl_net, initial_risk)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
                .params(p.id(), p.runId(), p.runMode().name(), p.instrumentId(), p.signalId(), p.strategy(), p.side().name(),
                        p.quantity(), p.openQuantity(), p.avgEntry(), p.stop(), p.target(), p.status(), Db.ts(p.openedAt()),
                        p.grossPnl(), p.costs(), p.grossPnl().subtract(p.costs()), p.initialRisk()).update();
    }

    /** Persist the mutable position state (called after every fill). */
    public void savePosition(PositionRec p, Instant closedAt, String exitReason, BigDecimal avgExit) {
        jdbc.sql("""
                UPDATE positions SET quantity=?, open_quantity=?, avg_entry_price=?, stop_loss=?, target_price=?, status=?,
                  closed_at=?, avg_exit_price=?, exit_reason=?, realized_pnl_gross=?, total_costs=?, realized_pnl_net=? WHERE id=?""")
                .params(p.quantity(), p.openQuantity(), p.avgEntry(), p.stop(), p.target(), p.status(), Db.ts(closedAt), avgExit,
                        exitReason, p.grossPnl(), p.costs(), p.grossPnl().subtract(p.costs()), p.id()).update();
    }

    public List<PositionRec> openPositions(String runId) {
        return jdbc.sql("SELECT * FROM positions WHERE run_id=? AND status='OPEN' ORDER BY opened_at").param(runId)
                .query((rs, n) -> mapPosition(rs)).list();
    }

    public Optional<PositionRec> position(String id) {
        return jdbc.sql("SELECT * FROM positions WHERE id=?").param(id).query((rs, n) -> mapPosition(rs)).optional();
    }

    public List<Map<String, Object>> positionsView(String runId, String status, int limit) {
        return jdbc.sql("""
                SELECT p.*, i.trading_symbol AS instrument_symbol, i.instrument_type, i.lot_size FROM positions p
                JOIN instruments i ON i.id=p.instrument_id WHERE p.run_id=? AND p.status=? ORDER BY p.opened_at DESC LIMIT ?""")
                .params(runId, status, limit).query().listOfRows();
    }

    /** Closed positions with instrument + signal context for analytics (per run). */
    public List<Map<String, Object>> closedTrades(String runId) {
        return jdbc.sql("""
                SELECT p.id, p.strategy_name, p.side, p.quantity, p.avg_entry_price, p.avg_exit_price, p.opened_at, p.closed_at,
                  p.exit_reason, p.realized_pnl_gross, p.total_costs, p.realized_pnl_net, p.initial_risk, p.signal_id,
                  i.trading_symbol AS instrument_symbol, i.instrument_type, i.underlying_symbol,
                  s.market_regime, s.ai_decision, s.ai_agrees
                FROM positions p JOIN instruments i ON i.id=p.instrument_id
                LEFT JOIN trading_signals s ON s.id=p.signal_id
                WHERE p.run_id=? AND p.status='CLOSED' ORDER BY p.closed_at""").param(runId).query().listOfRows();
    }

    public BigDecimal realizedNetTotal(String runId) {
        return jdbc.sql("SELECT COALESCE(SUM(realized_pnl_net),0) FROM positions WHERE run_id=? AND status='CLOSED'")
                .param(runId).query(BigDecimal.class).single();
    }

    public int countEntriesOn(String runId, Instant from, Instant to) {
        return jdbc.sql("SELECT COUNT(*) FROM positions WHERE run_id=? AND opened_at >= ? AND opened_at < ?")
                .params(runId, Db.ts(from), Db.ts(to)).query(Integer.class).single();
    }

    private OrderRec mapOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OrderRec(rs.getString("id"), rs.getString("run_id"), RunMode.valueOf(rs.getString("run_mode")),
                rs.getString("client_order_id"), rs.getString("signal_id"), rs.getString("position_id"), rs.getLong("instrument_id"),
                Side.valueOf(rs.getString("side")), OrderType.valueOf(rs.getString("order_type")),
                OrderPurpose.valueOf(rs.getString("purpose")), rs.getInt("quantity"), rs.getBigDecimal("limit_price"),
                OrderStatus.valueOf(rs.getString("status")), rs.getInt("filled_quantity"), rs.getBigDecimal("avg_fill_price"),
                Db.instant(rs, "created_at"), rs.getBigDecimal("plan_stop_loss"), rs.getBigDecimal("plan_target"),
                rs.getBigDecimal("plan_initial_risk"), rs.getString("plan_strategy"));
    }

    private PositionRec mapPosition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PositionRec(rs.getString("id"), rs.getString("run_id"), RunMode.valueOf(rs.getString("run_mode")),
                rs.getLong("instrument_id"), rs.getString("signal_id"), rs.getString("strategy_name"),
                Direction.valueOf(rs.getString("side")), rs.getInt("quantity"), rs.getInt("open_quantity"),
                rs.getBigDecimal("avg_entry_price"), rs.getBigDecimal("stop_loss"), rs.getBigDecimal("target_price"),
                rs.getString("status"), Db.instant(rs, "opened_at"), rs.getBigDecimal("realized_pnl_gross"),
                rs.getBigDecimal("total_costs"), rs.getBigDecimal("initial_risk"), BigDecimal.ZERO, 0);
    }
}
