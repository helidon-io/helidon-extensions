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
import java.util.Objects;
import java.util.Optional;

import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.sql.DataSource;

/**
 * Effective channel settings, kept out of the public configuration API.
 */
record AqRuntimeConfig(String channelName,
                       String destination,
                       Optional<String> schema,
                       AqDestinationType destinationType,
                       AqPayloadType payloadType,
                       Optional<AqPayloadConverter> payloadConverter,
                       Optional<DataSource> dataSource,
                       Optional<String> dataSourceName,
                       Optional<ConnectionFactory> connectionFactory,
                       Optional<String> connectionFactoryName,
                       Optional<String> jdbcUrl,
                       Optional<String> username,
                       Optional<String> password,
                       Optional<String> clientId,
                       boolean transacted,
                       boolean allowObjectMessages,
                       Duration closeTimeout,
                       AqReconnectConfig reconnect,
                       Optional<String> messageSelector,
                       boolean durable,
                       Optional<String> subscriptionName,
                       Optional<String> recipientName,
                       boolean noLocal,
                       int maxBodyBytes,
                       Duration receiveTimeout,
                       int deliveryMode,
                       int priority,
                       Duration timeToLive) {
    static AqRuntimeConfig incoming(AqConnectorConfig defaults, AqIncomingConfig channel) {
        return create(channel.channelName(), defaults, channel, channel, defaults, true);
    }

    static AqRuntimeConfig outgoing(AqConnectorConfig defaults, AqOutgoingConfig channel) {
        return create(channel.channelName(), defaults, channel, defaults, channel, false);
    }

    @Override
    public String toString() {
        return "AqRuntimeConfig[channelName=" + channelName + ", destination=" + destination
                + ", destinationType=" + destinationType + ", payloadType=" + payloadType + "]";
    }

    private static AqRuntimeConfig create(String channelName,
                                          AqConnectorConfig defaults,
                                          AqChannelOptions channel,
                                          AqIncomingOptions incoming,
                                          AqOutgoingOptions outgoing,
                                          boolean receiving) {
        Objects.requireNonNull(channelName);
        requireNonBlank("channel-name", Optional.of(channelName));
        validateResources(defaults);
        validateResources(channel);
        AqChannelOptions resources = resourceCount(channel) > 0 ? channel : defaults;
        String destination = inherit(channel.destination(), defaults.destination())
                .orElseThrow(() -> new IllegalArgumentException("AQ destination is required for channel " + channelName));
        Optional<String> schema = inherit(channel.schema(), defaults.schema());
        Optional<String> username = inherit(channel.username(), defaults.username());
        Optional<String> password = inherit(channel.password(), defaults.password());
        Optional<String> clientId = inherit(channel.clientId(), defaults.clientId());
        Optional<String> selector = inherit(incoming.messageSelector(), defaults.messageSelector());
        Optional<String> subscription = incoming.subscriptionName();
        if (subscription.isEmpty() && !incoming.durable().equals(Optional.of(false))) {
            subscription = defaults.subscriptionName();
        }
        Optional<String> recipient = inherit(incoming.recipientName(), defaults.recipientName());
        AqDestinationType destinationType = inherit(channel.destinationType(), defaults.destinationType())
                .orElse(AqDestinationType.QUEUE);
        boolean durable = inherit(incoming.durable(), defaults.durable()).orElse(false);
        boolean noLocal = inherit(incoming.noLocal(), defaults.noLocal()).orElse(false);
        Duration receiveTimeout = inherit(incoming.receiveTimeout(), defaults.receiveTimeout()).orElse(Duration.ofMillis(100));
        Duration closeTimeout = inherit(channel.closeTimeout(), defaults.closeTimeout()).orElse(Duration.ofSeconds(10));
        AqReconnectConfig reconnect = inherit(channel.reconnect(), defaults.reconnect()).orElseGet(AqReconnectConfig::create);
        Duration timeToLive = inherit(outgoing.timeToLive(), defaults.timeToLive()).orElse(Duration.ZERO);
        int maxBodyBytes = inherit(incoming.maxBodyBytes(), defaults.maxBodyBytes()).orElse(1_048_576);
        int deliveryMode = inherit(outgoing.deliveryMode(), defaults.deliveryMode()).orElse(DeliveryMode.PERSISTENT);
        int priority = inherit(outgoing.priority(), defaults.priority()).orElse(4);
        requireNonBlank("destination", Optional.of(destination));
        requireNonBlank("schema", schema);
        requireNonBlank("username", username);
        requireNonBlank("client-id", clientId);
        requireNonBlank("message-selector", selector);
        requireNonBlank("subscription-name", subscription);
        requireNonBlank("recipient-name", recipient);
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("AQ username and password must be configured together");
        }
        requireMillis("receive-timeout", receiveTimeout, false);
        requireMillis("close-timeout", closeTimeout, false);
        requireMillis("time-to-live", timeToLive, true);
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("max-body-bytes must be greater than zero");
        }
        if (deliveryMode != DeliveryMode.PERSISTENT && deliveryMode != DeliveryMode.NON_PERSISTENT) {
            throw new IllegalArgumentException("delivery-mode must be 1 (nonpersistent) or 2 (persistent)");
        }
        if (priority < 0 || priority > 9) {
            throw new IllegalArgumentException("priority must be between 0 and 9");
        }
        if (receiving) {
            if (destinationType == AqDestinationType.QUEUE
                    && (durable || noLocal || subscription.isPresent() || recipient.isPresent())) {
                throw new IllegalArgumentException("AQ subscriptions, recipient-name, and no-local require "
                                                           + "destination-type TOPIC");
            }
            if (durable && subscription.isEmpty()) {
                throw new IllegalArgumentException("A durable AQ subscription requires subscription-name");
            }
            if (!durable && subscription.isPresent()) {
                throw new IllegalArgumentException("subscription-name requires durable=true");
            }
            if (durable && recipient.isPresent()) {
                throw new IllegalArgumentException("recipient-name and durable subscriptions are mutually exclusive");
            }
            if (recipient.isPresent() && noLocal) {
                throw new IllegalArgumentException("no-local cannot be combined with recipient-name");
            }
        }
        return new AqRuntimeConfig(channelName,
                                   destination,
                                   schema,
                                   destinationType,
                                   inherit(channel.payloadType(), defaults.payloadType()).orElse(AqPayloadType.JMS),
                                   inherit(channel.payloadConverter(), defaults.payloadConverter()),
                                   resources.dataSource(),
                                   resources.dataSourceName(),
                                   resources.connectionFactory(),
                                   resources.connectionFactoryName(),
                                   resources.jdbcUrl(),
                                   username,
                                   password,
                                   clientId,
                                   inherit(channel.transacted(), defaults.transacted()).orElse(true),
                                   inherit(channel.allowObjectMessages(), defaults.allowObjectMessages()).orElse(false),
                                   closeTimeout,
                                   reconnect,
                                   selector,
                                   durable,
                                   subscription,
                                   recipient,
                                   noLocal,
                                   maxBodyBytes,
                                   receiveTimeout,
                                   deliveryMode,
                                   priority,
                                   timeToLive);
    }

    private static <T> Optional<T> inherit(Optional<T> channel, Optional<T> defaults) {
        return channel.or(() -> defaults);
    }

    private static int resourceCount(AqChannelOptions options) {
        return (options.dataSource().isPresent() ? 1 : 0)
                + (options.dataSourceName().isPresent() ? 1 : 0)
                + (options.connectionFactory().isPresent() ? 1 : 0)
                + (options.connectionFactoryName().isPresent() ? 1 : 0)
                + (options.jdbcUrl().isPresent() ? 1 : 0);
    }

    private static void validateResources(AqChannelOptions options) {
        if (resourceCount(options) > 1) {
            throw new IllegalArgumentException("AQ data source, connection factory, registry names, and JDBC URL "
                                                       + "are mutually exclusive");
        }
        requireNonBlank("data-source-name", options.dataSourceName());
        requireNonBlank("connection-factory-name", options.connectionFactoryName());
        requireNonBlank("jdbc-url", options.jdbcUrl());
    }

    private static void requireNonBlank(String name, Optional<String> value) {
        value.ifPresent(it -> {
            if (it.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        });
    }

    private static void requireMillis(String name, Duration duration, boolean zeroAllowed) {
        try {
            long millis = duration.toMillis();
            if (duration.isNegative() || (!zeroAllowed && millis < 1)
                    || (zeroAllowed && !duration.isZero() && millis == 0)) {
                throw new IllegalArgumentException(name + " must be " + (zeroAllowed ? "zero or " : "")
                                                           + "at least one millisecond");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " is too large", e);
        }
    }
}
