package com.aiinvestor.live;

import com.aiinvestor.broker.BrokerOrderProvider;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.groww.GrowwOrders;
import com.aiinvestor.mock.MockBrokerOrderProvider;
import com.aiinvestor.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves that a default start-up cannot place a real order, by construction and by gate. */
class LiveDisabledByDefaultTest extends AbstractDbTest {
    @Autowired ApplicationContext ctx;
    @Autowired AppProperties props;
    @Autowired LiveExecutionGate gate;
    @Autowired LiveOrderService live;
    @Autowired BrokerOrderProvider orders;

    @Test
    void defaultConfigurationDisablesLiveAndDoesNotAttestCompliance() {
        assertThat(props.live().enabled()).isFalse();
        assertThat(props.live().complianceConfirmed()).isFalse();
        assertThat(props.live().requireManualApproval()).isTrue();
        assertThat(props.groww().enabled()).isFalse();
        assertThat(props.data().source()).isEqualTo("MOCK");
    }

    @Test
    void noBeanThatCanReachARealOrderEndpointExists() {
        assertThat(ctx.getBeansOfType(GrowwOrders.class)).isEmpty();
        assertThat(orders).isInstanceOf(MockBrokerOrderProvider.class);
        assertThat(ctx.getBeansOfType(BrokerOrderProvider.class)).hasSize(1);
    }

    @Test
    void theGateFailsAndEachFailureIsExplained() {
        assertThat(gate.allowed()).isFalse();
        var checks = gate.evaluate();
        assertThat(checks).filteredOn(c -> !c.ok()).extracting(LiveExecutionGate.Check::name)
                .contains("1_CONFIG_ENABLED", "2_USER_AUTHORISED", "3_BROKER_AUTH", "4_CAPABILITIES_VERIFIED", "5_COMPLIANCE_ATTESTED", "6_REAL_MARKET_DATA");
        assertThat(checks).allSatisfy(c -> assertThat(c.detail()).isNotBlank());
        assertThatThrownBy(gate::assertAllowed).isInstanceOf(LiveExecutionGate.LiveDisabledException.class).hasMessageContaining("live trading is disabled");
    }

    @Test
    void everyLiveEntryPointIsRefused() {
        assertThatThrownBy(() -> live.propose(new LiveOrderService.ProposeRequest(1, "BUY", "LIMIT", 1, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, null), "admin"))
                .isInstanceOf(LiveExecutionGate.LiveDisabledException.class);
        assertThatThrownBy(() -> live.approve("00000000-0000-0000-0000-000000000000", "admin")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> live.authorize("admin", LiveOrderService.AUTH_PHRASE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("not enabled in configuration");
        assertThat(((MockBrokerOrderProvider) orders).receivedRequests()).isEmpty();
        assertThat(jdbc.sql("SELECT setting_value FROM application_settings WHERE setting_key='live_authorized'").query(String.class).single()).isEqualTo("NO");
    }

    @Test
    void attestationAndAuthorisationNeedDeliberateInput() {
        assertThatThrownBy(() -> live.attestCompliance("admin", "ok")).isInstanceOf(IllegalArgumentException.class);
        assertThat(live.status().get("live_order_placement_enabled")).isEqualTo(false);
    }
}
