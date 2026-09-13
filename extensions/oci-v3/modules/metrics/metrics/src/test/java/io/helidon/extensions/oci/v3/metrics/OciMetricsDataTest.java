/*
 * Copyright (c) 2022, 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.helidon.extensions.oci.v3.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.helidon.metrics.api.DistributionSummary;
import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.Tag;
import io.helidon.metrics.api.Timer;
import io.helidon.service.registry.Services;

import com.oracle.bmc.monitoring.model.MetricDataDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertAll;

class OciMetricsDataTest {
    private final OciMetricsSupport.NameFormatter nameFormatter = new OciMetricsSupport.NameFormatter() { };
    private final MeterRegistry meterRegistry = Services.get(MeterRegistry.class);

    @BeforeEach
    @AfterEach
    void clearAllRegistry() {
        meterRegistry.meters().forEach(meterRegistry::remove);
    }

    @Test
    void testUnscopedMetersAreExported() {
        String counterName = "DummyCounter";
        String timerName = "DummyTimer";

        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder(counterName))
                .increment();
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().timerBuilder(timerName))
                .record(Duration.ofMillis(100));
        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", false);
        List<MetricDataDetails> allMetricDataDetails = ociMetricsData.getMetricDataDetails();
        assertThat("Counter and timer metric data", allMetricDataDetails, hasSize(4));
        assertThat("Counter metric data", allMetricDataDetails.stream()
                .filter(metric -> metric.getName().startsWith(counterName))
                .toList(), hasSize(1));
        assertThat("Timer metric data", allMetricDataDetails.stream()
                .filter(metric -> metric.getName().startsWith(timerName))
                .toList(), hasSize(3));
        allMetricDataDetails.forEach(metric ->
                assertThat("Fallback dimensions for " + metric.getName(),
                           metric.getDimensions(),
                           is(Map.of("source", "helidon"))));
    }

    @Test
    void testOciMonitoringParameters() {
        String compartmentId = "dummy.compartmentId";
        String namespace = "dummy-namespace";
        String resourceGroup = "dummy_resourceGroup";

        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("dummy.counter"))
                .increment();

        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, compartmentId, namespace, resourceGroup, false);
        List<MetricDataDetails> allMetricDataDetails = ociMetricsData.getMetricDataDetails();
        assertThat("Exported counter", allMetricDataDetails, hasSize(1));
        MetricDataDetails metricDataDetails = allMetricDataDetails.getFirst();
        assertThat(metricDataDetails.getCompartmentId(), is(compartmentId));
        assertThat(metricDataDetails.getNamespace(), is(namespace));
        assertThat(metricDataDetails.getResourceGroup(), is(resourceGroup));
    }

    @Test
    void testDimensions() {
        String dummyTagName = "DummyTag";
        String dummyTagValue = "DummyValue";

        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("dummy.counter")
                                          .tags(Set.of(Tag.create(dummyTagName, dummyTagValue))))
                .increment();
        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", false);
        List<MetricDataDetails> allMetricDataDetails = ociMetricsData.getMetricDataDetails();
        assertThat("Exported tagged counter", allMetricDataDetails, hasSize(1));
        assertThat(allMetricDataDetails.getFirst().getDimensions(), is(Map.of(dummyTagName, dummyTagValue)));
    }

    @Test
    void testCallerProvidedScopeTagIsPreserved() {
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("dummy.counter")
                                          .tags(Set.of(Tag.create("scope", "custom"), Tag.create("region", "west"))))
                .increment();
        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", false);
        List<MetricDataDetails> allMetricDataDetails = ociMetricsData.getMetricDataDetails();

        assertThat("Exported counter with a caller-provided scope tag", allMetricDataDetails, hasSize(1));
        assertThat(allMetricDataDetails.getFirst().getDimensions(), is(Map.of("scope", "custom", "region", "west")));
    }

    @Test
    void testBaseUnitsControlValueConversion() {
        String description = "Measured values";
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("elapsed")
                                          .baseUnit(Meter.BaseUnits.MILLISECONDS)
                                          .description(description))
                .increment(1500);
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().gaugeBuilder("size", () -> 16)
                                          .baseUnit(Meter.BaseUnits.BITS)
                                          .description(description));
        AtomicLong total = new AtomicLong(3);
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().functionalCounterBuilder("total", total, AtomicLong::get)
                                          .baseUnit(Meter.BaseUnits.KILOBYTES)
                                          .description(description));

        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", true);
        List<MetricDataDetails> metrics = ociMetricsData.getMetricDataDetails();

        assertAll(
                () -> assertMetricValues(metrics, Map.of("elapsed_counter_seconds", 1.5,
                                                        "size_gauge_bytes", 2.0,
                                                        "total_counter_bytes", 3000.0)),
                () -> metrics.forEach(metric -> assertThat("Description for " + metric.getName(),
                                                          metric.getMetadata(),
                                                          is(Map.of("description", description)))));
    }

    @ParameterizedTest
    @CsvSource({"kilobytes,bytes,2000000,3000000", "milliseconds,seconds,2,3"})
    void testSummaryConvertsValuesWithoutConvertingObservationCount(String unit,
                                                                    String baseUnit,
                                                                    double expectedMean,
                                                                    double expectedMax) {
        var factory = meterRegistry.metricsFactory();
        DistributionSummary summary = meterRegistry.getOrCreate(factory.distributionSummaryBuilder(
                        "samples", factory.distributionStatisticsConfigBuilder())
                        .baseUnit(unit)
                        .description("Recorded samples"));
        summary.record(1000);
        summary.record(3000);

        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", true);

        assertMetricValues(ociMetricsData.getMetricDataDetails(),
                           Map.of("samples_" + unit + "_count_histogram_" + baseUnit, 2.0,
                                  "samples_mean_" + unit + "_histogram_" + baseUnit, expectedMean,
                                  "samples_max_" + unit + "_histogram_" + baseUnit, expectedMax));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"milliseconds", "seconds"})
    void testTimerExportsSecondsWithoutConvertingEventCount(String unit) {
        var builder = meterRegistry.metricsFactory().timerBuilder("duration")
                .description("Recorded durations");
        if (!unit.isEmpty()) {
            builder.baseUnit(unit);
        }
        Timer timer = meterRegistry.getOrCreate(builder);
        timer.record(Duration.ofMillis(500));
        timer.record(Duration.ofMillis(1500));

        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", true);

        assertMetricValues(ociMetricsData.getMetricDataDetails(),
                           Map.of("duration_seconds_count_timer", 2.0,
                                  "duration_mean_seconds_timer", 1.0,
                                  "duration_max_seconds_timer", 1.5));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 256, 257})
    void testDescriptionMetadata(int descriptionLength) {
        var builder = meterRegistry.metricsFactory().counterBuilder("described")
                .baseUnit(Meter.BaseUnits.KILOBYTES);
        if (descriptionLength >= 0) {
            builder.description("x".repeat(descriptionLength));
        }
        meterRegistry.getOrCreate(builder).increment();

        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", true);
        List<MetricDataDetails> metrics = ociMetricsData.getMetricDataDetails();

        assertThat("Exported counter", metrics, hasSize(1));
        if (descriptionLength <= 0) {
            assertThat("Absent or empty description is omitted", metrics.getFirst().getMetadata(), nullValue());
        } else {
            assertThat("Description respects the OCI 256-character limit",
                       metrics.getFirst().getMetadata(),
                       is(Map.of("description", "x".repeat(Math.min(descriptionLength, 256)))));
        }
    }

    @Test
    void testDisabledDescriptionDoesNotAffectValues() {
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("described")
                                          .description(Meter.BaseUnits.MILLISECONDS))
                .increment(1500);

        OciMetricsData ociMetricsData = new OciMetricsData(
                nameFormatter, "compartmentId", "namespace", "resourceGroup", false);
        List<MetricDataDetails> metrics = ociMetricsData.getMetricDataDetails();

        assertThat("Exported counter", metrics, hasSize(1));
        assertAll(
                () -> assertMetricValues(metrics, Map.of("described_counter", 1500.0)),
                () -> assertThat("Disabled description is omitted", metrics.getFirst().getMetadata(), nullValue()));
    }

    private void assertMetricValues(List<MetricDataDetails> metrics, Map<String, Double> expectedValues) {
        metrics.forEach(metric -> assertThat("Datapoints for " + metric.getName(), metric.getDatapoints(), hasSize(1)));
        Map<String, Double> values = metrics.stream()
                .collect(toMap(MetricDataDetails::getName, metric -> metric.getDatapoints().getFirst().getValue()));
        assertThat("Exported metric names", values.keySet(), is(expectedValues.keySet()));
        expectedValues.forEach((name, value) -> assertThat("Value of " + name, values.get(name), closeTo(value, 1.0e-9)));
    }
}
