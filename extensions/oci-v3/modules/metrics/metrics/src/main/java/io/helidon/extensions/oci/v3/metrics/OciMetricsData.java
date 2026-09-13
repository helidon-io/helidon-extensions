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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import io.helidon.metrics.api.Counter;
import io.helidon.metrics.api.DistributionSummary;
import io.helidon.metrics.api.FunctionalCounter;
import io.helidon.metrics.api.Gauge;
import io.helidon.metrics.api.HistogramSnapshot;
import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.metrics.api.Timer;
import io.helidon.service.registry.Services;

import com.oracle.bmc.monitoring.model.Datapoint;
import com.oracle.bmc.monitoring.model.MetricDataDetails;

class OciMetricsData {
    private static final UnitConverter STORAGE_UNIT_CONVERTER = UnitConverter.storageUnitConverter();
    private static final UnitConverter TIME_UNIT_CONVERTER = UnitConverter.timeUnitConverter();
    private static final List<UnitConverter> UNIT_CONVERTERS = List.of(STORAGE_UNIT_CONVERTER, TIME_UNIT_CONVERTER);

    private final OciMetricsSupport.NameFormatter nameFormatter;
    private final String compartmentId;
    private final String namespace;
    private final String resourceGroup;
    private final boolean descriptionEnabled;

    OciMetricsData(
            OciMetricsSupport.NameFormatter nameFormatter,
            String compartmentId,
            String namespace,
            String resourceGroup,
            boolean descriptionEnabled) {
        this.compartmentId = compartmentId;
        this.nameFormatter = nameFormatter;
        this.namespace = namespace;
        this.resourceGroup = resourceGroup;
        this.descriptionEnabled = descriptionEnabled;
    }

    List<MetricDataDetails> getMetricDataDetails() {
        List<MetricDataDetails> allMetricDataDetails = new ArrayList<>();
        Services.get(MeterRegistry.class).meters().stream()
                .flatMap(this::metricDataDetails)
                .forEach(allMetricDataDetails::add);
        return allMetricDataDetails;
    }

    Stream<MetricDataDetails> metricDataDetails(Meter metric) {
        if (metric instanceof Counter counter) {
            return forCounter(metric.id(), counter);
        } else if (metric instanceof FunctionalCounter fCounter) {
            return forFunctionalCounter(metric.id(), fCounter);
        } else if (metric instanceof Gauge gauge) {
            return forGauge(metric.id(), gauge);
        } else if (metric instanceof Timer timer) {
            return forTimer(metric.id(), timer);
        } else if (metric instanceof DistributionSummary summary) {
            return forHistogram(metric.id(), summary);
        } else {
            return Stream.empty();
        }
    }

    private Stream<MetricDataDetails> forCounter(Meter.Id metricId, Counter counter) {
        double value = convertUnits(counter.baseUnit().orElse(null), counter.count());
        return Stream.of(metricDataDetails(counter, metricId, null, value));
    }

    private Stream<MetricDataDetails> forFunctionalCounter(Meter.Id metricId, FunctionalCounter fCounter) {
        double value = convertUnits(fCounter.baseUnit().orElse(null), fCounter.count());
        return Stream.of(metricDataDetails(fCounter, metricId, null, value));
    }

    private Stream<MetricDataDetails> forGauge(Meter.Id metricId, Gauge gauge) {
        double value = convertUnits(gauge.baseUnit().orElse(null), gauge.value().doubleValue());
        return Stream.of(metricDataDetails(gauge, metricId, null, value));
    }

    private Stream<MetricDataDetails> forTimer(Meter.Id metricId, Timer timer) {
        Stream.Builder<MetricDataDetails> result = Stream.builder();
        long count = timer.count();
        result.add(metricDataDetails(timer, metricId, "seconds_count", count));
        if (count > 0) {
            HistogramSnapshot snapshot = timer.snapshot();
            result.add(metricDataDetails(timer,
                                         metricId,
                                         "mean_seconds",
                                         convertUnits(Meter.BaseUnits.NANOSECONDS, snapshot.mean())));
            result.add(metricDataDetails(timer,
                                         metricId,
                                         "max_seconds",
                                         convertUnits(Meter.BaseUnits.NANOSECONDS, snapshot.max())));
        }
        return result.build();
    }

    private Stream<MetricDataDetails> forHistogram(Meter.Id metricId, DistributionSummary histogram) {
        Stream.Builder<MetricDataDetails> result = Stream.builder();
        long count = histogram.count();
        String units = histogram.baseUnit().orElse(null);
        String unitsPrefix = units != null && !Objects.equals(units, Meter.BaseUnits.NONE) ? units + "_" : "";
        String unitsSuffix = units != null && !Objects.equals(units, Meter.BaseUnits.NONE) ? "_" + units : "";
        result.add(metricDataDetails(histogram, metricId, unitsPrefix + "count", count));
        if (count > 0) {
            HistogramSnapshot snapshot = histogram.snapshot();
            result.add(metricDataDetails(histogram,
                                         metricId,
                                         "mean" + unitsSuffix,
                                         convertUnits(units, snapshot.mean())));
            result.add(metricDataDetails(histogram,
                                         metricId,
                                         "max" + unitsSuffix,
                                         convertUnits(units, snapshot.max())));
        }
        return result.build();
    }

    private MetricDataDetails metricDataDetails(Meter metric,
            Meter.Id metricId, String suffix, double value) {
        if (Double.isNaN(value)) {
            return null;
        }

        Map<String, String> dimensions = metric.id().tagsMap();
        if (dimensions.isEmpty()) {
            // OCI requires at least one dimension for each metric group.
            dimensions = Map.of("source", "helidon");
        }
        List<Datapoint> datapoints = datapoints(value);
        String metricName = nameFormatter.format(metric, metricId, suffix, metric.baseUnit().orElse(null));
        return MetricDataDetails.builder()
                .compartmentId(compartmentId)
                .name(metricName)
                .namespace(namespace)
                .resourceGroup(resourceGroup)
                .metadata(ociMetadata(metric.description().orElse(null)))
                .datapoints(datapoints)
                .dimensions(dimensions)
                .build();
    }

    private double convertUnits(String metricUnits, double value) {
        for (UnitConverter converter : UNIT_CONVERTERS) {
            if (converter.handles(metricUnits)) {
                return converter.convert(metricUnits, value);
            }
        }
        return value;
    }

    private List<Datapoint> datapoints(double value) {
        return Collections.singletonList(Datapoint.builder()
                                                 .value(value)
                                                 .timestamp(new Date())
                                                 .build());
    }

    private Map<String, String> ociMetadata(String description) {
        return (descriptionEnabled && description != null && !description.isEmpty())
                ? Collections.singletonMap("description",
                                           description.length() <= 256
                                                   ? description
                                                   // trim metadata value as oci metadata.value has a maximum of 256 characters
                                                   : description.substring(0, 256))
                : null;
    }
}
