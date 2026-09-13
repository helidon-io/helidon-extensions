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

import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.Tag;
import io.helidon.service.registry.Services;

import com.oracle.bmc.monitoring.model.MetricDataDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

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
}
