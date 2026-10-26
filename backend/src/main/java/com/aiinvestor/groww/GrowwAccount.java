package com.aiinvestor.groww;

import com.aiinvestor.broker.BrokerAccountProvider;
import com.aiinvestor.broker.BrokerDtos.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only account access: margins, positions, orders, trades. Payload field names beyond the request side are partly UNVERIFIED. */
public class GrowwAccount implements BrokerAccountProvider {
    private final GrowwHttp http;
    private final GrowwAuthManager auth;

    public GrowwAccount(GrowwHttp http, GrowwAuthManager auth) {
        this.http = http;
        this.auth = auth;
    }

    @Override public AccountFunds funds() {
        JsonNode p = http.get("/margins/detail/user", Map.of());
        BigDecimal avail = GrowwJson.dec(p, "clear_cash", "available_cash", "available_margin");
        BigDecimal used = GrowwJson.dec(p, "net_margin_used", "margin_used");
        BigDecimal total = GrowwJson.dec(p, "total_balance", "net_margin");
        return new AccountFunds(avail, used, total, "GROWW");
    }

    @Override public List<BrokerPosition> positions() {
        List<BrokerPosition> out = new ArrayList<>();
        for (String seg : List.of("CASH", "FNO")) {
            JsonNode p = http.get("/positions/user", Map.of("segment", seg));
            JsonNode arr = p.has("positions") ? p.get("positions") : p;
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    out.add(new BrokerPosition(GrowwJson.text(n, "trading_symbol"), seg, GrowwJson.text(n, "product"),
                            GrowwJson.integer(n, 0, "quantity", "net_quantity"), GrowwJson.dec(n, "net_price", "average_price"), GrowwJson.dec(n, "realised_pnl", "realized_pnl")));
                }
            }
        }
        return out;
    }

    @Override public List<BrokerOrder> orders() {
        List<BrokerOrder> out = new ArrayList<>();
        for (String seg : List.of("CASH", "FNO")) {
            int page = 0;
            while (page < 20) {
                JsonNode p = http.get("/order/list", Map.of("segment", seg, "page", String.valueOf(page)));
                JsonNode arr = p.has("order_list") ? p.get("order_list") : p;
                if (arr == null || !arr.isArray() || arr.isEmpty()) break;
                for (JsonNode n : arr) out.add(GrowwOrders.order(n, seg));
                if (arr.size() < 10) break;
                page++;
            }
        }
        return out;
    }

    @Override public List<BrokerTrade> tradesForOrder(String brokerOrderId, String segment) {
        JsonNode p = http.get("/order/trades/" + brokerOrderId, Map.of("segment", segment, "page", "0", "page_size", "50"));
        JsonNode arr = p.has("trade_list") ? p.get("trade_list") : p;
        List<BrokerTrade> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) out.add(new BrokerTrade(GrowwJson.text(n, "groww_trade_id", "trade_id"), brokerOrderId,
                    GrowwJson.integer(n, 0, "quantity"), GrowwJson.dec(n, "price"), Instant.now()));
        }
        return out;
    }

    @Override public boolean isConnected() { return auth.state() == GrowwAuthManager.State.OK; }
    @Override public String sourceName() { return "GROWW"; }
}
