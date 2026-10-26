package com.aiinvestor.runtime;

import com.aiinvestor.domain.Enums.*;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.engine.UniverseService;
import com.aiinvestor.market.SettableClock;
import com.aiinvestor.market.TradingClock;
import com.aiinvestor.persistence.DailyStateRepository;
import com.aiinvestor.persistence.Db;
import com.aiinvestor.persistence.TradeRepository;
import com.aiinvestor.persistence.TradeRepository.PositionRec;
import com.aiinvestor.risk.KillSwitch;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** "Restart" = calling init() again over the same database: runs, positions, locks and the kill switch must all survive. */
@SpringBootTest(properties = "app.mock.replay-enabled=true")
class StartupRecoveryTest extends AbstractDbTest {
    @Autowired PaperTradingService paper;
    @Autowired TradeRepository trades;
    @Autowired DailyStateRepository daily;
    @Autowired UniverseService universe;
    @Autowired TradingClock clock;
    @Autowired KillSwitch kill;
    @Autowired Fixtures fx;

    @Test
    void aRestartResumesTheSamePaperRunWithOpenPositionsLocksAndKillSwitchIntact() {
        paper.init();
        String runId = paper.currentRun().orElseThrow().id();
        Instrument rel = universe.analysisUniverse().stream().filter(i -> i.tradingSymbol().equals("RELIANCE")).findFirst().orElseThrow();
        Instant opened = fx.at(Fixtures.TUESDAY, 9, 20);
        String posId = Db.newId();
        trades.insertPosition(new PositionRec(posId, runId, RunMode.PAPER, rel.id(), null, "TEST", Direction.LONG, 50, 50, new BigDecimal("2900"), new BigDecimal("2800"),
                new BigDecimal("3000"), "OPEN", opened, BigDecimal.ZERO, new BigDecimal("40"), new BigDecimal("5000"), BigDecimal.ZERO, 0));
        daily.lock(runId, RunMode.PAPER, Fixtures.TUESDAY, "daily loss limit reached (test)", opened);
        kill.set(true, "tester", "restart test");

        // ---- restart ----
        paper.init();

        assertThat(paper.currentRun().orElseThrow().id()).as("the same PAPER run is resumed, not a new one").isEqualTo(runId);
        assertThat(trades.openPositions(runId)).extracting(PositionRec::id).containsExactly(posId);
        assertThat(daily.get(runId, Fixtures.TUESDAY).locked()).as("daily loss lock survives restart").isTrue();
        assertThat(kill.isEngaged()).as("kill switch survives restart").isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trading_runs WHERE run_mode='PAPER'").query(Integer.class).single()).isEqualTo(1);
        kill.set(false, "tester", "cleanup");
    }

    @Test
    void afterARestartTheRecoveredPositionIsManagedAgainstBarsThatArrivedMeanwhile() {
        paper.init();
        String runId = paper.currentRun().orElseThrow().id();
        Instrument rel = universe.analysisUniverse().stream().filter(i -> i.tradingSymbol().equals("RELIANCE")).findFirst().orElseThrow();
        Instant opened = fx.at(Fixtures.TUESDAY, 9, 20);
        String posId = Db.newId();
        // a long whose stop sits far ABOVE the market: the first bar after opening must stop it out
        trades.insertPosition(new PositionRec(posId, runId, RunMode.PAPER, rel.id(), null, "TEST", Direction.LONG, 10, 10, new BigDecimal("2900"), new BigDecimal("999999"),
                new BigDecimal("1000000"), "OPEN", opened, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1000"), BigDecimal.ZERO, 0));
        paper.init();                                                         // restart
        ((SettableClock) clock).set(fx.at(Fixtures.TUESDAY, 10, 0));          // time passed while the app was down
        paper.runCycle();
        assertThat(trades.position(posId).orElseThrow().status()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("SELECT exit_reason FROM positions WHERE id=?").param(posId).query(String.class).single()).isEqualTo("STOP_LOSS");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action='PAPER_RUN_STARTED'").query(Integer.class).single()).isGreaterThanOrEqualTo(2);
    }
}
