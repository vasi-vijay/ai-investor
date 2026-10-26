package com.aiinvestor.config;

import com.aiinvestor.ai.AiAnalyst;
import com.aiinvestor.ai.AnthropicSdkGateway;
import com.aiinvestor.ai.ClaudeAnalyst;
import com.aiinvestor.ai.HeuristicMockAnalyst;
import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerOrderProvider;
import com.aiinvestor.market.HistoricalDataProvider;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.market.MarketDataProvider;
import com.aiinvestor.market.MarketSession;
import com.aiinvestor.market.SettableClock;
import com.aiinvestor.market.SystemTradingClock;
import com.aiinvestor.market.TradingClock;
import com.aiinvestor.mock.MockBrokerAccountProvider;
import com.aiinvestor.mock.MockBrokerOrderProvider;
import com.aiinvestor.mock.MockInstrumentMetadataProvider;
import com.aiinvestor.mock.MockMarketDataProvider;
import com.aiinvestor.mock.SyntheticMarket;
import com.aiinvestor.paper.CostModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;

@Configuration
public class AppConfig {

    @Bean MarketSession marketSession(AppProperties p) { return new MarketSession(p.market()); }

    @Bean CostModel costModel(AppProperties p) { return new CostModel(p.costs()); }

    /** Replay (mock + replayEnabled) uses a settable clock stepped by the scheduler; otherwise the system clock. */
    @Bean TradingClock tradingClock(AppProperties p, MarketSession session) {
        boolean replay = "MOCK".equalsIgnoreCase(p.data().source()) && p.mock().replayEnabled();
        if (!replay) return new SystemTradingClock();
        LocalDate start = p.mock().replayStartDate();
        if (start == null) start = session.previousTradingDay(LocalDate.now(session.zone()));
        while (!session.isTradingDay(start)) start = start.plusDays(1);
        return new SettableClock(session.openInstant(start));
    }

    // ------------------------------------------------------------------ MOCK data source (default)

    @Bean @ConditionalOnProperty(name = "app.data.source", havingValue = "MOCK", matchIfMissing = true)
    SyntheticMarket syntheticMarket(AppProperties p, MarketSession s) {
        return new SyntheticMarket(s, p.mock().basePrices(), p.mock().lotSizes());
    }

    @Bean @ConditionalOnProperty(name = "app.data.source", havingValue = "MOCK", matchIfMissing = true)
    MockMarketDataProvider mockMarketData(SyntheticMarket m, MarketSession s, TradingClock c) {
        return new MockMarketDataProvider(m, s, c);
    }

    @Bean @ConditionalOnProperty(name = "app.data.source", havingValue = "MOCK", matchIfMissing = true)
    InstrumentMetadataProvider mockMetadata(SyntheticMarket m, MarketSession s, AppProperties p) {
        return new MockInstrumentMetadataProvider(m, s, p);
    }

    @Bean @ConditionalOnProperty(name = "app.data.source", havingValue = "MOCK", matchIfMissing = true)
    BrokerAccountProvider mockAccount(AppProperties p) { return new MockBrokerAccountProvider(p.paper().initialCapital()); }

    /**
     * Default order provider is the in-memory mock. A real Groww order provider exists only when BOTH
     * app.groww.enabled and app.live.enabled are true (see GrowwConfig); even then LiveExecutionGate guards every call.
     */
    @Bean @ConditionalOnProperty(name = "app.live.enabled", havingValue = "false", matchIfMissing = true)
    BrokerOrderProvider mockOrders() { return new MockBrokerOrderProvider(); }

    // ------------------------------------------------------------------ AI analyst

    @Bean AiAnalyst aiAnalyst(AppProperties p, ObjectMapper mapper) {
        if ("CLAUDE".equalsIgnoreCase(p.ai().provider())) return new ClaudeAnalyst(new AnthropicSdkGateway(p.ai()));
        return new HeuristicMockAnalyst(mapper);
    }
}
