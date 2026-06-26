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

package io.helidon.extensions.messaging;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.Service;

/**
 * In-memory channel graph assembled from generated consumer registrations.
 */
@Service.Singleton
class ChannelRegistry implements MessagingRuntime {
    private static final System.Logger LOGGER = System.getLogger(ChannelRegistry.class.getName());

    private final Map<String, MessagingChannel<?>> channels;

    @Service.Inject
    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    Config config,
                    List<IncomingConnector<?>> incomingConnectors,
                    List<OutgoingConnector<?>> outgoingConnectors) {
        Map<String, List<ConsumerRegistration>> grouped = new HashMap<>();
        for (ConsumerRegistration registration : consumerRegistrations) {
            grouped.computeIfAbsent(registration.channel(), ignored -> new ArrayList<>())
                    .add(registration);
        }

        Map<String, MessagingChannel<?>> channels = new HashMap<>();
        grouped.forEach((channel, consumers) -> channels.put(channel, createChannel(channel, consumers)));
        configuredChannels(config, ConnectorConfig.OUTGOING_PREFIX).forEach(channel -> ensureChannel(channels, channel));
        configuredChannels(config, ConnectorConfig.INCOMING_PREFIX).forEach(channel -> ensureChannel(channels, channel));
        this.channels = Map.copyOf(channels);

        configureOutgoingConnectors(config, outgoingConnectors);
        startIncomingConnectors(config, incomingConnectors);
    }

    /**
     * Emit a message to a named channel.
     *
     * @param channel channel name
     * @param message message
     * @param <T> payload type
     */
    @Override
    public <T> void emit(String channel, Message<T> message) {
        emitBatch(channel, List.of(message));
    }

    /**
     * Emit a batch of messages to a named channel.
     *
     * @param channel channel name
     * @param messages messages
     * @param <T> payload type
     */
    @Override
    public <T> void emitBatch(String channel, List<Message<T>> messages) {
        MessagingChannel<?> messagingChannel = channels.get(channel);
        if (messagingChannel == null) {
            return;
        }
        emitBatch(messagingChannel, messages);
    }

    /**
     * Get an assembled channel.
     *
     * @param channel channel name
     * @return channel if it exists
     */
    Optional<MessagingChannel<?>> channel(String channel) {
        return Optional.ofNullable(channels.get(channel));
    }

    /**
     * Add an outgoing connector sink to a named channel.
     *
     * @param channel channel name
     * @param connector outgoing connector sink
     */
    void addOutgoingConnector(String channel, ConnectorSink connector) {
        MessagingChannel<?> messagingChannel = channels.get(channel);
        if (messagingChannel == null) {
            throw new IllegalArgumentException("Unknown messaging channel " + channel);
        }
        if (!(messagingChannel instanceof DefaultMessagingChannel<?> defaultMessagingChannel)) {
            throw new IllegalArgumentException("Unsupported channel implementation "
                                                       + messagingChannel.getClass().getName());
        }
        defaultMessagingChannel.addOutgoingConnector(connector);
    }

    /**
     * Create a context for an incoming connector source.
     *
     * @param channel channel name
     * @return connector source context
     */
    ConnectorSourceContext incomingContext(String channel) {
        return new RegistryConnectorSourceContext(channel);
    }

    /**
     * Run an incoming connector source against a named channel.
     *
     * @param channel channel name
     * @param connector connector source factory
     */
    void runIncoming(String channel, Function<ConnectorSourceContext, ConnectorSource> connector) {
        connector.apply(incomingContext(channel)).run();
    }

    @SuppressWarnings("unchecked")
    private MessagingChannel<?> createChannel(String channel, List<ConsumerRegistration> consumerRegistrations) {
        Class<Object> payloadType = (Class<Object>) payloadType(channel, consumerRegistrations);
        MessagingChannel.Builder<Object> builder = MessagingChannel.<Object>builder()
                .payloadType(payloadType);
        for (ConsumerRegistration consumer : consumerRegistrations) {
            if (consumer.batch()) {
                builder.addBatchOutput(messages -> {
                    validatePayloadTypes(consumer, messages);
                    dispatchBatch(consumer, messages);
                });
            } else {
                builder.addOutput(message -> {
                    validatePayloadType(consumer, message);
                    consumer.dispatch(message);
                });
            }
        }
        return builder.build();
    }

    private MessagingChannel<?> createConfiguredChannel() {
        return MessagingChannel.builder().build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void emitBatch(MessagingChannel<?> messagingChannel, List<Message<T>> messages) {
        ((MessagingChannel) messagingChannel).emitBatch(messages);
    }

    private Class<?> payloadType(String channel, List<ConsumerRegistration> consumerRegistrations) {
        Class<?> payloadType = null;
        for (ConsumerRegistration consumer : consumerRegistrations) {
            if (payloadType == null) {
                payloadType = consumer.payloadType();
            } else if (!payloadType.equals(consumer.payloadType())) {
                throw new IllegalArgumentException("Channel " + channel + " has conflicting payload types "
                                                           + payloadType.getName() + " and "
                                                           + consumer.payloadType().getName());
            }
        }
        return payloadType;
    }

    private void validatePayloadType(ConsumerRegistration consumer, Message<?> message) {
        Object entity = message.entity();
        if (entity != null && !consumer.payloadType().isInstance(entity)) {
            throw new IllegalArgumentException("Channel " + consumer.channel()
                                                       + " expected payload type "
                                                       + consumer.payloadType().getName()
                                                       + " but received " + entity.getClass().getName());
        }
    }

    private void validatePayloadTypes(ConsumerRegistration consumer, List<? extends Message<?>> messages) {
        for (Message<?> message : messages) {
            validatePayloadType(consumer, message);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchBatch(ConsumerRegistration consumer, List<? extends Message<?>> messages) {
        consumer.dispatchBatch((List) messages);
    }

    private void configureOutgoingConnectors(Config root, List<OutgoingConnector<?>> outgoingConnectors) {
        if (outgoingConnectors.isEmpty()) {
            return;
        }

        Map<String, OutgoingConnector<?>> connectors = outgoingConnectors(outgoingConnectors);
        for (String channel : configuredChannels(root, ConnectorConfig.OUTGOING_PREFIX)) {
            Config channelConfig = root.get(ConnectorConfig.OUTGOING_PREFIX + channel);
            String connectorName = channelConfig.get(ConnectorConfig.CONNECTOR_ATTRIBUTE).asString().orElse(null);
            if (connectorName == null) {
                continue;
            }
            OutgoingConnector<?> connector = connectors.get(connectorName);
            if (connector == null) {
                throw new IllegalArgumentException("No outgoing connector named " + connectorName
                                                           + " for channel " + channel);
            }

            Class<? extends ConnectorConfig> configType = outgoingConfigType(connector);
            ConnectorConfig connectorConfig = connectorConfig(root,
                                                             channelConfig,
                                                             configType,
                                                             ConnectorConfig.Direction.OUTGOING,
                                                             channel,
                                                             connectorName);
            addOutgoingConnector(channel, createSink(connector, connectorConfig));
        }
    }

    private void startIncomingConnectors(Config root, List<IncomingConnector<?>> incomingConnectors) {
        if (incomingConnectors.isEmpty()) {
            return;
        }

        Map<String, IncomingConnector<?>> connectors = incomingConnectors(incomingConnectors);
        for (String channel : configuredChannels(root, ConnectorConfig.INCOMING_PREFIX)) {
            Config channelConfig = root.get(ConnectorConfig.INCOMING_PREFIX + channel);
            String connectorName = channelConfig.get(ConnectorConfig.CONNECTOR_ATTRIBUTE).asString().orElse(null);
            if (connectorName == null) {
                continue;
            }
            IncomingConnector<?> connector = connectors.get(connectorName);
            if (connector == null) {
                throw new IllegalArgumentException("No incoming connector named " + connectorName
                                                           + " for channel " + channel);
            }

            Class<? extends ConnectorConfig> configType = incomingConfigType(connector);
            ConnectorConfig connectorConfig = connectorConfig(root,
                                                             channelConfig,
                                                             configType,
                                                             ConnectorConfig.Direction.INCOMING,
                                                             channel,
                                                             connectorName);
            ConnectorSource source = createSource(connector, connectorConfig, incomingContext(channel));
            Thread.ofVirtual()
                    .name("helidon-messaging-connector-source-" + channel)
                    .inheritInheritableThreadLocals(false)
                    .uncaughtExceptionHandler((thread, throwable) -> LOGGER.log(System.Logger.Level.ERROR,
                                                                                "Incoming connector source failed",
                                                                                throwable))
                    .start(source);
        }
    }

    private Map<String, OutgoingConnector<?>> outgoingConnectors(List<OutgoingConnector<?>> outgoingConnectors) {
        Map<String, OutgoingConnector<?>> connectors = new HashMap<>();
        for (OutgoingConnector<?> connector : outgoingConnectors) {
            connectors.put(connector.connectorName(), connector);
        }
        return connectors;
    }

    private Map<String, IncomingConnector<?>> incomingConnectors(List<IncomingConnector<?>> incomingConnectors) {
        Map<String, IncomingConnector<?>> connectors = new HashMap<>();
        for (IncomingConnector<?> connector : incomingConnectors) {
            connectors.put(connector.connectorName(), connector);
        }
        return connectors;
    }

    private Set<String> configuredChannels(Config root, String prefix) {
        Config config = root.get(prefix.substring(0, prefix.length() - 1));
        if (!config.exists()) {
            return Set.of();
        }

        Set<String> channels = new LinkedHashSet<>();
        config.detach().asMap().orElse(Map.of()).keySet()
                .stream()
                .map(ChannelRegistry::firstSegment)
                .forEach(channels::add);
        return channels;
    }

    private void ensureChannel(Map<String, MessagingChannel<?>> channels, String channel) {
        channels.computeIfAbsent(channel, ignored -> createConfiguredChannel());
    }

    private ConnectorConfig connectorConfig(Config root,
                                            Config channelConfig,
                                            Class<? extends ConnectorConfig> configType,
                                            ConnectorConfig.Direction direction,
                                            String channel,
                                            String connector) {
        Map<String, String> properties = connectorProperties(root, channelConfig, connector);
        properties.put(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, channel);
        properties.put(ConnectorConfig.CONNECTOR_ATTRIBUTE, connector);
        properties.put("direction", direction.name());

        Config config = Config.just(ConfigSources.create(properties));
        try {
            Method create = configType.getMethod("create", Config.class);
            return configType.cast(create.invoke(null, config));
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Connector config " + configType.getName()
                                                       + " must expose static create(Config)", e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Connector config " + configType.getName()
                                                       + " create(Config) is not accessible", e);
        } catch (InvocationTargetException e) {
            throw new IllegalArgumentException("Cannot create connector config " + configType.getName(),
                                               e.getCause());
        }
    }

    private Map<String, String> connectorProperties(Config root, Config channelConfig, String connector) {
        Map<String, String> properties = new LinkedHashMap<>();
        root.get(ConnectorConfig.CONNECTOR_PREFIX + connector).detach().asMap().ifPresent(properties::putAll);
        channelConfig.detach().asMap().ifPresent(properties::putAll);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private ConnectorSink createSink(OutgoingConnector<?> connector, ConnectorConfig config) {
        return ((OutgoingConnector<ConnectorConfig>) connector).createSink(config);
    }

    @SuppressWarnings("unchecked")
    private ConnectorSource createSource(IncomingConnector<?> connector,
                                         ConnectorConfig config,
                                         ConnectorSourceContext context) {
        return ((IncomingConnector<ConnectorConfig>) connector).createSource(config, context);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends ConnectorConfig> outgoingConfigType(OutgoingConnector<?> connector) {
        return (Class<? extends ConnectorConfig>) connectorConfigType(connector.getClass(), "createSink", 1);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends ConnectorConfig> incomingConfigType(IncomingConnector<?> connector) {
        return (Class<? extends ConnectorConfig>) connectorConfigType(connector.getClass(), "createSource", 2);
    }

    private Class<?> connectorConfigType(Class<?> connectorType, String methodName, int parameterCount) {
        for (Method method : connectorType.getMethods()) {
            if (method.isBridge() || !method.getName().equals(methodName)) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != parameterCount) {
                continue;
            }
            if (ConnectorConfig.class.isAssignableFrom(parameterTypes[0])) {
                return parameterTypes[0];
            }
        }
        throw new IllegalArgumentException("Connector " + connectorType.getName()
                                                   + " must declare " + methodName
                                                   + " with a ConnectorConfig parameter");
    }

    private static String firstSegment(String key) {
        int index = key.indexOf('.');
        return index == -1 ? key : key.substring(0, index);
    }

    private final class RegistryConnectorSourceContext implements ConnectorSourceContext {
        private final String channel;

        private RegistryConnectorSourceContext(String channel) {
            this.channel = channel;
        }

        @Override
        public String channelName() {
            return channel;
        }

        @Override
        public <T> void emit(Message<T> message) {
            ChannelRegistry.this.emit(channel, message);
        }

        @Override
        public <T> void emitBatch(List<Message<T>> messages) {
            ChannelRegistry.this.emitBatch(channel, messages);
        }
    }
}
