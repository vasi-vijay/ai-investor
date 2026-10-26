package com.aiinvestor.support;

import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerOrderProvider;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.live.LiveExecutionGate;
import com.aiinvestor.mock.MockBrokerAccountProvider;
import com.aiinvestor.mock.MockBrokerOrderProvider;
import com.aiinvestor.persistence.AuditRepository;
import com.aiinvestor.persistence.SettingsRepository;
import com.aiinvestor.risk.KillSwitch;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.aiinvestor.groww.GrowwAuthManager;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lets tests exercise the live WORKFLOW without any real broker: the order provider is the in-memory mock, the account
 * provider has adjustable funds, and the gate treats every external-dependency check as satisfied while keeping the
 * real kill-switch and audit checks.
 */
@TestConfiguration
public class LiveTestConfig {
    public static class AdjustableAccount extends MockBrokerAccountProvider {
        public volatile BigDecimal funds = new BigDecimal("1000000");
        public AdjustableAccount() { super(new BigDecimal("1000000")); }
        @Override public com.aiinvestor.broker.BrokerDtos.AccountFunds funds() { return new com.aiinvestor.broker.BrokerDtos.AccountFunds(funds, BigDecimal.ZERO, funds, "MOCK"); }
    }

    @Bean MockBrokerOrderProvider liveTestOrders() { return new MockBrokerOrderProvider(); }

    @Bean @Primary AdjustableAccount liveTestAccount() { return new AdjustableAccount(); }

    @Bean @Primary LiveExecutionGate testGate(AppProperties p, SettingsRepository s, KillSwitch k, AuditRepository a, ObjectProvider<GrowwAuthManager> auth) {
        return new LiveExecutionGate(p, s, k, a, auth) {
            @Override public List<Check> evaluate() {
                return super.evaluate().stream()
                        .map(c -> c.name().equals("8_KILL_SWITCH_OFF") || c.name().equals("8_AUDIT_LOG_WRITABLE") ? c : new Check(c.name(), true, "forced ok in test")).toList();
            }
        };
    }
}
