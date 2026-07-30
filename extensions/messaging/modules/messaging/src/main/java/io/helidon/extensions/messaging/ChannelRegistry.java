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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

import io.helidon.common.GenericType;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.Service;

/**
 * In-memory channel graph assembled from generated consumer registrations.
 */
@Service.Singleton
class ChannelRegistry implements MessagingRuntime {
    private static final System.Logger LOGGER = System.getLogger(ChannelRegistry.class.getName());

    private Map<String, MessagingChannel<?>> channels = Map.of();
    private final DeliveryEngine deliveryEngine;

    @Service.Inject
    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    Config config,
                    List<IncomingConnector<?>> incomingConnectors,
                    List<OutgoingConnector<?>> outgoingConnectors,
                    List<MessageSizeEstimator> sizeEstimators) {
        MessagingExecutionConfig defaultExecutionConfig = executionConfig(config, null);
        this.deliveryEngine = new DeliveryEngine(defaultExecutionConfig, sizeEstimators);
        try {
            initialize(consumerRegistrations,
                       config,
                       incomingConnectors,
                       outgoingConnectors);
        } catch (RuntimeException | Error e) {
            deliveryEngine.close();
            throw e;
        }
    }

    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    Config config,
                    List<IncomingConnector<?>> incomingConnectors,
                    List<OutgoingConnector<?>> outgoingConnectors) {
        this(consumerRegistrations, config, incomingConnectors, outgoingConnectors, List.of());
    }

    private void initialize(List<ConsumerRegistration> consumerRegistrations,
                            Config config,
                            List<IncomingConnector<?>> incomingConnectors,
                            List<OutgoingConnector<?>> outgoingConnectors) {
        Map<String, List<ConsumerRegistration>> grouped = new HashMap<>();
        for (ConsumerRegistration registration : consumerRegistrations) {
            grouped.computeIfAbsent(registration.channel(), ignored -> new ArrayList<>())
                    .add(registration);
        }

        Map<String, MessagingChannel<?>> channels = new HashMap<>();
        grouped.forEach((channel, consumers) -> channels.put(channel, createChannel(config, channel, consumers)));
        configuredChannels(config, ConnectorConfig.OUTGOING_PREFIX)
                .forEach(channel -> ensureChannel(config, channels, channel));
        configuredChannels(config, ConnectorConfig.INCOMING_PREFIX)
                .forEach(channel -> ensureChannel(config, channels, channel));
        this.channels = Map.copyOf(channels);

        List<OutgoingBinding> outgoingBindings = prepareOutgoingBindings(config, outgoingConnectors);
        List<IncomingDescriptor> incomingDescriptors = prepareIncomingDescriptors(config, incomingConnectors);
        Set<String> outputChannels = new LinkedHashSet<>(grouped.keySet());
        outgoingBindings.stream().map(OutgoingBinding::channel).forEach(outputChannels::add);
        validateFailureRoutes(incomingDescriptors, outputChannels, grouped);
        validateIncomingOutputs(incomingDescriptors, outputChannels);

        configureOutgoingConnectors(outgoingBindings);
        List<IncomingBinding> incomingBindings = createIncomingBindings(incomingDescriptors);
        startIncomingConnectors(incomingBindings);
    }

    /**
     * Emit a message to a named channel.
     * <p>
     * This method returns only after every required output completes successfully. Output failures are propagated
     * unchanged.
     *
     * @param channel channel name
     * @param message message
     * @param <T> payload type
     * @throws MessagingException if the channel does not exist
     * @throws RuntimeException if an output fails
     */
    @Override
    public <T> void emit(String channel, Message<T> message) {
        emitBatch(channel, List.of(message));
    }

    /**
     * Emit a batch of messages to a named channel.
     * <p>
     * This method returns only after every required output completes successfully. Output failures are propagated
     * unchanged.
     *
     * @param channel channel name
     * @param messages messages
     * @param <T> payload type
     * @throws MessagingException if the channel does not exist
     * @throws RuntimeException if an output fails
     */
    @Override
    public <T> void emitBatch(String channel, List<? extends Message<T>> messages) {
        MessagingChannel<?> messagingChannel = channels.get(channel);
        if (messagingChannel == null) {
            throw new MessagingException("Unknown messaging channel " + channel);
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

    @Service.PreDestroy
    public void close() {
        deliveryEngine.close();
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
        return incomingContext(channel, FailurePolicy.create());
    }

    private ConnectorSourceContext incomingContext(String channel, FailurePolicy failurePolicy) {
        return new RegistryConnectorSourceContext(channel, failurePolicy);
    }

    /**
     * Run an incoming connector source against a named channel.
     *
     * @param channel channel name
     * @param connector connector source factory
     */
    void runIncoming(String channel, Function<ConnectorSourceContext, ConnectorSource> connector) {
        deliveryEngine.startSource(channel, connector.apply(incomingContext(channel)));
    }

    @SuppressWarnings("unchecked")
    private MessagingChannel<?> createChannel(Config config,
                                              String channel,
                                              List<ConsumerRegistration> consumerRegistrations) {
        Class<Object> payloadType = (Class<Object>) payloadType(channel, consumerRegistrations);
        DefaultMessagingChannel.Builder<Object> builder = new DefaultMessagingChannel.Builder<>();
        builder.deliveryEngine(deliveryEngine, channel, executionConfig(config, channel))
                .payloadType(payloadType);
        builder.addBatchOutput(messages -> validateMessageTypes(consumerRegistrations, messages));
        for (ConsumerRegistration consumer : consumerRegistrations) {
            if (consumer.batch()) {
                builder.addBatchOutput(messages -> dispatchBatch(consumer, messages));
            } else {
                builder.addOutput(consumer::dispatch);
            }
        }
        return builder.build();
    }

    private MessagingChannel<?> createConfiguredChannel(Config config, String channel) {
        DefaultMessagingChannel.Builder<Object> builder = new DefaultMessagingChannel.Builder<>();
        builder.deliveryEngine(deliveryEngine, channel, executionConfig(config, channel));
        return builder.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void emitBatch(MessagingChannel<?> messagingChannel, List<? extends Message<T>> messages) {
        ((MessagingChannel) messagingChannel).emitBatch(messages);
    }

    private Class<?> payloadType(String channel, List<ConsumerRegistration> consumerRegistrations) {
        GenericType<?> payloadType = null;
        for (ConsumerRegistration consumer : consumerRegistrations) {
            if (payloadType == null) {
                payloadType = consumer.payloadGenericType();
            } else if (!payloadType.equals(consumer.payloadGenericType())) {
                throw new IllegalArgumentException("Channel " + channel + " has conflicting payload types "
                                                           + payloadType.getTypeName() + " and "
                                                           + consumer.payloadGenericType().getTypeName());
            }
        }
        validateEnvelopeTypes(channel, consumerRegistrations);
        return payloadType == null ? Object.class : payloadType.rawType();
    }

    private void validateEnvelopeTypes(String channel, List<ConsumerRegistration> consumers) {
        for (int first = 0; first < consumers.size(); first++) {
            ConsumerRegistration firstConsumer = consumers.get(first);
            for (int second = first + 1; second < consumers.size(); second++) {
                ConsumerRegistration secondConsumer = consumers.get(second);
                if (!compatibleEnvelopeTypes(firstConsumer, secondConsumer)) {
                    throw new IllegalArgumentException("Channel " + channel + " has conflicting message envelope types "
                                                               + firstConsumer.envelopeGenericType().getTypeName() + " and "
                                                               + secondConsumer.envelopeGenericType().getTypeName());
                }
            }
        }
    }

    private boolean compatibleEnvelopeTypes(ConsumerRegistration first, ConsumerRegistration second) {
        Type firstType = first.envelopeGenericType().type();
        Type secondType = second.envelopeGenericType().type();
        return typeAccepts(firstType, secondType) || typeAccepts(secondType, firstType);
    }

    private boolean typeAccepts(Type target, Type candidate) {
        if (sameType(target, candidate)) {
            return true;
        }
        if (target instanceof WildcardType wildcard) {
            return wildcardAccepts(wildcard, candidate);
        }

        Class<?> targetRawType = GenericType.create(target).rawType();
        Class<?> candidateRawType = GenericType.create(candidate).rawType();
        if (!targetRawType.isAssignableFrom(candidateRawType)) {
            return false;
        }
        if (target instanceof Class<?>) {
            return true;
        }
        if (!(target instanceof ParameterizedType targetParameterized)) {
            return false;
        }

        Type resolvedCandidate = resolveSupertype(candidate, targetRawType);
        if (resolvedCandidate instanceof Class<?>) {
            return true;
        }
        if (!(resolvedCandidate instanceof ParameterizedType candidateParameterized)) {
            return false;
        }
        return typeArgumentsAccept(targetParameterized.getActualTypeArguments(),
                                   candidateParameterized.getActualTypeArguments());
    }

    private boolean typeArgumentsAccept(Type[] targetArguments, Type[] candidateArguments) {
        if (targetArguments.length != candidateArguments.length) {
            return false;
        }
        for (int i = 0; i < targetArguments.length; i++) {
            if (!typeArgumentAccepts(targetArguments[i], candidateArguments[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean typeArgumentAccepts(Type target, Type candidate) {
        if (sameType(target, candidate)) {
            return true;
        }
        if (target instanceof WildcardType wildcard) {
            return wildcardAccepts(wildcard, candidate);
        }
        if (target instanceof ParameterizedType targetParameterized
                && candidate instanceof ParameterizedType candidateParameterized
                && sameType(targetParameterized.getRawType(), candidateParameterized.getRawType())) {
            return typeArgumentsAccept(targetParameterized.getActualTypeArguments(),
                                       candidateParameterized.getActualTypeArguments());
        }
        if (target instanceof GenericArrayType targetArray
                && candidate instanceof GenericArrayType candidateArray) {
            return typeArgumentAccepts(targetArray.getGenericComponentType(),
                                       candidateArray.getGenericComponentType());
        }
        return false;
    }

    private boolean wildcardAccepts(WildcardType wildcard, Type candidate) {
        if (candidate instanceof WildcardType) {
            return false;
        }
        for (Type lowerBound : wildcard.getLowerBounds()) {
            if (!typeAccepts(candidate, lowerBound)) {
                return false;
            }
        }
        for (Type upperBound : wildcard.getUpperBounds()) {
            if (!typeAccepts(upperBound, candidate)) {
                return false;
            }
        }
        return true;
    }

    private Type resolveSupertype(Type candidate, Class<?> targetRawType) {
        Class<?> candidateRawType = GenericType.create(candidate).rawType();
        if (candidateRawType.equals(targetRawType)) {
            return candidate;
        }

        Map<TypeVariable<?>, Type> bindings = typeBindings(candidateRawType, candidate);
        for (Type genericInterface : candidateRawType.getGenericInterfaces()) {
            Type resolvedInterface = resolveType(genericInterface, bindings);
            Class<?> interfaceRawType = GenericType.create(resolvedInterface).rawType();
            if (targetRawType.isAssignableFrom(interfaceRawType)) {
                Type resolved = resolveSupertype(resolvedInterface, targetRawType);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        Type genericSuperclass = candidateRawType.getGenericSuperclass();
        if (genericSuperclass != null) {
            Type resolvedSuperclass = resolveType(genericSuperclass, bindings);
            Class<?> superclassRawType = GenericType.create(resolvedSuperclass).rawType();
            if (targetRawType.isAssignableFrom(superclassRawType)) {
                return resolveSupertype(resolvedSuperclass, targetRawType);
            }
        }
        return null;
    }

    private Map<TypeVariable<?>, Type> typeBindings(Class<?> rawType, Type type) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        if (type instanceof ParameterizedType parameterizedType) {
            TypeVariable<?>[] variables = rawType.getTypeParameters();
            Type[] arguments = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < Math.min(variables.length, arguments.length); i++) {
                bindings.put(variables[i], arguments[i]);
            }
        }
        return bindings;
    }

    private Type resolveType(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) {
            return bindings.getOrDefault(variable, variable);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            Type[] resolvedArguments = new Type[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                resolvedArguments[i] = resolveType(arguments[i], bindings);
            }
            Type owner = parameterizedType.getOwnerType();
            return new ResolvedParameterizedType(owner == null ? null : resolveType(owner, bindings),
                                                 parameterizedType.getRawType(),
                                                 resolvedArguments);
        }
        if (type instanceof WildcardType wildcardType) {
            return new ResolvedWildcardType(resolveTypes(wildcardType.getLowerBounds(), bindings),
                                            resolveTypes(wildcardType.getUpperBounds(), bindings));
        }
        if (type instanceof GenericArrayType arrayType) {
            return new ResolvedGenericArrayType(resolveType(arrayType.getGenericComponentType(), bindings));
        }
        return type;
    }

    private Type[] resolveTypes(Type[] types, Map<TypeVariable<?>, Type> bindings) {
        Type[] resolved = new Type[types.length];
        for (int i = 0; i < types.length; i++) {
            resolved[i] = resolveType(types[i], bindings);
        }
        return resolved;
    }

    private boolean sameType(Type first, Type second) {
        return first.equals(second) || second.equals(first);
    }

    private void validateMessageType(ConsumerRegistration consumer, Message<?> message) {
        if (!consumer.envelopeType().isInstance(message)) {
            throw new IllegalArgumentException("Channel " + consumer.channel()
                                                       + " expected message envelope type "
                                                       + consumer.envelopeType().getName()
                                                       + " but received " + message.getClass().getName());
        }
        Object entity = message.entity();
        if (entity != null && !consumer.payloadType().isInstance(entity)) {
            throw new IllegalArgumentException("Channel " + consumer.channel()
                                                       + " expected payload type "
                                                       + consumer.payloadType().getName()
                                                       + " but received " + entity.getClass().getName());
        }
    }

    private void validateMessageTypes(ConsumerRegistration consumer, List<? extends Message<?>> messages) {
        for (Message<?> message : messages) {
            validateMessageType(consumer, message);
        }
    }

    private void validateMessageTypes(List<ConsumerRegistration> consumers, List<? extends Message<?>> messages) {
        for (ConsumerRegistration consumer : consumers) {
            validateMessageTypes(consumer, messages);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchBatch(ConsumerRegistration consumer, List<? extends Message<?>> messages) {
        consumer.dispatchBatch((List) messages);
    }

    private List<OutgoingBinding> prepareOutgoingBindings(Config root,
                                                          List<OutgoingConnector<?>> outgoingConnectors) {
        List<OutgoingBinding> bindings = new ArrayList<>();
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
            bindings.add(new OutgoingBinding(channel, connector, connectorConfig));
        }
        return List.copyOf(bindings);
    }

    private List<IncomingDescriptor> prepareIncomingDescriptors(Config root,
                                                                List<IncomingConnector<?>> incomingConnectors) {
        List<IncomingDescriptor> descriptors = new ArrayList<>();
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
            FailurePolicy failurePolicy = FailurePolicy.create(channelConfig.get("failure"));
            descriptors.add(new IncomingDescriptor(channel, failurePolicy, connector, connectorConfig));
        }
        return List.copyOf(descriptors);
    }

    private void configureOutgoingConnectors(List<OutgoingBinding> bindings) {
        for (OutgoingBinding binding : bindings) {
            addOutgoingConnector(binding.channel(), createSink(binding.connector(), binding.config()));
        }
    }

    private List<IncomingBinding> createIncomingBindings(List<IncomingDescriptor> descriptors) {
        List<IncomingBinding> bindings = new ArrayList<>(descriptors.size());
        for (IncomingDescriptor descriptor : descriptors) {
            ConnectorSourceContext context = incomingContext(descriptor.channel(), descriptor.failurePolicy());
            ConnectorSource source = createSource(descriptor.connector(), descriptor.config(), context);
            bindings.add(new IncomingBinding(descriptor.channel(), source));
        }
        return List.copyOf(bindings);
    }

    private void startIncomingConnectors(List<IncomingBinding> bindings) {
        for (IncomingBinding binding : bindings) {
            deliveryEngine.startSource("connector-" + binding.channel(), binding.source());
        }
    }

    private void validateIncomingOutputs(List<IncomingDescriptor> bindings, Set<String> outputChannels) {
        for (IncomingDescriptor binding : bindings) {
            if (!outputChannels.contains(binding.channel())) {
                throw new IllegalArgumentException("Incoming channel " + binding.channel() + " has no outputs");
            }
        }
    }

    private void validateFailureRoutes(List<IncomingDescriptor> bindings,
                                       Set<String> outputChannels,
                                       Map<String, List<ConsumerRegistration>> consumers) {
        Map<String, String> routes = new LinkedHashMap<>();
        for (IncomingDescriptor binding : bindings) {
            FailurePolicy policy = binding.failurePolicy();
            if (policy.onExhausted() != FailureDisposition.DEAD_LETTER) {
                continue;
            }

            String source = binding.channel();
            String target = policy.deadLetterChannel().orElseThrow();
            if (source.equals(target)) {
                throw new IllegalArgumentException("Dead-letter channel must not reference itself: " + source);
            }
            if (!channels.containsKey(target)) {
                throw new IllegalArgumentException("Unknown dead-letter channel " + target
                                                           + " configured for incoming channel " + source);
            }
            if (!outputChannels.contains(target)) {
                throw new IllegalArgumentException("Dead-letter channel " + target
                                                           + " configured for incoming channel " + source
                                                           + " has no outputs");
            }
            validateDeadLetterConsumers(source, target, consumers.getOrDefault(target, List.of()));
            routes.put(source, target);
        }
        validateFailureRouteCycles(routes);
    }

    private void validateDeadLetterConsumers(String source,
                                             String target,
                                             List<ConsumerRegistration> consumers) {
        for (ConsumerRegistration consumer : consumers) {
            if (!consumer.envelopeType().isAssignableFrom(DeadLetterMessage.class)) {
                throw new IllegalArgumentException("Dead-letter channel " + target
                                                           + " configured for incoming channel " + source
                                                           + " has consumer envelope "
                                                           + consumer.envelopeGenericType().getTypeName()
                                                           + " that cannot accept "
                                                           + DeadLetterMessage.class.getName());
            }
        }
    }

    private void validateFailureRouteCycles(Map<String, String> routes) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        List<String> path = new ArrayList<>();
        for (String source : routes.keySet()) {
            visitFailureRoute(source, routes, visited, visiting, path);
        }
    }

    private void visitFailureRoute(String source,
                                   Map<String, String> routes,
                                   Set<String> visited,
                                   Set<String> visiting,
                                   List<String> path) {
        if (visited.contains(source)) {
            return;
        }
        if (!visiting.add(source)) {
            int cycleStart = path.indexOf(source);
            List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(source);
            throw new IllegalArgumentException("Cyclic dead-letter channel route: " + String.join(" -> ", cycle));
        }
        path.add(source);

        String target = routes.get(source);
        if (target != null) {
            visitFailureRoute(target, routes, visited, visiting, path);
        }

        path.removeLast();
        visiting.remove(source);
        visited.add(source);
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

    private void ensureChannel(Config config, Map<String, MessagingChannel<?>> channels, String channel) {
        channels.computeIfAbsent(channel, ignored -> createConfiguredChannel(config, channel));
    }

    static MessagingExecutionConfig executionConfig(Config root, String channel) {
        Map<String, String> properties = new LinkedHashMap<>();
        root.get("helidon.messaging.execution").detach().asMap().ifPresent(properties::putAll);
        if (channel != null) {
            Map<String, String> channelProperties = new LinkedHashMap<>();
            root.get("helidon.messaging.channel." + channel + ".execution")
                    .detach()
                    .asMap()
                    .ifPresent(channelProperties::putAll);
            if (channelProperties.containsKey("shutdown-timeout")) {
                throw new IllegalArgumentException("Channel execution configuration must not override global "
                                                           + "shutdown-timeout: " + channel);
            }
            properties.putAll(channelProperties);
        }
        return MessagingExecutionConfig.create(Config.just(ConfigSources.create(properties)));
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
        properties.keySet().removeIf(key -> key.equals("failure") || key.startsWith("failure."));
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
        private final FailurePolicy failurePolicy;

        private RegistryConnectorSourceContext(String channel, FailurePolicy failurePolicy) {
            this.channel = channel;
            this.failurePolicy = failurePolicy;
        }

        @Override
        public String channelName() {
            return channel;
        }

        @Override
        public FailurePolicy failurePolicy() {
            return failurePolicy;
        }

        @Override
        public int maxDeliveryMessages() {
            return deliveryEngine.maxDeliveryMessages(channel);
        }

        @Override
        public long maxDeliveryBytes() {
            return deliveryEngine.maxDeliveryBytes(channel);
        }

        @Override
        public OptionalLong messageAdmissionBytes(Message<?> message) {
            return deliveryEngine.messageAdmissionBytes(message);
        }

        @Override
        public Optional<Duration> admissionTimeout() {
            return deliveryEngine.admissionTimeout(channel);
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery(int maxMessages, long maxAdmissionBytes) {
            return deliveryEngine.reserveConnectorDelivery(channel, maxMessages, maxAdmissionBytes);
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages, long maxAdmissionBytes) {
            return deliveryEngine.tryReserveConnectorDelivery(channel, maxMessages, maxAdmissionBytes);
        }

        @Override
        public <T> void emit(Message<T> message) {
            ChannelRegistry.this.emit(channel, message);
        }

        @Override
        public <T> void emitBatch(List<? extends Message<T>> messages) {
            ChannelRegistry.this.emitBatch(channel, messages);
        }

        @Override
        public <T> ConnectorDelivery submitDelivery(List<? extends Message<T>> messages,
                                                    long admissionBytes,
                                                    Runnable delivery) {
            return deliveryEngine.submitConnectorDelivery(channel, messages, admissionBytes, delivery);
        }

        @Override
        public <T> Optional<ConnectorDelivery> trySubmitDelivery(List<? extends Message<T>> messages,
                                                                 long admissionBytes,
                                                                 Runnable delivery) {
            return deliveryEngine.trySubmitConnectorDelivery(channel, messages, admissionBytes, delivery);
        }

        @Override
        public <T> FailureResult handleFailure(List<? extends Message<T>> messages,
                                               int failedAttempt,
                                               RuntimeException failure) {
            if (failedAttempt < 1) {
                throw new IllegalArgumentException("failedAttempt must be greater than zero");
            }
            if (failurePolicy.maxAttempts() == 0 || failedAttempt < failurePolicy.maxAttempts()) {
                return FailureResult.RETRY;
            }

            return switch (failurePolicy.onExhausted()) {
            case FAIL -> throw failure;
            case DROP -> {
                LOGGER.log(System.Logger.Level.WARNING,
                           "Messaging delivery failed after " + failedAttempt
                                   + " attempt(s); dropping " + messages.size()
                                   + " message(s) from channel " + channel,
                           failure);
                yield FailureResult.SETTLED;
            }
            case DEAD_LETTER -> {
                String target = failurePolicy.deadLetterChannel().orElseThrow();
                List<DeadLetterMessage<T>> deadLetters = new ArrayList<>(messages.size());
                for (Message<T> message : messages) {
                    deadLetters.add(DeadLetterMessage.create(message, channel, failedAttempt, failure));
                }
                try {
                    ChannelRegistry.this.emitBatch(target, deadLetters);
                } catch (RuntimeException routeFailure) {
                    MessagingException result = new MessagingException(
                            "Dead-letter delivery from channel " + channel
                                    + " to channel " + target + " failed",
                            routeFailure);
                    result.addSuppressed(failure);
                    throw result;
                }
                yield FailureResult.SETTLED;
            }
            };
        }
    }

    private static final class ResolvedParameterizedType implements ParameterizedType {
        private final Type ownerType;
        private final Type rawType;
        private final Type[] actualTypeArguments;

        private ResolvedParameterizedType(Type ownerType, Type rawType, Type[] actualTypeArguments) {
            this.ownerType = ownerType;
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments.clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof ParameterizedType other)) {
                return false;
            }
            return Objects.equals(ownerType, other.getOwnerType())
                    && rawType.equals(other.getRawType())
                    && Arrays.equals(actualTypeArguments, other.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(actualTypeArguments)
                    ^ Objects.hashCode(ownerType)
                    ^ Objects.hashCode(rawType);
        }
    }

    private static final class ResolvedWildcardType implements WildcardType {
        private final Type[] lowerBounds;
        private final Type[] upperBounds;

        private ResolvedWildcardType(Type[] lowerBounds, Type[] upperBounds) {
            this.lowerBounds = lowerBounds.clone();
            this.upperBounds = upperBounds.clone();
        }

        @Override
        public Type[] getUpperBounds() {
            return upperBounds.clone();
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds.clone();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof WildcardType other)) {
                return false;
            }
            return Arrays.equals(lowerBounds, other.getLowerBounds())
                    && Arrays.equals(upperBounds, other.getUpperBounds());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(lowerBounds) ^ Arrays.hashCode(upperBounds);
        }
    }

    private static final class ResolvedGenericArrayType implements GenericArrayType {
        private final Type componentType;

        private ResolvedGenericArrayType(Type componentType) {
            this.componentType = componentType;
        }

        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof GenericArrayType other
                    && componentType.equals(other.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return componentType.hashCode();
        }
    }

    private record OutgoingBinding(String channel, OutgoingConnector<?> connector, ConnectorConfig config) {
    }

    private record IncomingDescriptor(String channel,
                                      FailurePolicy failurePolicy,
                                      IncomingConnector<?> connector,
                                      ConnectorConfig config) {
    }

    private record IncomingBinding(String channel, ConnectorSource source) {
    }
}
