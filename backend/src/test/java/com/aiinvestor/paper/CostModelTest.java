package com.aiinvestor.paper;

import com.aiinvestor.config.AppProperties;
import com.aiinvestor.domain.Enums.InstrumentType;
import com.aiinvestor.domain.Enums.Side;
import com.aiinvestor.support.TestProps;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostModelTest {
    private final CostModel model = new CostModel(TestProps.defaults().costs());

    @Test
    void equityBuyCostsMatchHandCalculation() {
        // turnover 100,000: brokerage min(20, 0.1% = 100) = 20; exch 2.97; sebi 0.1; stamp 3; gst 18% of (20+2.97+0.1)
        CostModel.Costs c = model.forFill(InstrumentType.EQUITY, Side.BUY, new BigDecimal("100"), 1000);
        assertThat(c.brokerage()).isEqualByComparingTo("20");
        assertThat(c.statutory().doubleValue()).isCloseTo(2.97 + 0.1 + 3.0 + 0.18 * (20 + 2.97 + 0.1), within(0.001));
        assertThat(c.total()).isEqualByComparingTo(c.brokerage().add(c.statutory()));
    }

    @Test
    void equitySellAddsSttButNoStampDuty() {
        CostModel.Costs buy = model.forFill(InstrumentType.EQUITY, Side.BUY, new BigDecimal("100"), 1000);
        CostModel.Costs sell = model.forFill(InstrumentType.EQUITY, Side.SELL, new BigDecimal("100"), 1000);
        // sell: + STT 0.025% = 25, - stamp 3
        assertThat(sell.total().subtract(buy.total()).doubleValue()).isCloseTo(25.0 - 3.0, within(0.001));
    }

    @Test
    void brokerageIsCappedByPercentageOnSmallOrders() {
        CostModel.Costs c = model.forFill(InstrumentType.OPTION, Side.BUY, new BigDecimal("10"), 75);   // turnover 750 -> 0.1% = 0.75
        assertThat(c.brokerage()).isEqualByComparingTo("0.75");
    }

    @Test
    void optionSellUsesPremiumBasedStt() {
        CostModel.Costs sell = model.forFill(InstrumentType.OPTION, Side.SELL, new BigDecimal("100"), 75);   // turnover 7,500
        double stt = 7500 * 0.001, exch = 7500 * 0.0003503, sebi = 7500 * 0.000001, brokerage = 7.5, gst = 0.18 * (brokerage + exch + sebi);
        assertThat(sell.total().doubleValue()).isCloseTo(brokerage + stt + exch + sebi + gst, within(0.001));
    }

    @Test
    void roundTripEstimateIsBuyPlusSell() {
        BigDecimal rt = model.roundTripEstimate(InstrumentType.EQUITY, new BigDecimal("100"), 1000);
        assertThat(rt).isEqualByComparingTo(model.forFill(InstrumentType.EQUITY, Side.BUY, new BigDecimal("100"), 1000).total()
                .add(model.forFill(InstrumentType.EQUITY, Side.SELL, new BigDecimal("100"), 1000).total()));
    }

    @Test
    void costsAreConfigurable() {
        AppProperties.Costs free = TestProps.with(java.util.Map.of("app.costs.brokerage-flat-per-order", "0", "app.costs.stt-equity-sell-pct", "0",
                "app.costs.exchange-txn-equity-pct", "0", "app.costs.sebi-pct", "0", "app.costs.stamp-buy-pct", "0")).costs();
        assertThat(new CostModel(free).forFill(InstrumentType.EQUITY, Side.SELL, new BigDecimal("100"), 1000).total()).isEqualByComparingTo("0");
    }
}
