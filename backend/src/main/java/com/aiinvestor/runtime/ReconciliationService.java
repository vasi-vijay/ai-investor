package com.aiinvestor.runtime;

import com.aiinvestor.persistence.AuditRepository;
import com.aiinvestor.persistence.RiskEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * End-of-day internal reconciliation of the simulated books: orders vs executions, positions vs P&L, daily_pnl vs
 * closed positions. (Broker-side reconciliation for live orders lives in LiveOrderService.)
 */
@Service
public class ReconciliationService {
    private final JdbcClient jdbc;
    private final AuditRepository audit;
    private final RiskEventRepository events;

    public ReconciliationService(JdbcClient jdbc, AuditRepository audit, RiskEventRepository events) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    public List<String> endOfDay(String runId, String runMode, LocalDate day) {
        List<String> issues = new ArrayList<>();
        Integer open = jdbc.sql("SELECT COUNT(*) FROM positions WHERE run_id=? AND status='OPEN'").param(runId).query(Integer.class).single();
        if (open > 0) issues.add(open + " position(s) still OPEN after square-off");
        Integer pend = jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE run_id=? AND status IN ('PENDING','PARTIAL')").param(runId).query(Integer.class).single();
        if (pend > 0) issues.add(pend + " order(s) still PENDING/PARTIAL");
        List<Map<String, Object>> mism = jdbc.sql("""
                SELECT o.id, o.filled_quantity, COALESCE(SUM(e.quantity),0) AS exec_qty FROM trade_orders o
                LEFT JOIN trade_executions e ON e.order_id=o.id WHERE o.run_id=? GROUP BY o.id, o.filled_quantity
                HAVING o.filled_quantity <> COALESCE(SUM(e.quantity),0)""").param(runId).query().listOfRows();
        if (!mism.isEmpty()) issues.add(mism.size() + " order(s) whose filled quantity differs from execution rows");
        BigDecimal posNet = jdbc.sql("SELECT COALESCE(SUM(realized_pnl_net),0) FROM positions WHERE run_id=? AND status='CLOSED'").param(runId).query(BigDecimal.class).single();
        BigDecimal dayNet = jdbc.sql("SELECT COALESCE(SUM(realized_pnl_net),0) FROM daily_pnl WHERE run_id=?").param(runId).query(BigDecimal.class).single();
        if (posNet.subtract(dayNet).abs().compareTo(new BigDecimal("0.05")) > 0) {
            issues.add("closed-position net " + posNet + " differs from daily_pnl total " + dayNet);
        }
        BigDecimal execCosts = jdbc.sql("SELECT COALESCE(SUM(total_costs),0) FROM trade_executions WHERE run_id=?").param(runId).query(BigDecimal.class).single();
        BigDecimal posCosts = jdbc.sql("SELECT COALESCE(SUM(total_costs),0) FROM positions WHERE run_id=?").param(runId).query(BigDecimal.class).single();
        if (execCosts.subtract(posCosts).abs().compareTo(new BigDecimal("0.5")) > 0) {
            issues.add("execution costs " + execCosts + " differ from position costs " + posCosts);
        }
        audit.log("system", issues.isEmpty() ? "EOD_RECONCILIATION_OK" : "EOD_RECONCILIATION_ISSUES", "run", runId,
                day + (issues.isEmpty() ? "" : ": " + String.join("; ", issues)));
        for (String i : issues) events.record(runId, runMode, null, "RECONCILIATION", "EOD", "WARN", i);
        return issues;
    }
}
