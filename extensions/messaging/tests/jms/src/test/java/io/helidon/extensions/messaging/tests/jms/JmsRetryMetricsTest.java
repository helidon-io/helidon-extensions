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

package io.helidon.extensions.messaging.tests.jms;

import java.util.List;
import java.util.Map;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.extensions.messaging.connectors.jms.JmsConnector;
import io.helidon.extensions.messaging.connectors.jms.JmsIncomingConfig;
import io.helidon.extensions.messaging.connectors.jms.JmsOutgoingConfig;
import io.helidon.faulttolerance.FaultTolerance;
import io.helidon.faulttolerance.Retry;
import io.helidon.messaging.spi.IncomingChannel;
import io.helidon.messaging.spi.OutgoingChannel;
import io.helidon.metrics.api.Meter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.service.registry.Services;
import io.helidon.testing.junit5.Testing;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

@Testing.Test
class JmsRetryMetricsTest {
    private final MeterRegistry meterRegistry;

    JmsRetryMetricsTest(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @BeforeAll
    static void enableFaultToleranceMetrics() {
        Services.set(Config.class,
                     Config.just(ConfigSources.create(Map.of(FaultTolerance.FT_METRICS_DEFAULT_ENABLED, "true"))));
    }

    @Test
    void separatesReconnectRetryMetricsByChannelAndDirection() {
        String retryPrefix = "messaging-jms-";
        removeRetryMetrics(meterRegistry, retryPrefix);

        try (ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://localhost:61616")) {
            JmsConnector connector = JmsConnector.builder()
                    .name("test-jms")
                    .connectionFactory(connectionFactory)
                    .destination("events")
                    .build();
            try (IncomingChannel incomingOrders = connector.incoming(JmsIncomingConfig.builder()
                                                                             .channelName("orders")
                                                                             .build());
                    IncomingChannel incomingAudit = connector.incoming(JmsIncomingConfig.builder()
                                                                               .channelName("audit")
                                                                               .build());
                    OutgoingChannel outgoingOrders = connector.outgoing(JmsOutgoingConfig.builder()
                                                                                .channelName("orders")
                                                                                .build());
                    OutgoingChannel outgoingAudit = connector.outgoing(JmsOutgoingConfig.builder()
                                                                               .channelName("audit")
                                                                               .build())) {
                assertThat(retryMetricNames(meterRegistry, retryPrefix),
                           containsInAnyOrder("messaging-jms-incoming-reconnect-orders",
                                              "messaging-jms-incoming-reconnect-audit",
                                              "messaging-jms-outgoing-reconnect-orders",
                                              "messaging-jms-outgoing-reconnect-audit"));
            }
        } finally {
            removeRetryMetrics(meterRegistry, retryPrefix);
        }
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
