package com.aiinvestor.runtime;

import com.aiinvestor.market.SettableClock;
import com.aiinvestor.market.TradingClock;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** One real paper cycle on mock data: the audit trail, reproducibility records and idempotency. */
@SpringBootTest(properties = "app.mock.replay-enabled=true")
class PaperCycleTest extends AbstractDbTest {
    @Autowired PaperTradingService paper;
    @Autowired TradingClock clock;
    @Autowired Fixtures fx;

    @Test
    void aCycleRecordsSignalsCandlesAndEffectiveStrategyConfiguration() {
        paper.init();
        String runId = paper.currentRun().orElseThrow().id();
        ((SettableClock) clock).set(fx.at(Fixtures.TUESDAY, 11, 0));
        paper.runCycle();

        int signals = jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=?").param(runId).query(Integer.class).single();
        assertThat(signals).as("every strategy evaluation is persisted, including NO_TRADE").isGreaterThan(5);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND rule_decision='NO_TRADE'").param(runId).query(Integer.class).single()).isGreaterThan(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=? AND run_mode<>'PAPER'").param(runId).query(Integer.class).single()).isZero();

        assertThat(jdbc.sql("SELECT COUNT(*) FROM market_candles WHERE source='MOCK' AND interval_code='5m'").query(Integer.class).single())
                .as("analysed candles are archived with their source").isGreaterThan(0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM strategy_configurations").query(Integer.class).single()).isEqualTo(3);
        String cfg = jdbc.sql("SELECT config_json FROM trading_runs WHERE id=?").param(runId).query(String.class).single();
        assertThat(cfg).contains("strategies_effective").contains("VWAP_TREND").contains("risk").contains("prompt_version");

        // repeating the same cycle must not duplicate decisions or candles (idempotency keys)
        paper.runCycle();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_signals WHERE run_id=?").param(runId).query(Integer.class).single()).isEqualTo(signals);
        int candles = jdbc.sql("SELECT COUNT(*) FROM market_candles").query(Integer.class).single();
        paper.runCycle();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM market_candles").query(Integer.class).single()).isEqualTo(candles);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE run_id=? AND simulated=0").param(runId).query(Integer.class).single()).isZero();
    }
}
