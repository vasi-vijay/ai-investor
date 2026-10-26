package com.aiinvestor.api;

import com.aiinvestor.persistence.SettingsRepository;
import com.aiinvestor.runtime.PaperTradingService;
import com.aiinvestor.security.RateLimitFilter;
import com.aiinvestor.support.AbstractDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.groww.access-token=SECRET-GROWW-TOKEN-VALUE", "app.groww.api-key=SECRET-GROWW-APIKEY", "app.groww.api-secret=SECRET-GROWW-APISECRET",
        "app.security.admin-password=correct-horse-battery"})
@AutoConfigureMockMvc
class DashboardApiTest extends AbstractDbTest {
    @Autowired MockMvc mvc;
    @Autowired PaperTradingService paper;
    @Autowired RateLimitFilter rateLimit;

    @BeforeEach
    void ensurePaperRun() { paper.init(); }      // AbstractDbTest empties trading_runs before each test

    private static final List<String> GET_ENDPOINTS = List.of("/api/overview", "/api/signals", "/api/signals?includeNoTrade=true", "/api/orders", "/api/executions",
            "/api/positions", "/api/positions?status=CLOSED", "/api/analytics/paper", "/api/analytics/daily", "/api/runs", "/api/risk/events", "/api/risk/limits",
            "/api/strategies", "/api/live/status", "/api/auth/me");

    @Test
    void everyApiEndpointRequiresAuthentication() throws Exception {
        for (String p : GET_ENDPOINTS) mvc.perform(get(p)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/risk/kill-switch").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"engaged\":true,\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());              // only health is public
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/ws/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardEndpointsReturnLabelledSimulatedData() throws Exception {
        for (String p : GET_ENDPOINTS) {
            mvc.perform(get(p).with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/overview").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.mode").value("PAPER")).andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.capital.initial_simulated_capital").exists()).andExpect(jsonPath("$.risk_utilization.daily_loss_limit").exists())
                .andExpect(jsonPath("$.groww.status").exists()).andExpect(jsonPath("$.claude.status").exists()).andExpect(jsonPath("$.market_session").exists());
        mvc.perform(get("/api/analytics/paper").with(user("viewer").roles("VIEWER")))
                .andExpect(jsonPath("$.run_mode").value("PAPER")).andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.label").value(org.hamcrest.Matchers.containsString("SIMULATED")));
    }

    @Test
    void noCredentialEverAppearsInAnyResponse() throws Exception {
        for (String p : GET_ENDPOINTS) {
            String body = mvc.perform(get(p).with(user("admin").roles("ADMIN", "VIEWER"))).andReturn().getResponse().getContentAsString();
            assertThat(body).as(p).doesNotContain("SECRET-GROWW").doesNotContain("correct-horse-battery").doesNotContain("$2a$").doesNotContain("access-token").doesNotContain("apiKey");
        }
        String limits = mvc.perform(get("/api/risk/limits").with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(limits).doesNotContain("groww");
    }

    @Test
    void stateChangingCallsNeedAdminRoleAndCsrfAndAreAudited() throws Exception {
        String body = "{\"engaged\":true,\"reason\":\"api test\"}";
        mvc.perform(post("/api/risk/kill-switch").with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());   // no CSRF token
        mvc.perform(post("/api/risk/kill-switch").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());   // wrong role
        mvc.perform(post("/api/risk/kill-switch").with(csrf()).with(user("admin").roles("ADMIN", "VIEWER")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kill_switch").value(true));
        assertThat(new SettingsRepository(jdbc).get("kill_switch", "?")).isEqualTo("ON");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action='KILL_SWITCH_ENGAGED' AND actor='admin'").query(Integer.class).single()).isEqualTo(1);
        mvc.perform(post("/api/risk/kill-switch").with(csrf()).with(user("admin").roles("ADMIN", "VIEWER")).contentType(MediaType.APPLICATION_JSON).content("{\"engaged\":false,\"reason\":\"done\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/risk/kill-switch").with(csrf()).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"engaged\":true}")).andExpect(status().isBadRequest());   // validation
    }

    @Test
    void liveEndpointsAreRefusedWhenLiveIsDisabledEvenForAdmins() throws Exception {
        mvc.perform(get("/api/live/status").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.live_order_placement_enabled").value(false)).andExpect(jsonPath("$.account_scope").value("LIVE"));
        mvc.perform(post("/api/live/orders/propose").with(csrf()).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrumentId\":1,\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":1,\"stopLoss\":1,\"target\":2}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("live_disabled"));
        mvc.perform(post("/api/live/authorize").with(csrf()).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"I AUTHORIZE LIVE TRADING WITH REAL MONEY\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/live/authorize").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void inputsAreValidatedAndErrorsDoNotLeakInternals() throws Exception {
        mvc.perform(get("/api/signals/not-a-uuid").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/positions?status=DROP_TABLE").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
        String body = mvc.perform(get("/api/signals/00000000-0000-0000-0000-000000000000").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception").doesNotContain("at com.");
        mvc.perform(post("/api/backtests").with(csrf()).with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"from\":\"2025-03-10\",\"to\":\"2025-03-03\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/backtests").with(csrf()).with(user("viewer").roles("VIEWER")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"from\":\"2025-03-03\",\"to\":\"2025-03-04\"}")).andExpect(status().isForbidden());
    }

    @Test
    void sessionLoginWorksWithCsrfAndRejectsBadCredentials() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isForbidden());                                         // CSRF protects login too
        mvc.perform(post("/api/auth/login").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
        MvcResult ok = mvc.perform(post("/api/auth/login").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true)).andReturn();
        MockHttpSession authed = (MockHttpSession) ok.getRequest().getSession(false);
        assertThat(authed).isNotNull();
        mvc.perform(get("/api/overview").session(authed)).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_logs WHERE action IN ('LOGIN_OK','LOGIN_FAILED')").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void theRateLimiterThrottlesBurstsAndLoginIsStricter() {
        int allowed = 0;
        for (int i = 0; i < 25; i++) if (rateLimit.allow("api:test", 10)) allowed++;
        assertThat(allowed).isBetween(10, 12);
        int login = 0;
        for (int i = 0; i < 30; i++) if (rateLimit.allow("login:test", 10)) login++;
        assertThat(login).isBetween(10, 12);
    }
}
