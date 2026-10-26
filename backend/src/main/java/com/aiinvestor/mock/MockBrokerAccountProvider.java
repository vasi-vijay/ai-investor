package com.aiinvestor.mock;

import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerDtos.*;

import java.math.BigDecimal;
import java.util.List;

/** Static funds, no positions. Lets the app run without a Groww account. */
public class MockBrokerAccountProvider implements BrokerAccountProvider {
    private final BigDecimal funds;
    public MockBrokerAccountProvider(BigDecimal funds) { this.funds = funds; }
    @Override public AccountFunds funds() { return new AccountFunds(funds, BigDecimal.ZERO, funds, "MOCK"); }
    @Override public List<BrokerPosition> positions() { return List.of(); }
    @Override public List<BrokerOrder> orders() { return List.of(); }
    @Override public List<BrokerTrade> tradesForOrder(String brokerOrderId, String segment) { return List.of(); }
    @Override public boolean isConnected() { return true; }
    @Override public String sourceName() { return "MOCK"; }
}
