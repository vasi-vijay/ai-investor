package com.aiinvestor.broker;

import com.aiinvestor.broker.BrokerDtos.*;

import java.util.Optional;

/**
 * Real-order interface. In this application the only implementations that can reach a real broker are
 * instantiated when {@code app.live.enabled=true}, and every call is additionally guarded by LiveExecutionGate.
 */
public interface BrokerOrderProvider {
    BrokerOrderAck place(BrokerOrderRequest request);
    BrokerOrderAck modify(String brokerOrderId, String segment, String orderType, int quantity,
                          java.math.BigDecimal price, java.math.BigDecimal triggerPrice);
    BrokerOrderAck cancel(String brokerOrderId, String segment);
    BrokerOrder status(String brokerOrderId, String segment);
    Optional<BrokerOrder> statusByReference(String reference, String segment);
    String sourceName();
}
