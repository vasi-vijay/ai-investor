package com.aiinvestor.paper;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-fill transaction cost estimate (brokerage, STT, exchange charges, SEBI fee, stamp duty, GST).
 * All rates come from configuration and are APPROXIMATIONS that must be verified against current schedules.
 */
public class CostModel {
    public record Costs(BigDecimal brokerage, BigDecimal statutory, BigDecimal total) {
        public static final Costs ZERO = new Costs(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final AppProperties.Costs c;

    public CostModel(AppProperties.Costs c) { this.c = c; }

    public Costs forFill(InstrumentType type, Side side, BigDecimal price, int qty) {
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(qty));
        boolean option = type == InstrumentType.OPTION;
        BigDecimal brokerage = c.brokerageFlatPerOrder().min(turnover.multiply(bd(c.brokerageMaxPct())));
        BigDecimal stt = side == Side.SELL ? turnover.multiply(bd(option ? c.sttOptionSellPct() : c.sttEquitySellPct())) : BigDecimal.ZERO;
        BigDecimal exch = turnover.multiply(bd(option ? c.exchangeTxnOptionPct() : c.exchangeTxnEquityPct()));
        BigDecimal sebi = turnover.multiply(bd(c.sebiPct()));
        BigDecimal stamp = side == Side.BUY ? turnover.multiply(bd(c.stampBuyPct())) : BigDecimal.ZERO;
        BigDecimal gst = brokerage.add(exch).add(sebi).multiply(bd(c.gstPct()));
        BigDecimal statutory = stt.add(exch).add(sebi).add(stamp).add(gst);
        return new Costs(r(brokerage), r(statutory), r(brokerage.add(statutory)));
    }

    /** Estimated entry+exit cost for a round trip at the same price (used by risk reward-vs-cost checks). */
    public BigDecimal roundTripEstimate(InstrumentType type, BigDecimal price, int qty) {
        return forFill(type, Side.BUY, price, qty).total().add(forFill(type, Side.SELL, price, qty).total());
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
    private static BigDecimal r(BigDecimal v) { return v.setScale(4, RoundingMode.HALF_EVEN); }
}
