package com.aiinvestor.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Spring context on H2 (MySQL mode) with the real Flyway migrations; tables are emptied before every test. */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractDbTest {
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        for (String t : new String[]{"live_order_approvals", "trade_executions", "trade_orders", "positions", "ai_analysis_records",
                "trading_signals", "daily_pnl", "risk_events", "audit_logs", "market_candles", "strategy_configurations", "trading_runs"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("UPDATE application_settings SET setting_value='OFF' WHERE setting_key='kill_switch'").update();
        jdbc.sql("UPDATE application_settings SET setting_value='NO' WHERE setting_key='live_authorized'").update();
        jdbc.sql("DELETE FROM application_settings WHERE setting_key NOT IN ('execution_mode','kill_switch','live_authorized')").update();
    }
}
