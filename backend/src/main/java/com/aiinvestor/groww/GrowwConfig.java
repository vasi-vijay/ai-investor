package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerOrderProvider;
import com.aiinvestor.config.AppProperties;
import com.aiinvestor.market.InstrumentMetadataProvider;
import com.aiinvestor.market.MarketSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Groww beans, active only with app.data.source=GROWW. The order provider additionally needs BOTH app.groww.enabled and
 * app.live.enabled — in the default configuration no bean able to reach a real order endpoint exists.
 */
@Configuration
@ConditionalOnProperty(name = "app.data.source", havingValue = "GROWW")
public class GrowwConfig {

    @Bean GrowwAuthManager growwAuth(AppProperties p, ObjectMapper m) { return new GrowwAuthManager(p.groww(), m); }

    @Bean GrowwHttp growwHttp(AppProperties p, ObjectMapper m, GrowwAuthManager auth) {
        GrowwHttp http = new GrowwHttp(p.groww(), m, auth::token);
        http.onUnauthorized(auth::markExpired);
        auth.attach(http);
        return http;
    }

    @Bean GrowwMarketData growwMarketData(GrowwHttp http, GrowwAuthManager auth, MarketSession s) { return new GrowwMarketData(http, auth, s); }

    @Bean InstrumentMetadataProvider growwMetadata(AppProperties p) { return new GrowwInstrumentMaster(p); }

    @Bean BrokerAccountProvider growwAccount(GrowwHttp http, GrowwAuthManager auth) { return new GrowwAccount(http, auth); }

    @Bean
    @ConditionalOnExpression("${app.groww.enabled:false} and ${app.live.enabled:false}")
    BrokerOrderProvider growwOrders(GrowwHttp http) { return new GrowwOrders(http); }
}
