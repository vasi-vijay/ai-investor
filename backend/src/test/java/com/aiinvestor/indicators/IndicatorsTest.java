package com.aiinvestor.indicators;

import com.aiinvestor.domain.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class IndicatorsTest {

    @Test
    void emaIsSeededWithSmaAndMatchesHandCalculation() {
        double[] v = {1, 2, 3, 4, 5, 6};
        double[] e = Indicators.ema(v, 3);
        assertThat(e[0]).isNaN();
        assertThat(e[1]).isNaN();
        assertThat(e[2]).isEqualTo(2.0);                  // SMA(1,2,3)
        assertThat(e[3]).isCloseTo(3.0, within(1e-12));   // 4*0.5 + 2*0.5
        assertThat(e[4]).isCloseTo(4.0, within(1e-12));
        assertThat(e[5]).isCloseTo(5.0, within(1e-12));
    }

    @Test
    void emaReturnsNaNWhenHistoryIsInsufficient() {
        assertThat(java.util.Arrays.stream(Indicators.ema(new double[]{1, 2}, 5)).allMatch(Double::isNaN)).isTrue();
    }

    @Test
    void rsiOfMonotonicRiseIs100AndFallIsZero() {
        double[] up = new double[20], down = new double[20];
        for (int i = 0; i < 20; i++) { up[i] = 100 + i; down[i] = 100 - i; }
        assertThat(Indicators.last(Indicators.rsi(up, 14))).isEqualTo(100.0);
        assertThat(Indicators.last(Indicators.rsi(down, 14))).isEqualTo(0.0);
    }

    @Test
    void rsiMatchesWilderReferenceExample() {
        // Classic 14-period Wilder example data (StockCharts RSI worksheet), first RSI value ~70.46
        double[] closes = {44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28};
        double[] r = Indicators.rsi(closes, 14);
        assertThat(r[14]).isCloseTo(70.46, within(0.1));
        assertThat(r[13]).isNaN();
    }

    @Test
    void atrOfConstantRangeEqualsRange() {
        List<Candle> c = new ArrayList<>();
        Instant t = Instant.parse("2025-03-03T03:45:00Z");
        for (int i = 0; i < 30; i++) c.add(new Candle(t.plusSeconds(300L * i), 100, 101, 99, 100, 10));
        assertThat(Indicators.last(Indicators.atr(c, 14))).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void vwapIsVolumeWeighted() {
        Instant t = Instant.parse("2025-03-03T03:45:00Z");
        List<Candle> c = List.of(new Candle(t, 10, 10, 10, 10, 100), new Candle(t.plusSeconds(300), 20, 20, 20, 20, 300));
        Indicators.Vwap v = Indicators.vwap(c);
        assertThat(v.volumeWeighted()).isTrue();
        assertThat(v.values()[1]).isCloseTo((10 * 100 + 20 * 300) / 400.0, within(1e-12));
    }

    @Test
    void vwapFallsBackToEqualWeightProxyWithoutVolumeAndSaysSo() {
        Instant t = Instant.parse("2025-03-03T03:45:00Z");
        List<Candle> c = List.of(new Candle(t, 10, 10, 10, 10, 0), new Candle(t.plusSeconds(300), 20, 20, 20, 20, 0));
        Indicators.Vwap v = Indicators.vwap(c);
        assertThat(v.volumeWeighted()).isFalse();
        assertThat(v.values()[1]).isCloseTo(15.0, within(1e-12));
    }

    @Test
    void indicatorsNeverFabricateValuesForShortSeries() {
        assertThat(java.util.Arrays.stream(Indicators.rsi(new double[]{1, 2, 3}, 14)).allMatch(Double::isNaN)).isTrue();
    }
}
