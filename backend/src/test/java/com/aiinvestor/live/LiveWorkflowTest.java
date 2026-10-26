package com.aiinvestor.live;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Instrument;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.SettableClock;
import com.aiinvestor.market.TradingClock;
import com.aiinvestor.mock.MockBrokerOrderProvider;
import com.aiinvestor.persistence.InstrumentRepository;
import com.aiinvestor.risk.KillSwitch;
import com.aiinvestor.support.AbstractDbTest;
import com.aiinvestor.support.Fixtures;
import com.aiinvestor.support.LiveTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"app.mock.replay-enabled=true", "app.live.enabled=true"})
@Import(LiveTestConfig.class)
class LiveWorkflowTest extends AbstractDbTest {
    @Autowired LiveOrderService live;
    @Autowired MockBrokerOrderProvider broker;
    @Autowired LiveTestConfig.AdjustableAccount account;
    @Autowired InstrumentRepository instruments;
    @Autowired TradingClock clock;
    @Autowired MarketDataProvider md;
    @Autowired KillSwitch kill;
    @Autowired Fixtures fx;
    @Autowired AppProperties props;

    Instrument ins;

    @BeforeEach
    void setUp() {
        broker.reset();
        account.funds = new BigDecimal("1000000");
        ((SettableClock) clock).set(fx.tuesday1000());
        // broker-sourced metadata is required for live orders
        ins = instruments.upsert(new Instrument(null, "NSE", "CASH", InstrumentType.EQUITY, "RELIANCE", "NSE-RELIANCE", "Reliance", null, null, null, null, 1,
                new BigDecimal("0.05"), "2885", true, "GROWW"));
    }

    private LiveOrderService.ProposeRequest proposal(int qty) {
        double last = md.getQuote(ins).last();
        BigDecimal px = BigDecimal.valueOf(Math.round(last * 20) / 20.0);
        return new LiveOrderService.ProposeRequest(ins.id(), "BUY", "LIMIT", qty, px, px.multiply(new BigDecimal("0.99")).setScale(2, java.math.RoundingMode.HALF_EVEN),
                px.multiply(new BigDecimal("1.02")).setScale(2, java.math.RoundingMode.HALF_EVEN), null);
    }

    private String propose(int qty) { return (String) live.propose(proposal(qty), "admin").get("id"); }

    @Test
    void proposalsWaitForHumanApprovalAndNothingIsSentBeforeThat() {
        String id = propose(10);
        assertThat(live.get(id).get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(broker.receivedRequests()).isEmpty();
        live.reject(id, "admin", "changed my mind");
        assertThat(live.get(id).get("status")).isEqualTo("REJECTED");
        assertThat(broker.receivedRequests()).isEmpty();
        assertThatThrownBy(() -> live.approve(id, "admin")).isInstanceOf(IllegalStateException.class);
        assertThat(broker.receivedRequests()).isEmpty();
    }

    @Test
    void approvalSubmitsExactlyOneOrderAndRecordsALiveNotSimulatedRow() {
        String id = propose(10);
        Map<String, Object> done = live.approve(id, "admin");
        assertThat(done.get("status")).isEqualTo("SUBMITTED");
        assertThat(broker.receivedRequests()).hasSize(1);
        var req = broker.receivedRequests().get(0);
        assertThat(req.reference()).isEqualTo(done.get("reference")).startsWith("AI");
        assertThat(req.product()).isEqualTo("MIS");
        assertThat(req.quantity()).isEqualTo(10);
        Map<String, Object> o = jdbc.sql("SELECT run_mode, simulated, broker_order_id FROM trade_orders WHERE client_order_id=?").param(req.reference()).query().singleRow();
        assertThat(o.get("run_mode")).isEqualTo("LIVE");
        assertThat(com.aiinvestor.persistence.Db.bool(o.get("simulated"))).isFalse();
        assertThat(o.get("broker_order_id")).isNotNull();
        assertThatThrownBy(() -> live.approve(id, "admin")).isInstanceOf(IllegalStateException.class);        // cannot be approved twice
        assertThat(broker.receivedRequests()).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action IN ('LIVE_ORDER_PROPOSED','LIVE_ORDER_SUBMITTING','LIVE_ORDER_SUBMITTED')").query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void anExpiredApprovalWindowSendsNothing() {
        String id = propose(10);
        ((SettableClock) clock).set(fx.tuesday1000().plus(Duration.ofMinutes(props.live().approvalTtlMinutes() + 1)));
        assertThatThrownBy(() -> live.approve(id, "admin")).hasMessageContaining("elapsed");
        assertThat(live.get(id).get("status")).isEqualTo("EXPIRED");
        assertThat(broker.receivedRequests()).isEmpty();
    }

    @Test
    void theKillSwitchStopsProposalsAndApprovals() {
        String id = propose(10);
        kill.set(true, "admin", "test");
        assertThatThrownBy(() -> live.approve(id, "admin")).isInstanceOf(RuntimeException.class);
        assertThat(broker.receivedRequests()).isEmpty();
        assertThatThrownBy(() -> propose(10)).isInstanceOf(RuntimeException.class);
        kill.set(false, "admin", "test");
    }

    @Test
    void ordersTooLargeForTheRiskBudgetOrFundsAreRefused() {
        assertThatThrownBy(() -> propose(100_000)).isInstanceOf(RuntimeException.class);       // exceeds max order value / sizing
        String id = propose(10);
        account.funds = new BigDecimal("100");
        assertThatThrownBy(() -> live.approve(id, "admin")).hasMessageContaining("insufficient available funds");
        assertThat(live.get(id).get("status")).isEqualTo("FAILED");
        assertThat(broker.receivedRequests()).isEmpty();
    }

    @Test
    void brokerRejectionAndAuthExpiryAreRecordedAsFailedWithoutRetry() {
        String id = propose(10);
        broker.setMode(MockBrokerOrderProvider.Mode.REJECT);
        assertThat(live.approve(id, "admin").get("status")).isEqualTo("FAILED");
        assertThat(broker.receivedRequests()).hasSize(1);
        String id2 = propose(11);
        broker.setMode(MockBrokerOrderProvider.Mode.AUTH_EXPIRED);
        Map<String, Object> r = live.approve(id2, "admin");
        assertThat(r.get("status")).isEqualTo("FAILED");
        assertThat((String) r.get("note")).contains("Auth");
        assertThat(broker.receivedRequests()).hasSize(2);        // one attempt each, never repeated
    }

    @Test
    void anAmbiguousSubmissionIsParkedThenReconciledByReferenceNeverResent() {
        String id = propose(10);
        broker.setMode(MockBrokerOrderProvider.Mode.AMBIGUOUS_ORDER_EXISTS);        // broker received it, but the caller saw a timeout
        Map<String, Object> amb = live.approve(id, "admin");
        assertThat(amb.get("status")).isEqualTo("UNKNOWN_RECONCILE");
        assertThat(broker.receivedRequests()).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE simulated=0").query(Integer.class).single()).as("no local order until reconciled").isZero();
        broker.setMode(MockBrokerOrderProvider.Mode.NORMAL);
        Map<String, Object> rec = live.reconcile(id, "admin");
        assertThat(rec.get("status")).isEqualTo("SUBMITTED");
        assertThat(rec.get("broker_order_id")).isNotNull();
        assertThat(broker.receivedRequests()).as("reconciliation adopted the existing broker order instead of re-sending").hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_orders WHERE simulated=0").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aLostAmbiguousSubmissionIsOnlyDeclaredFailedAfterAWaitingPeriod() {
        String id = propose(10);
        broker.setMode(MockBrokerOrderProvider.Mode.AMBIGUOUS_ORDER_LOST);
        assertThat(live.approve(id, "admin").get("status")).isEqualTo("UNKNOWN_RECONCILE");
        assertThatThrownBy(() -> live.reconcile(id, "admin")).hasMessageContaining("wait at least 2 minutes");
        jdbc.sql("UPDATE live_order_approvals SET decided_at = ? WHERE id=?").params(java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(600)), id).update();
        assertThat(live.reconcile(id, "admin").get("status")).isEqualTo("FAILED");
        assertThat(broker.receivedRequests()).hasSize(1);
    }

    @Test
    void approvalRechecksAgainstTheBrokerReferenceToAvoidDuplicates() {
        String id = propose(10);
        // simulate: an earlier attempt reached the broker, our DB still says PENDING_APPROVAL
        var ref = (String) live.get(id).get("reference");
        broker.place(new com.aiinvestor.broker.BrokerDtos.BrokerOrderRequest(ref, "NSE", "CASH", "RELIANCE", "BUY", "LIMIT", "MIS", "DAY", 10, BigDecimal.TEN, null));
        assertThat(broker.receivedRequests()).hasSize(1);
        Map<String, Object> done = live.approve(id, "admin");
        assertThat(done.get("status")).isEqualTo("SUBMITTED");
        assertThat(broker.receivedRequests()).as("existing reference adopted, no second order").hasSize(1);
    }

    @Test
    void indicesAndNonBrokerMetadataCannotBeTraded() {
        Instrument idx = instruments.upsert(new Instrument(null, "NSE", "CASH", InstrumentType.INDEX, "NIFTY", null, "n", "NIFTY", null, null, null, 1, new BigDecimal("0.05"), "x", false, "GROWW"));
        assertThatThrownBy(() -> live.propose(new LiveOrderService.ProposeRequest(idx.id(), "BUY", "MARKET", 1, null, BigDecimal.ONE, BigDecimal.TEN, null), "admin")).hasMessageContaining("not directly tradable");
        Instrument mock = fx.equity("MOCKY");
        assertThatThrownBy(() -> live.propose(new LiveOrderService.ProposeRequest(mock.id(), "BUY", "MARKET", 1, null, BigDecimal.ONE, BigDecimal.TEN, null), "admin")).hasMessageContaining("not from the broker");
    }
}
