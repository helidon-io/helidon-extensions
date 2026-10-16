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

package io.helidon.extensions.messaging.connectors.aq;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.sql.DataSource;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.messaging.spi.MessagingIncomingConfig;
import io.helidon.service.registry.ServiceRegistryConfig;
import io.helidon.service.registry.ServiceRegistryManager;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyZeroInteractions;

class AqConfigTest {
    @Test
    void testConstructionDoesNotBorrowSharedDataSourceConnection() {
        DataSource source = mock(DataSource.class);
        AqConnector connector = AqConnector.builder()
                .name("aq")
                .dataSource(source)
                .destination("ORDERS")
                .build();

        assertThat(connector.incoming(incoming()), notNullValue());
        assertThat(connector.outgoing(outgoing()), notNullValue());
        verifyZeroInteractions(source);
    }

    @Test
    void testEffectiveDefaultsAndChannelOverrides() {
        AqConnectorConfig defaults = defaults();
        AqRuntimeConfig incoming = AqRuntimeConfig.incoming(defaults, incoming());
        assertThat(incoming.destinationType(), is(AqDestinationType.QUEUE));
        assertThat(incoming.payloadType(), is(AqPayloadType.JMS));
        assertThat(incoming.transacted(), is(true));
        assertThat(incoming.allowObjectMessages(), is(false));
        assertThat(incoming.receiveTimeout(), is(Duration.ofMillis(100)));
        assertThat(incoming.closeTimeout(), is(Duration.ofSeconds(10)));
        assertThat(incoming.maxBodyBytes(), is(1_048_576));
        assertThat(incoming.deliveryMode(), is(DeliveryMode.PERSISTENT));
        assertThat(incoming.priority(), is(4));
        assertThat(incoming.timeToLive(), is(Duration.ZERO));

        AqIncomingConfig overrides = AqIncomingConfig.builder(incoming())
                .destination("CUSTOM_ORDERS")
                .payloadType(AqPayloadType.ADT)
                .transacted(false)
                .receiveTimeout(Duration.ofSeconds(2))
                .build();
        AqRuntimeConfig effective = AqRuntimeConfig.incoming(defaults, overrides);
        assertThat(effective.destination(), is("CUSTOM_ORDERS"));
        assertThat(effective.payloadType(), is(AqPayloadType.ADT));
        assertThat(effective.transacted(), is(false));
        assertThat(effective.receiveTimeout(), is(Duration.ofSeconds(2)));
    }

    @Test
    void testChannelResourceSelectionReplacesConnectorSelection() {
        DataSource source = mock(DataSource.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        AqConnectorConfig defaults = AqConnectorConfig.builder(defaults())
                .dataSource(source)
                .buildPrototype();
        AqOutgoingConfig channel = AqOutgoingConfig.builder(outgoing())
                .connectionFactory(factory)
                .build();

        AqRuntimeConfig effective = AqRuntimeConfig.outgoing(defaults, channel);
        assertThat(effective.dataSource(), is(Optional.empty()));
        assertThat(effective.connectionFactory().orElseThrow(), sameInstance(factory));
        verifyZeroInteractions(source, factory);
    }

    @Test
    void testConfigurationKeysAndSecretRedaction() {
        Config config = Config.just(ConfigSources.create(Map.ofEntries(
                Map.entry("destination", "ORDERS"),
                Map.entry("schema", "AQ_OWNER"),
                Map.entry("data-source-name", "shared-db"),
                Map.entry("username", "user"),
                Map.entry("password", "secret-password"),
                Map.entry("payload-type", "XML"),
                Map.entry("receive-timeout", "PT1S"),
                Map.entry("close-timeout", "PT4S"),
                Map.entry("max-body-bytes", "2048"))));
        AqConnectorConfig defaults = AqConnectorConfig.builder().name("aq").config(config).buildPrototype();
        AqRuntimeConfig runtime = AqRuntimeConfig.incoming(defaults, incoming());

        assertThat(runtime.schema(), is(Optional.of("AQ_OWNER")));
        assertThat(runtime.dataSourceName(), is(Optional.of("shared-db")));
        assertThat(runtime.password(), is(Optional.of("secret-password")));
        assertThat(runtime.payloadType(), is(AqPayloadType.XML));
        assertThat(runtime.receiveTimeout(), is(Duration.ofSeconds(1)));
        assertThat(runtime.closeTimeout(), is(Duration.ofSeconds(4)));
        assertThat(runtime.maxBodyBytes(), is(2048));
        assertThat(defaults.toString(), not(containsString("secret-password")));
        assertThat(runtime.toString(), not(containsString("secret-password")));
        assertThat(AqIncomingConfig.builder(incoming()).password("secret-password").build().toString(),
                   not(containsString("secret-password")));
        assertThat(AqOutgoingConfig.builder(outgoing()).password("secret-password").build().toString(),
                   not(containsString("secret-password")));
    }

    @Test
    void testProviderDiscoveryUsesConnectorName() {
        ServiceRegistryManager manager = ServiceRegistryManager.create(ServiceRegistryConfig.builder()
                .discoverServicesFromServiceLoader(false)
                .build());
        try {
            var connector = manager.registry().get(AqConnectorProvider.class)
                    .create(Config.just(ConfigSources.create(Map.of("destination", "ORDERS"))), "orders-aq");
            assertThat(connector.name(), is("orders-aq"));
            assertThat(connector.type(), is("helidon-aq"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void testConflictingResourcesAreRejectedBeforeStart() {
        AqConnector connector = AqConnector.builder()
                .name("aq")
                .dataSource(mock(DataSource.class))
                .connectionFactoryName("factory")
                .destination("ORDERS")
                .build();
        var failure = assertThrows(IllegalArgumentException.class, () -> connector.incoming(incoming()));
        assertThat(failure.getMessage(), containsString("mutually exclusive"));
    }

    @Test
    void testInvalidSubscriptionCombinationsAreRejectedBeforeStart() {
        List<AqIncomingConfig> invalid = List.of(
                AqIncomingConfig.builder(incoming()).durable(true).build(),
                AqIncomingConfig.builder(incoming()).destinationType(AqDestinationType.TOPIC).durable(true).build(),
                AqIncomingConfig.builder(incoming()).destinationType(AqDestinationType.TOPIC)
                        .subscriptionName("sub").build(),
                AqIncomingConfig.builder(incoming()).destinationType(AqDestinationType.TOPIC)
                        .durable(true).subscriptionName("sub").recipientName("recipient").build(),
                AqIncomingConfig.builder(incoming()).destinationType(AqDestinationType.TOPIC)
                        .recipientName("recipient").noLocal(true).build());
        for (var channel : invalid) {
            assertThrows(IllegalArgumentException.class, () -> AqRuntimeConfig.incoming(defaults(), channel));
        }
        assertThat(AqRuntimeConfig.incoming(defaults(), AqIncomingConfig.builder(incoming())
                .destinationType(AqDestinationType.TOPIC)
                .recipientName("recipient")
                .build()).recipientName(), is(Optional.of("recipient")));
    }

    @Test
    void testInvalidDurationAndProducerOptionsAreRejectedBeforeStart() {
        assertThrows(IllegalArgumentException.class,
                     () -> AqRuntimeConfig.incoming(defaults(),
                             AqIncomingConfig.builder(incoming()).receiveTimeout(Duration.ofNanos(1)).build()));
        assertThrows(IllegalArgumentException.class,
                     () -> AqRuntimeConfig.outgoing(defaults(),
                             AqOutgoingConfig.builder(outgoing()).timeToLive(Duration.ofSeconds(-1)).build()));
        assertThrows(IllegalArgumentException.class,
                     () -> AqRuntimeConfig.outgoing(defaults(),
                             AqOutgoingConfig.builder(outgoing()).deliveryMode(3).build()));
        assertThrows(IllegalArgumentException.class,
                     () -> AqRuntimeConfig.outgoing(defaults(),
                             AqOutgoingConfig.builder(outgoing()).priority(10).build()));
        assertThrows(IllegalArgumentException.class,
                     () -> AqRuntimeConfig.incoming(defaults(),
                             AqIncomingConfig.builder(incoming()).maxBodyBytes(0).build()));
    }

    @Test
    void testNullResourceIsRejectedByBuilder() {
        assertThrows(NullPointerException.class, () -> AqConnector.builder().dataSource((DataSource) null));
        assertThrows(NullPointerException.class,
                     () -> AqConnector.builder().connectionFactory((ConnectionFactory) null));
    }

    @Test
    void testCredentialsMustBeConfiguredTogether() {
        for (var defaults : List.of(AqConnectorConfig.builder(defaults()).username("user").buildPrototype(),
                                    AqConnectorConfig.builder(defaults()).password("password").buildPrototype())) {
            var failure = assertThrows(IllegalArgumentException.class,
                                       () -> AqRuntimeConfig.incoming(defaults, incoming()));
            assertThat(failure.getMessage(), containsString("username and password must be configured together"));
        }
    }

    @Test
    void testChannelCanDisableInheritedDurableSubscription() {
        AqConnector connector = AqConnectorConfig.builder(defaults())
                .destinationType(AqDestinationType.TOPIC)
                .durable(true)
                .subscriptionName("default-subscription")
                .build();
        AqIncomingConfig typed = AqIncomingConfig.builder(incoming()).durable(false).build();
        AqRuntimeConfig runtime = AqRuntimeConfig.incoming(connector.prototype(), typed);
        assertThat(runtime.durable(), is(false));
        assertThat(runtime.subscriptionName(), is(Optional.empty()));
        assertThat(connector.incoming(typed), notNullValue());

        MessagingIncomingConfig configured = MessagingIncomingConfig.builder()
                .connector("aq")
                .channelName("orders")
                .config(Config.just(ConfigSources.create(Map.of("durable", "false"))))
                .build();
        assertThat(connector.incoming(configured).orElseThrow(), notNullValue());

        assertThrows(IllegalArgumentException.class,
                     () -> connector.incoming(AqIncomingConfig.builder(typed).subscriptionName("explicit").build()));
    }

    @Test
    void testReconnectDefaultsAndWholePolicyOverride() {
        AqReconnectConfig defaults = AqRuntimeConfig.incoming(defaults(), incoming()).reconnect();
        assertThat(defaults.enabled(), is(true));
        assertThat(defaults.initialDelay(), is(Duration.ofMillis(100)));
        assertThat(defaults.maxDelay(), is(Duration.ofSeconds(30)));
        assertThat(defaults.jitter(), is(0.2));
        assertThat(defaults.connectTimeout(), is(Duration.ofSeconds(10)));
        assertThat(defaults.waitTimeout(), is(Duration.ofSeconds(30)));

        AqReconnectConfig policy = AqReconnectConfig.builder().enabled(false).waitTimeout(Duration.ofSeconds(3)).build();
        AqConnectorConfig connector = AqConnectorConfig.builder(defaults()).reconnect(policy).buildPrototype();
        assertThat(AqRuntimeConfig.incoming(connector, incoming()).reconnect(), sameInstance(policy));
        AqReconnectConfig override = AqReconnectConfig.builder().waitTimeout(Duration.ofSeconds(2)).build();
        AqIncomingConfig channel = AqIncomingConfig.builder(incoming()).reconnect(override).build();
        assertThat(AqRuntimeConfig.incoming(connector, channel).reconnect(), sameInstance(override));
        assertThat(AqRuntimeConfig.incoming(connector, channel).reconnect().enabled(), is(true));
    }

    @Test
    void testReconnectConfigurationKeys() {
        Config config = Config.just(ConfigSources.create(Map.of(
                "destination", "ORDERS",
                "reconnect.enabled", "false",
                "reconnect.initial-delay", "PT0.2S",
                "reconnect.max-delay", "PT2S",
                "reconnect.jitter", "0.5",
                "reconnect.connect-timeout", "PT4S",
                "reconnect.wait-timeout", "PT3S")));
        AqReconnectConfig policy = AqRuntimeConfig.incoming(AqConnectorConfig.builder()
                .name("aq").config(config).buildPrototype(), incoming()).reconnect();
        assertThat(policy.enabled(), is(false));
        assertThat(policy.initialDelay(), is(Duration.ofMillis(200)));
        assertThat(policy.maxDelay(), is(Duration.ofSeconds(2)));
        assertThat(policy.jitter(), is(0.5));
        assertThat(policy.connectTimeout(), is(Duration.ofSeconds(4)));
        assertThat(policy.waitTimeout(), is(Duration.ofSeconds(3)));
    }

    @Test
    void testReconnectRejectsInvalidDurationsAndJitter() {
        assertThrows(IllegalArgumentException.class, () -> AqReconnectConfig.builder().initialDelay(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> AqReconnectConfig.builder().maxDelay(Duration.ofMillis(1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> AqReconnectConfig.builder().connectTimeout(Duration.ofNanos(1)).build());
        assertThrows(IllegalArgumentException.class,
                     () -> AqReconnectConfig.builder().waitTimeout(Duration.ofSeconds(-1)).build());
        for (double jitter : List.of(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> AqReconnectConfig.builder().jitter(jitter).build());
        }
    }

    private static AqConnectorConfig defaults() {
        return AqConnectorConfig.builder().name("aq").destination("ORDERS").buildPrototype();
    }

    private static AqIncomingConfig incoming() {
        return AqIncomingConfig.builder().connector("aq").channelName("orders").build();
    }

    private static AqOutgoingConfig outgoing() {
        return AqOutgoingConfig.builder().connector("aq").channelName("orders").build();
    }
}
