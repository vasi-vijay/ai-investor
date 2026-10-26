package com.aiinvestor.ai;

import com.aiinvestor.support.TestProps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClaudeGatewayTest {
    @Test
    void withoutAnApiKeyInTheEnvironmentTheGatewayIsInertAndMakesNoNetworkCall() {
        assumeTrue(System.getenv("ANTHROPIC_API_KEY") == null || System.getenv("ANTHROPIC_API_KEY").isBlank());
        AnthropicSdkGateway gw = new AnthropicSdkGateway(TestProps.defaults().ai());
        assertThat(gw.isConfigured()).isFalse();
        assertThatThrownBy(() -> gw.complete("system", "user")).isInstanceOfSatisfying(AiException.class, e -> assertThat(e.kind()).isEqualTo(AiException.Kind.NOT_CONFIGURED));
    }

    @Test
    void theModelIdentifierAndLimitsAreConfigurableNotHardcoded() {
        var ai = TestProps.with(java.util.Map.of("app.ai.model", "some-future-model", "app.ai.timeout-seconds", "7", "app.ai.max-output-tokens", "1234", "app.ai.effort", "low")).ai();
        assertThat(ai.model()).isEqualTo("some-future-model");
        assertThat(ai.timeoutSeconds()).isEqualTo(7);
        assertThat(ai.maxOutputTokens()).isEqualTo(1234);
        assertThat(new AnthropicSdkGateway(ai).modelId()).isEqualTo("some-future-model");
    }

    @Test
    void theWireSchemaDoesNotLetTheModelNameFieldsThatControlExecution() {
        var names = java.util.Arrays.stream(ClaudeAssessmentWire.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertThat(names).doesNotContain("order", "quantity", "execute", "risk_limit", "mode", "approve", "live");
    }
}
