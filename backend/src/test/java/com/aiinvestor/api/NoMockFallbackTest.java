package com.aiinvestor.api;

import com.aiinvestor.ai.AiAnalyst;
import com.aiinvestor.ai.ClaudeAnalyst;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.runtime.PaperTradingService;
import com.aiinvestor.support.AbstractDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production-default wiring with NO credentials: real integrations selected, nothing silently mocked. */
@SpringBootTest(properties = {"app.data.source=GROWW", "app.ai.provider=CLAUDE", "app.security.admin-password=correct-horse-battery"})
@AutoConfigureMockMvc
class NoMockFallbackTest extends AbstractDbTest {
    @Autowired MockMvc mvc;
    @Autowired PaperTradingService paper;
    @Autowired AiAnalyst analyst;
    @Autowired(required = false) MarketDataProvider quotes;
    @Autowired IntegrationService integrations;
    @Autowired com.aiinvestor.persistence.RunRepository runs;

    @Test
    void missingCredentialsGiveSetupStatesNotMocks() throws Exception {
        assertThat(analyst).isInstanceOf(ClaudeAnalyst.class);
        assertThat(quotes).isNotNull();
        assertThat(quotes.sourceName()).isEqualTo("GROWW");
        assertThat(integrations.state()).isEqualTo(IntegrationService.OperatingState.DISCONNECTED);
        assertThat(integrations.marketDataBlockReason()).isNotNull();
        mvc.perform(get("/api/broker/status").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operating_state").value("DISCONNECTED"))
                .andExpect(jsonPath("$.data.simulated").value(false))
                .andExpect(jsonPath("$.data.blocked_reason").isNotEmpty())
                .andExpect(jsonPath("$.live_trading_enabled_by_connection").value(false));
        mvc.perform(get("/api/market/quote").param("symbol", "RELIANCE").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unavailable").isNotEmpty()).andExpect(jsonPath("$.last").doesNotExist());
        mvc.perform(get("/api/broker/funds").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    }

    @Test
    void cycleIsBlockedWithoutRealData() {
        paper.init();
        paper.runCycle();
        assertThat(paper.blockedReason()).isNotNull();
    }

    @Test
    void connectNeverEnablesLiveAndViewerCannotConnect() throws Exception {
        mvc.perform(post("/api/broker/connect").with(csrf()).with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/broker/connect").with(csrf()).with(user("admin").roles("ADMIN", "VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.live_trading_enabled_by_connection").value(false))
                .andExpect(jsonPath("$.operating_state").value("DISCONNECTED"));
    }

    @Test
    void paperRunsAreNeverSharedAcrossDataSources() {
        var mock = runs.getOrCreatePaperRun("shared-name", com.aiinvestor.domain.Enums.AiMode.AI_SHADOW, "MOCK", new java.math.BigDecimal("100000"));
        var real = runs.getOrCreatePaperRun("shared-name", com.aiinvestor.domain.Enums.AiMode.AI_SHADOW, "GROWW", new java.math.BigDecimal("100000"));
        assertThat(real.id()).isNotEqualTo(mock.id());
        assertThat(runs.getOrCreatePaperRun("shared-name", com.aiinvestor.domain.Enums.AiMode.AI_SHADOW, "GROWW", new java.math.BigDecimal("100000")).id()).isEqualTo(real.id());
    }
}
