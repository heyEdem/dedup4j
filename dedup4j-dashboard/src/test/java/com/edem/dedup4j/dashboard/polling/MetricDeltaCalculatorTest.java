package com.edem.dedup4j.dashboard.polling;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MetricDeltaCalculatorTest {
    private final MetricDeltaCalculator calculator = new MetricDeltaCalculator();
    private final MetricDeltaCalculator.Cumulative before = new MetricDeltaCalculator.Cumulative(10, 4, 3, 1_000, 400, 600);

    @Test
    void firstSampleReflectsCurrentCumulativeValues() {
        var delta = calculator.calculate(before, null);

        assertThat(delta.uploads()).isEqualTo(10);
        assertThat(delta.logicalBytes()).isEqualTo(1_000);
        assertThat(delta.avoidedBytes()).isEqualTo(400);
        assertThat(delta.physicalBytes()).isEqualTo(600);
    }

    @Test
    void calculatesCounterDelta() {
        var delta = calculator.calculate(new MetricDeltaCalculator.Cumulative(13, 5, 4, 1_300, 500, 800), before);
        assertThat(delta.uploads()).isEqualTo(3);
        assertThat(delta.logicalBytes()).isEqualTo(300);
        assertThat(delta.avoidedBytes()).isEqualTo(100);
    }

    @Test
    void counterResetStartsNewBaseline() {
        var delta = calculator.calculate(new MetricDeltaCalculator.Cumulative(2, 1, 0, 100, 20, 80), before);
        assertThat(delta.uploads()).isZero();
        assertThat(delta.logicalBytes()).isZero();
        assertThat(delta.physicalBytes()).isZero();
    }
}
