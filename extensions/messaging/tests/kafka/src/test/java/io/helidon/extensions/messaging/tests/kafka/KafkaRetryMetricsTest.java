/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.messaging.tests.kafka;

import java.util.List;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.connectors.kafka.KafkaConnector;
import io.helidon.extensions.messaging.connectors.kafka.KafkaIncomingConfig;
import io.helidon.faulttolerance.FaultTolerance;
import io.helidon.faulttolerance.Retry;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.service.registry.Services;
import io.helidon.testing.junit5.Testing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

@Testing.Test
class KafkaRetryMetricsTest {
    private final MeterRegistry meterRegistry;

    KafkaRetryMetricsTest(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @BeforeAll
    static void enableFaultToleranceMetrics() {
        Services.set(Config.class,
                     Config.just(ConfigSources.create(Map.of(FaultTolerance.FT_METRICS_DEFAULT_ENABLED, "true"))));
    }

    @Test
    void separatesCommitRetryMetricsByChannel() {
        String retryPrefix = "messaging-kafka-offset-commit-";
        removeRetryMetrics(meterRegistry, retryPrefix);
        KafkaConnector kafka = KafkaConnector.builder().name("test-kafka").bootstrapServers("localhost:9092").build();

        try (IncomingChannel orders = kafka.incoming(config("orders"));
                IncomingChannel audit = kafka.incoming(config("audit"))) {
            assertThat(retryMetricNames(meterRegistry, retryPrefix),
                       containsInAnyOrder(retryPrefix + "orders", retryPrefix + "audit"));
        } finally {
            removeRetryMetrics(meterRegistry, retryPrefix);
        }
    }

    private static KafkaIncomingConfig config(String channelName) {
        return KafkaIncomingConfig.builder()
                .channelName(channelName)
                .topic("events")
                .build();
    }

    private static List<String> retryMetricNames(MeterRegistry meterRegistry, String retryPrefix) {
        return retryMetrics(meterRegistry, retryPrefix).stream()
                .map(meter -> meter.id().tagsMap().get("name"))
                .toList();
    }

    private static List<Meter> retryMetrics(MeterRegistry meterRegistry, String retryPrefix) {
        return meterRegistry.meters().stream()
                .filter(meter -> meter.id().name().equals(Retry.FT_RETRY_CALLS_TOTAL))
                .filter(meter -> meter.id().tagsMap().getOrDefault("name", "").startsWith(retryPrefix))
                .toList();
    }

    private static void removeRetryMetrics(MeterRegistry meterRegistry, String retryPrefix) {
        meterRegistry.meters().stream()
                .filter(meter -> meter.id().name().equals(Retry.FT_RETRY_CALLS_TOTAL)
                        || meter.id().name().equals(Retry.FT_RETRY_RETRIES_TOTAL))
                .filter(meter -> meter.id().tagsMap().getOrDefault("name", "").startsWith(retryPrefix))
                .toList()
                .forEach(meterRegistry::remove);
    }
}
