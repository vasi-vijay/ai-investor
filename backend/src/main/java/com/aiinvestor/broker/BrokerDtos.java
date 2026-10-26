package com.aiinvestor.broker;

import java.math.BigDecimal;
import java.time.Instant;

/** Provider-neutral broker DTOs. */
public final class BrokerDtos {
    private BrokerDtos() {}

    public record AccountFunds(BigDecimal availableCash, BigDecimal usedMargin, BigDecimal totalBalance, String source) {}

    public record BrokerPosition(String tradingSymbol, String segment, String product, int netQuantity,
                                 BigDecimal avgPrice, BigDecimal realizedPnl) {}

    public record BrokerOrder(String brokerOrderId, String reference, String tradingSymbol, String segment,
                              String side, String orderType, int quantity, int filledQuantity,
                              BigDecimal avgFillPrice, String status, Instant updatedAt) {
        public boolean isTerminal() {
            return switch (status == null ? "" : status.toUpperCase()) {
                case "EXECUTED", "COMPLETED", "FILLED", "CANCELLED", "REJECTED", "FAILED" -> true;
                default -> false;
            };
        }
    }

    public record BrokerTrade(String brokerTradeId, String brokerOrderId, int quantity, BigDecimal price, Instant tradeTime) {}

    /** Order request as sent to a broker. {@code reference} is the idempotency/audit tag. */
    public record BrokerOrderRequest(String reference, String exchange, String segment, String tradingSymbol,
                                     String side, String orderType, String product, String validity, int quantity,
                                     BigDecimal price, BigDecimal triggerPrice) {}

    public record BrokerOrderAck(String brokerOrderId, String status, String reference, String message) {}
}
