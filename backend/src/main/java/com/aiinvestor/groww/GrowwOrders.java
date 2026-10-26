package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerDtos.*;
import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.broker.BrokerOrderProvider;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REAL order placement/modification/cancellation against Groww (POST /order/create, /order/modify, /order/cancel; GET status).
 * This bean is only created when app.live.enabled=true AND app.groww.enabled=true, and LiveOrderService additionally guards
 * every call with LiveExecutionGate. Request bodies follow the official `growwapi` SDK 1.5.0. A timeout or 5xx on a mutating
 * call throws {@link BrokerException.Ambiguous}: callers MUST reconcile by order_reference_id before any retry.
 */
public class GrowwOrders implements BrokerOrderProvider {
    private final GrowwHttp http;

    public GrowwOrders(GrowwHttp http) { this.http = http; }

    @Override public BrokerOrderAck place(BrokerOrderRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trading_symbol", r.tradingSymbol());
        body.put("quantity", r.quantity());
        body.put("price", r.price() == null ? 0.0 : r.price());
        body.put("trigger_price", r.triggerPrice());
        body.put("validity", r.validity());
        body.put("exchange", r.exchange());
        body.put("segment", r.segment());
        body.put("product", r.product());
        body.put("order_type", r.orderType());
        body.put("transaction_type", r.side());
        body.put("order_reference_id", r.reference());
        JsonNode p = http.post("/order/create", body, true);
        return ack(p, r.reference());
    }

    @Override public BrokerOrderAck modify(String id, String segment, String orderType, int qty, BigDecimal price, BigDecimal trigger) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("quantity", qty);
        body.put("price", price);
        body.put("trigger_price", trigger);
        body.put("groww_order_id", id);
        body.put("order_type", orderType);
        body.put("segment", segment);
        return ack(http.post("/order/modify", body, true), null);
    }

    @Override public BrokerOrderAck cancel(String id, String segment) {
        return ack(http.post("/order/cancel", Map.of("segment", segment, "groww_order_id", id), true), null);
    }

    @Override public BrokerOrder status(String id, String segment) {
        return order(http.get("/order/status/" + id, Map.of("segment", segment)), segment, id);
    }

    @Override public Optional<BrokerOrder> statusByReference(String reference, String segment) {
        try {
            return Optional.of(order(http.get("/order/status/reference/" + reference, Map.of("segment", segment)), segment, null));
        } catch (BrokerException.Rejected e) {
            return Optional.empty();       // 400/404: no such order → definitively not placed
        }
    }

    @Override public String sourceName() { return "GROWW"; }

    private static BrokerOrderAck ack(JsonNode p, String ref) {
        return new BrokerOrderAck(GrowwJson.text(p, "groww_order_id"), GrowwJson.text(p, "order_status", "status"),
                GrowwJson.text(p, "order_reference_id") != null ? GrowwJson.text(p, "order_reference_id") : ref, GrowwJson.text(p, "remark", "message"));
    }

    static BrokerOrder order(JsonNode n, String segment) { return order(n, segment, null); }

    static BrokerOrder order(JsonNode n, String segment, String idHint) {
        String id = GrowwJson.text(n, "groww_order_id");
        return new BrokerOrder(id == null ? idHint : id, GrowwJson.text(n, "order_reference_id"), GrowwJson.text(n, "trading_symbol"), segment,
                GrowwJson.text(n, "transaction_type"), GrowwJson.text(n, "order_type"), GrowwJson.integer(n, 0, "quantity"),
                GrowwJson.integer(n, 0, "filled_quantity"), GrowwJson.dec(n, "average_fill_price", "avg_fill_price"),
                GrowwJson.text(n, "order_status", "status"), Instant.now());
    }
}
