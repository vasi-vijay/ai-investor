package com.aiinvestor.mock;

import com.aiinvestor.broker.BrokerDtos.*;
import com.aiinvestor.broker.BrokerException;
import com.aiinvestor.broker.BrokerOrderProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fake broker for tests and local runs. Never touches the network. Failure injection supports tests of
 * rejection, auth expiry and the ambiguous-submission path (order stored, but the caller sees a timeout).
 */
public class MockBrokerOrderProvider implements BrokerOrderProvider {
    public enum Mode { NORMAL, REJECT, AUTH_EXPIRED, AMBIGUOUS_ORDER_EXISTS, AMBIGUOUS_ORDER_LOST }

    private final Map<String, BrokerOrder> orders = new ConcurrentHashMap<>();
    private final List<BrokerOrderRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(1000);
    private volatile Mode mode = Mode.NORMAL;

    public void setMode(Mode m) { this.mode = m; }

    /** Forget all received requests and stored orders (test isolation). */
    public void reset() { orders.clear(); requests.clear(); mode = Mode.NORMAL; }
    public List<BrokerOrderRequest> receivedRequests() { return List.copyOf(requests); }

    @Override public BrokerOrderAck place(BrokerOrderRequest r) {
        requests.add(r);
        switch (mode) {
            case AUTH_EXPIRED -> throw new BrokerException.Auth("mock: token expired");
            case REJECT -> throw new BrokerException.Rejected("mock: rejected");
            case AMBIGUOUS_ORDER_LOST -> throw new BrokerException.Ambiguous("mock: timeout (order not received)", null);
            case AMBIGUOUS_ORDER_EXISTS -> { store(r); throw new BrokerException.Ambiguous("mock: timeout (order received)", null); }
            default -> { }
        }
        BrokerOrder o = store(r);
        return new BrokerOrderAck(o.brokerOrderId(), o.status(), r.reference(), "ok");
    }

    private BrokerOrder store(BrokerOrderRequest r) {
        String id = "MOCK-" + seq.incrementAndGet();
        BrokerOrder o = new BrokerOrder(id, r.reference(), r.tradingSymbol(), r.segment(), r.side(), r.orderType(),
                r.quantity(), 0, null, "OPEN", Instant.now());
        orders.put(id, o);
        return o;
    }

    @Override public BrokerOrderAck modify(String id, String segment, String type, int qty, BigDecimal price, BigDecimal trig) {
        BrokerOrder o = orders.get(id);
        if (o == null) throw new BrokerException.Rejected("mock: unknown order");
        return new BrokerOrderAck(id, o.status(), o.reference(), "modified");
    }

    @Override public BrokerOrderAck cancel(String id, String segment) {
        BrokerOrder o = orders.get(id);
        if (o == null) throw new BrokerException.Rejected("mock: unknown order");
        orders.put(id, new BrokerOrder(id, o.reference(), o.tradingSymbol(), o.segment(), o.side(), o.orderType(),
                o.quantity(), o.filledQuantity(), o.avgFillPrice(), "CANCELLED", Instant.now()));
        return new BrokerOrderAck(id, "CANCELLED", o.reference(), "cancelled");
    }

    @Override public BrokerOrder status(String id, String segment) {
        BrokerOrder o = orders.get(id);
        if (o == null) throw new BrokerException.Rejected("mock: unknown order");
        return o;
    }

    @Override public Optional<BrokerOrder> statusByReference(String reference, String segment) {
        return orders.values().stream().filter(o -> reference.equals(o.reference())).findFirst();
    }

    @Override public String sourceName() { return "MOCK"; }
}
