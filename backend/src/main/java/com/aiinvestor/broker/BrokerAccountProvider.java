package com.aiinvestor.broker;

import com.aiinvestor.broker.BrokerDtos.*;

import java.util.List;

public interface BrokerAccountProvider {
    AccountFunds funds();
    List<BrokerPosition> positions();
    List<BrokerOrder> orders();
    List<BrokerTrade> tradesForOrder(String brokerOrderId, String segment);
    boolean isConnected();
    String sourceName();
}
