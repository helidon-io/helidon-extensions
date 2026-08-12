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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.helidon.common.GenericType;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.service.registry.Service;

/**
 * In-memory channel graph assembled from generated consumer registrations.
 */
@Service.Singleton
@Service.RunLevel(MessagingRuntime.RUN_LEVEL)
class ChannelRegistry implements MessagingRuntime {
    private static final System.Logger LOGGER = System.getLogger(ChannelRegistry.class.getName());

    private Map<String, MessagingChannel<?>> channels = Map.of();
    private final DeliveryEngine deliveryEngine;
    private final DefaultMessagingGraph graph;

    @Service.Inject
    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    List<EmitterRegistration> emitterRegistrations,
                    Config config,
                    List<ConnectorProvider> connectorProviders,
                    MessagingLifecycleGuard lifecycleGuard) {
        MessagingExecutionConfig defaultExecutionConfig = executionConfig(config, null);
        this.deliveryEngine = new DeliveryEngine(defaultExecutionConfig);
        this.graph = new DefaultMessagingGraph(deliveryEngine);
        try {
            initialize(consumerRegistrations,
                       emitterRegistrations,
                       config,
                       connectorProviders);
            graph.prepare();
        } catch (RuntimeException | Error e) {
            graph.abortPreparation(e);
            throw e;
        }
        lifecycleGuard.register(this);
    }

    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    Config config,
                    List<ConnectorProvider> connectorProviders,
                    MessagingLifecycleGuard lifecycleGuard) {
        this(consumerRegistrations,
             List.of(),
             config,
             connectorProviders,
             lifecycleGuard);
    }

    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    Config config,
                    List<ConnectorProvider> connectorProviders) {
        this(consumerRegistrations,
             List.of(),
             config,
             connectorProviders,
             new MessagingLifecycleGuard());
    }

    ChannelRegistry(List<ConsumerRegistration> consumerRegistrations,
                    List<EmitterRegistration> emitterRegistrations,
                    Config config,
                    List<ConnectorProvider> connectorProviders) {
        this(consumerRegistrations,
             emitterRegistrations,
             config,
             connectorProviders,
             new MessagingLifecycleGuard());
    }

    private void initialize(List<ConsumerRegistration> consumerRegistrations,
                            List<EmitterRegistration> emitterRegistrations,
                            Config config,
                            List<ConnectorProvider> connectorProviders) {
        validateRegistrationIdentities(consumerRegistrations, emitterRegistrations);
        validateRegistrationTypeMetadata(consumerRegistrations, emitterRegistrations);
        Map<String, PayloadContribution> payloadContributions =
                validateChannelPayloadContributions(consumerRegistrations, emitterRegistrations);
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

        Map<String, ConnectorProvider> providers = connectorProviders(connectorProviders);
        List<OutgoingBinding> outgoingBindings = prepareOutgoingBindings(config, providers);
        List<IncomingDescriptor> incomingDescriptors = prepareIncomingDescriptors(config, providers);
        Set<String> outputChannels = new LinkedHashSet<>(grouped.keySet());
        outgoingBindings.stream().map(OutgoingBinding::channel).forEach(outputChannels::add);
        validateGeneratedProducerTargets(consumerRegistrations,
                                         emitterRegistrations,
                                         grouped,
                                         outputChannels);
        validateFailureRoutes(incomingDescriptors, outputChannels, grouped, payloadContributions);
        validateIncomingOutputs(incomingDescriptors, outputChannels);
        registerProcessorRoutes(consumerRegistrations);

        configureOutgoingConnectors(outgoingBindings);
        configureIncomingConnectors(incomingDescriptors);
    }

    /**
     * Emit a batch of messages to a named channel.
     * <p>
     * This method returns only after every required output completes successfully. Output failures are propagated
     * unchanged.
     *
     * @param channel channel name
     * @param messages immutable message batch
     * @param <T> payload type
     * @throws MessagingException if the channel does not exist
     * @throws RuntimeException if an output fails
     */
    @Override
    public <T> void emitBatch(String channel, MessageBatch<? extends T> messages) {
        MessagingChannel<?> messagingChannel = channels.get(channel);
        if (messagingChannel == null) {
            throw new MessagingException("Unknown messaging channel " + channel);
        }
        graph.ensureRunning();
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

    @Service.PostConstruct
    void start() {
        graph.start();
    }

    @Service.PreDestroy
    public void close() {
        graph.close();
    }

    /**
     * Add an outgoing connector to a named channel.
     *
     * @param channel channel name
     * @param connector outgoing connector
     */
    void addOutgoingConnector(String channel, OutgoingConnector connector) {
        MessagingChannel<?> messagingChannel = channels.get(channel);
        if (messagingChannel == null) {
            throw new IllegalArgumentException("Unknown messaging channel " + channel);
        }
        if (!(messagingChannel instanceof DefaultMessagingChannel<?> defaultMessagingChannel)) {
            throw new IllegalArgumentException("Unsupported channel implementation "
                                                       + messagingChannel.getClass().getName());
        }
        graph.addBinding(connector);
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

    private void validateRegistrationIdentities(List<ConsumerRegistration> consumerRegistrations,
                                                List<EmitterRegistration> emitterRegistrations) {
        Set<String> handlerIds = new LinkedHashSet<>();
        for (ConsumerRegistration registration : consumerRegistrations) {
            String handlerId = requireRegistrationIdentity("Handler", registration.handlerId());
            if (!handlerIds.add(handlerId)) {
                throw new IllegalArgumentException("Duplicate messaging handler registration " + handlerId);
            }
        }

        Set<String> producerIds = new LinkedHashSet<>();
        for (EmitterRegistration registration : emitterRegistrations) {
            String producerId = requireRegistrationIdentity("Producer", registration.producerId());
            if (!producerIds.add(producerId)) {
                throw new IllegalArgumentException("Duplicate messaging producer registration " + producerId);
            }
        }
    }

    private String requireRegistrationIdentity(String kind, String identity) {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException(kind + " registration identity must not be blank");
        }
        return identity;
    }

    private void validateRegistrationTypeMetadata(List<ConsumerRegistration> consumerRegistrations,
                                                  List<EmitterRegistration> emitterRegistrations) {
        for (ConsumerRegistration registration : consumerRegistrations) {
            String handler = "Messaging handler " + registration.handlerId();
            GenericType<?> payloadType = registration.payloadGenericType();
            GenericType<?> envelopeType = registration.envelopeGenericType();
            validateRawType(handler + " payload",
                            registration.payloadType(),
                            payloadType);
            validateRawType(handler + " envelope",
                            registration.envelopeType(),
                            envelopeType);
            validateEnvelopePayloadType(handler, payloadType, envelopeType);
            if (registration instanceof ProcessorRegistration processor) {
                String outgoing = "Messaging processor " + processor.handlerId() + " outgoing";
                GenericType<?> outgoingPayloadType = processor.outgoingPayloadGenericType();
                GenericType<?> outgoingEnvelopeType = processor.outgoingEnvelopeGenericType();
                validateRawType(outgoing + " payload",
                                processor.outgoingPayloadType(),
                                outgoingPayloadType);
                validateRawType(outgoing + " envelope",
                                processor.outgoingEnvelopeType(),
                                outgoingEnvelopeType);
                validateEnvelopePayloadType(outgoing, outgoingPayloadType, outgoingEnvelopeType);
            }
        }
        for (EmitterRegistration registration : emitterRegistrations) {
            String emitter = "Messaging emitter " + registration.producerId();
            GenericType<?> payloadType = registration.payloadGenericType();
            GenericType<?> envelopeType = registration.envelopeGenericType();
            validateRawType(emitter + " payload", registration.payloadType(), payloadType);
            validateRawType(emitter + " envelope", registration.envelopeType(), envelopeType);
            validateEnvelopePayloadType(emitter, payloadType, envelopeType);
        }
    }

    private void validateRawType(String source, Class<?> rawType, GenericType<?> genericType) {
        Class<?> actualRawType = Objects.requireNonNull(rawType, source + " raw type");
        GenericType<?> actualGenericType = Objects.requireNonNull(genericType, source + " generic type");
        Class<?> genericRawType = actualGenericType.rawType();
        if (actualRawType.isPrimitive()) {
            throw new IllegalArgumentException(source + " raw type must not be primitive: " + actualRawType.getName());
        }
        if (genericRawType.isPrimitive()) {
            throw new IllegalArgumentException(source + " generic raw type must not be primitive: "
                                                       + genericRawType.getName());
        }
        if (!actualRawType.equals(genericRawType)) {
            throw new IllegalArgumentException(source + " raw type " + actualRawType.getName()
                                                       + " does not match generic raw type "
                                                       + genericRawType.getName());
        }
    }

    private void validateEnvelopePayloadType(String source,
                                             GenericType<?> payloadType,
                                             GenericType<?> envelopeType) {
        if (!Message.class.isAssignableFrom(envelopeType.rawType())) {
            throw new IllegalArgumentException(source + " envelope type " + typeName(envelopeType)
                                                       + " must implement " + Message.class.getName());
        }
        Type resolvedMessageType = resolveSupertype(envelopeType.type(), Message.class);
        if (!(resolvedMessageType instanceof ParameterizedType messageType)) {
            return;
        }
        Type[] arguments = messageType.getActualTypeArguments();
        if (arguments.length != 1 || hasUnresolvedType(arguments[0])) {
            return;
        }
        Type envelopePayloadType = arguments[0];
        if (!equivalentType(payloadType.type(), envelopePayloadType)) {
            throw new IllegalArgumentException(source + " payload generic type " + typeName(payloadType)
                                                       + " does not match envelope payload type "
                                                       + typeName(envelopePayloadType) + " declared by "
                                                       + typeName(envelopeType));
        }
    }

    private boolean hasUnresolvedType(Type type) {
        if (type instanceof TypeVariable<?>) {
            return true;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type owner = parameterizedType.getOwnerType();
            if (owner != null && hasUnresolvedType(owner)) {
                return true;
            }
            return Arrays.stream(parameterizedType.getActualTypeArguments()).anyMatch(this::hasUnresolvedType);
        }
        if (type instanceof GenericArrayType arrayType) {
            return hasUnresolvedType(arrayType.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcardType) {
            return Arrays.stream(wildcardType.getLowerBounds()).anyMatch(this::hasUnresolvedType)
                    || Arrays.stream(wildcardType.getUpperBounds()).anyMatch(this::hasUnresolvedType);
        }
        return false;
    }

    private boolean equivalentType(Type first, Type second) {
        if (sameType(first, second)) {
            return true;
        }
        Type firstComponent = arrayComponent(first);
        Type secondComponent = arrayComponent(second);
        if (firstComponent != null || secondComponent != null) {
            return firstComponent != null
                    && secondComponent != null
                    && equivalentType(firstComponent, secondComponent);
        }
        if (first instanceof ParameterizedType firstParameterized
                && second instanceof ParameterizedType secondParameterized) {
            if (!sameType(firstParameterized.getRawType(), secondParameterized.getRawType())
                    || !equivalentNullableType(firstParameterized.getOwnerType(), secondParameterized.getOwnerType())) {
                return false;
            }
            return equivalentTypes(firstParameterized.getActualTypeArguments(),
                                   secondParameterized.getActualTypeArguments());
        }
        if (first instanceof WildcardType firstWildcard && second instanceof WildcardType secondWildcard) {
            return equivalentTypes(firstWildcard.getLowerBounds(), secondWildcard.getLowerBounds())
                    && equivalentTypes(firstWildcard.getUpperBounds(), secondWildcard.getUpperBounds());
        }
        return false;
    }

    private boolean equivalentNullableType(Type first, Type second) {
        return first == null ? second == null : second != null && equivalentType(first, second);
    }

    private boolean equivalentTypes(Type[] first, Type[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < first.length; i++) {
            if (!equivalentType(first[i], second[i])) {
                return false;
            }
        }
        return true;
    }

    private Map<String, PayloadContribution> validateChannelPayloadContributions(
            List<ConsumerRegistration> consumerRegistrations,
            List<EmitterRegistration> emitterRegistrations) {
        Map<String, PayloadContribution> payloadTypes = new LinkedHashMap<>();
        for (ConsumerRegistration registration : consumerRegistrations) {
            addPayloadContribution(payloadTypes,
                                   registration.channel(),
                                   registration.payloadGenericType(),
                                   "handler " + registration.handlerId());
            if (registration instanceof ProcessorRegistration processor) {
                addPayloadContribution(payloadTypes,
                                       processor.outgoingChannel(),
                                       processor.outgoingPayloadGenericType(),
                                       "processor " + processor.handlerId());
            }
        }
        for (EmitterRegistration emitter : emitterRegistrations) {
            addPayloadContribution(payloadTypes,
                                   emitter.channel(),
                                   emitter.payloadGenericType(),
                                   "emitter " + emitter.producerId());
        }
        return Map.copyOf(payloadTypes);
    }

    private void addPayloadContribution(Map<String, PayloadContribution> payloadTypes,
                                        String channel,
                                        GenericType<?> payloadType,
                                        String source) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("Messaging channel contributed by " + source + " must not be blank");
        }
        GenericType<?> actualType = Objects.requireNonNull(payloadType, "Payload type contributed by " + source);
        PayloadContribution contribution = new PayloadContribution(actualType, source);
        PayloadContribution existing = payloadTypes.putIfAbsent(channel, contribution);
        if (existing != null && !existing.payloadType().equals(actualType)) {
            throw new IllegalArgumentException("Channel " + channel + " has conflicting payload types "
                                                       + typeName(existing.payloadType()) + " from "
                                                       + existing.source() + " and " + typeName(actualType)
                                                       + " from " + source);
        }
    }

    private String typeName(GenericType<?> type) {
        return typeName(type.type());
    }

    private String typeName(Type type) {
        if (type instanceof Class<?> classType) {
            return classType.getTypeName();
        }
        if (type instanceof GenericArrayType arrayType) {
            return typeName(arrayType.getGenericComponentType()) + "[]";
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return typeName(parameterizedType.getRawType()) + "<"
                    + Arrays.stream(parameterizedType.getActualTypeArguments())
                    .map(this::typeName)
                    .collect(Collectors.joining(", "))
                    + ">";
        }
        if (type instanceof WildcardType wildcardType) {
            Type[] lowerBounds = wildcardType.getLowerBounds();
            if (lowerBounds.length > 0) {
                return "? super " + Arrays.stream(lowerBounds)
                        .map(this::typeName)
                        .collect(Collectors.joining(" & "));
            }
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length == 0
                    || (upperBounds.length == 1 && upperBounds[0].equals(Object.class))) {
                return "?";
            }
            return "? extends " + Arrays.stream(upperBounds)
                    .map(this::typeName)
                    .collect(Collectors.joining(" & "));
        }
        return type.getTypeName();
    }

    private void validateGeneratedProducerTargets(List<ConsumerRegistration> consumerRegistrations,
                                                  List<EmitterRegistration> emitterRegistrations,
                                                  Map<String, List<ConsumerRegistration>> groupedConsumers,
                                                  Set<String> outputChannels) {
        for (ConsumerRegistration registration : consumerRegistrations) {
            if (registration instanceof ProcessorRegistration processor) {
                validateGeneratedProducerTarget("processor",
                                                processor.handlerId(),
                                                processor.outgoingChannel(),
                                                processor.outgoingPayloadGenericType(),
                                                processor.outgoingEnvelopeGenericType(),
                                                groupedConsumers,
                                                outputChannels);
            }
        }
        for (EmitterRegistration emitter : emitterRegistrations) {
            validateGeneratedProducerTarget("emitter",
                                            emitter.producerId(),
                                            emitter.channel(),
                                            emitter.payloadGenericType(),
                                            emitter.envelopeGenericType(),
                                            groupedConsumers,
                                            outputChannels);
        }
    }

    private void validateGeneratedProducerTarget(String kind,
                                                 String registrationId,
                                                 String targetChannel,
                                                 GenericType<?> payloadType,
                                                 GenericType<?> envelopeType,
                                                 Map<String, List<ConsumerRegistration>> groupedConsumers,
                                                 Set<String> outputChannels) {
        String target = targetChannel == null ? "" : targetChannel;
        if (target.isBlank()) {
            throw new IllegalArgumentException("Messaging " + kind + " " + registrationId
                                                       + " target channel must not be blank");
        }
        if (!channels.containsKey(target)) {
            throw new IllegalArgumentException("Unknown messaging " + kind + " target channel " + target
                                                       + " for " + registrationId);
        }
        if (!outputChannels.contains(target)) {
            throw new IllegalArgumentException("Messaging " + kind + " target channel " + target
                                                       + " has no outputs for " + registrationId);
        }

        GenericType<?> producedPayload = Objects.requireNonNull(payloadType,
                                                                 kind + " payload type for " + registrationId);
        GenericType<?> producedEnvelope = Objects.requireNonNull(envelopeType,
                                                                  kind + " envelope type for " + registrationId);
        for (ConsumerRegistration targetConsumer : groupedConsumers.getOrDefault(target, List.of())) {
            if (!producedPayload.equals(targetConsumer.payloadGenericType())) {
                throw new IllegalArgumentException("Messaging " + kind + " " + registrationId
                                                           + " produces payload type " + producedPayload.getTypeName()
                                                           + " but target channel " + target + " expects "
                                                           + targetConsumer.payloadGenericType().getTypeName());
            }
            if (!typeAccepts(targetConsumer.envelopeGenericType().type(), producedEnvelope.type())) {
                throw new IllegalArgumentException("Messaging " + kind + " " + registrationId
                                                           + " produces envelope type " + producedEnvelope.getTypeName()
                                                           + " that target channel " + target + " cannot accept as "
                                                           + targetConsumer.envelopeGenericType().getTypeName());
            }
        }
    }

    private void registerProcessorRoutes(List<ConsumerRegistration> consumerRegistrations) {
        for (ConsumerRegistration registration : consumerRegistrations) {
            if (registration instanceof ProcessorRegistration processor) {
                graph.addRoute(processor.channel(), processor.outgoingChannel());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private MessagingChannel<?> createChannel(Config config,
                                              String channel,
                                              List<ConsumerRegistration> consumerRegistrations) {
        GenericType<Object> payloadType = (GenericType<Object>) payloadType(channel, consumerRegistrations);
        DefaultMessagingChannel.Builder<Object> builder = new DefaultMessagingChannel.Builder<>();
        builder.messagingGraph(graph, channel, executionConfig(config, channel))
                .payloadType(payloadType);
        builder.addBatchValidator(messages -> validateMessageTypes(consumerRegistrations, messages));
        for (ConsumerRegistration consumer : consumerRegistrations) {
            if (consumer instanceof ProcessorRegistration processor) {
                builder.addBatchOutput(messages -> processAndRoute(processor, messages));
            } else {
                builder.addBatchOutput(consumer::dispatch);
            }
        }
        return builder.build();
    }

    private void processAndRoute(ProcessorRegistration processor, MessageBatch<?> messages) {
        MessageBatch<?> result = processor.process(messages);
        if (result == null) {
            throw new MessagingException("Messaging processor " + processor.handlerId() + " returned a null batch");
        }
        if (!messages.sameDelivery(result)) {
            throw new MessagingException("Messaging processor " + processor.handlerId()
                                                 + " did not preserve batch delivery lineage");
        }
        Class<?> outgoingEnvelopeType = processor.outgoingEnvelopeType();
        Class<?> outgoingPayloadType = processor.outgoingPayloadType();
        for (Message<?> message : result) {
            if (!outgoingEnvelopeType.isInstance(message)) {
                throw new MessagingException("Messaging processor " + processor.handlerId()
                                                     + " declared outgoing envelope type "
                                                     + outgoingEnvelopeType.getName()
                                                     + " but returned " + message.getClass().getName());
            }
            Object entity = message.entity();
            if (entity != null && !outgoingPayloadType.isInstance(entity)) {
                throw new MessagingException("Messaging processor " + processor.handlerId()
                                                     + " declared outgoing payload type "
                                                     + outgoingPayloadType.getName()
                                                     + " but returned " + entity.getClass().getName());
            }
        }
        MessagingChannel<?> target = channels.get(processor.outgoingChannel());
        if (target == null) {
            throw new MessagingException("Unknown messaging processor target channel "
                                                 + processor.outgoingChannel());
        }
        emitBatch(target, result);
    }

    private MessagingChannel<?> createConfiguredChannel(Config config, String channel) {
        DefaultMessagingChannel.Builder<Object> builder = new DefaultMessagingChannel.Builder<>();
        builder.messagingGraph(graph, channel, executionConfig(config, channel))
                .payloadType(Object.class);
        return builder.build();
    }

    private <T> void emitBatch(MessagingChannel<?> messagingChannel,
                               MessageBatch<? extends T> messages) {
        if (!(messagingChannel instanceof DefaultMessagingChannel<?> defaultMessagingChannel)) {
            throw new MessagingException("Unsupported messaging channel implementation "
                                                 + messagingChannel.getClass().getName());
        }
        defaultMessagingChannel.emitBatchObject(messages);
    }

    private <T> void emitRoutedBatch(MessagingChannel<?> messagingChannel,
                                     MessageBatch<? extends T> messages) {
        if (!(messagingChannel instanceof DefaultMessagingChannel<?> defaultMessagingChannel)) {
            throw new MessagingException("Unsupported messaging channel implementation "
                                                 + messagingChannel.getClass().getName());
        }
        defaultMessagingChannel.emitRoutedBatchObject(messages);
    }

    private <T> void emitRoutedBatch(String channel, MessageBatch<? extends T> messages) {
        MessagingChannel<?> messagingChannel = channels.get(channel);
        if (messagingChannel == null) {
            throw new MessagingException("Unknown messaging channel " + channel);
        }
        emitRoutedBatch(messagingChannel, messages);
    }

    private GenericType<?> payloadType(String channel, List<ConsumerRegistration> consumerRegistrations) {
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
        return payloadType == null ? GenericType.OBJECT : payloadType;
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
        Type targetComponent = arrayComponent(target);
        if (targetComponent != null) {
            Type candidateComponent = arrayComponent(candidate);
            return candidateComponent != null && typeArgumentAccepts(targetComponent, candidateComponent);
        }
        return false;
    }

    private Type arrayComponent(Type type) {
        if (type instanceof GenericArrayType genericArray) {
            return genericArray.getGenericComponentType();
        }
        if (type instanceof Class<?> arrayType && arrayType.isArray()) {
            return arrayType.getComponentType();
        }
        return null;
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

    private void validateMessageTypes(ConsumerRegistration consumer, MessageBatch<?> messages) {
        for (Message<?> message : messages.messages()) {
            validateMessageType(consumer, message);
        }
    }

    private void validateMessageTypes(List<ConsumerRegistration> consumers, MessageBatch<?> messages) {
        for (ConsumerRegistration consumer : consumers) {
            validateMessageTypes(consumer, messages);
        }
    }

    private List<OutgoingBinding> prepareOutgoingBindings(Config root,
                                                          Map<String, ConnectorProvider> providers) {
        List<OutgoingBinding> bindings = new ArrayList<>();
        for (String channel : configuredChannels(root, ConnectorConfig.OUTGOING_PREFIX)) {
            Config channelConfig = root.get(ConnectorConfig.OUTGOING_PREFIX + channel);
            String connectorType = channelConfig.get(ConnectorConfig.CONNECTOR_ATTRIBUTE).asString().orElse(null);
            if (connectorType == null) {
                continue;
            }
            ConnectorProvider provider = providers.get(connectorType);
            if (provider == null) {
                throw new IllegalArgumentException("No connector provider of type " + connectorType
                                                           + " for outgoing channel " + channel);
            }
            if (!(provider instanceof OutgoingConnectorProvider outgoingProvider)) {
                throw new IllegalArgumentException("Connector provider type " + connectorType
                                                           + " does not support outgoing channel " + channel);
            }

            Config connectorConfig = connectorConfig(root,
                                                     channelConfig,
                                                     ConnectorConfig.Direction.OUTGOING,
                                                     channel,
                                                     connectorType);
            bindings.add(new OutgoingBinding(channel, connectorType, outgoingProvider, connectorConfig));
        }
        return List.copyOf(bindings);
    }

    private List<IncomingDescriptor> prepareIncomingDescriptors(Config root,
                                                                Map<String, ConnectorProvider> providers) {
        List<IncomingDescriptor> descriptors = new ArrayList<>();
        for (String channel : configuredChannels(root, ConnectorConfig.INCOMING_PREFIX)) {
            Config channelConfig = root.get(ConnectorConfig.INCOMING_PREFIX + channel);
            String connectorType = channelConfig.get(ConnectorConfig.CONNECTOR_ATTRIBUTE).asString().orElse(null);
            if (connectorType == null) {
                continue;
            }
            ConnectorProvider provider = providers.get(connectorType);
            if (provider == null) {
                throw new IllegalArgumentException("No connector provider of type " + connectorType
                                                           + " for incoming channel " + channel);
            }
            if (!(provider instanceof IncomingConnectorProvider incomingProvider)) {
                throw new IllegalArgumentException("Connector provider type " + connectorType
                                                           + " does not support incoming channel " + channel);
            }

            Config connectorConfig = connectorConfig(root,
                                                     channelConfig,
                                                     ConnectorConfig.Direction.INCOMING,
                                                     channel,
                                                     connectorType);
            FailurePolicy failurePolicy = FailurePolicy.create(channelConfig.get("failure"));
            descriptors.add(new IncomingDescriptor(channel,
                                                   connectorType,
                                                   failurePolicy,
                                                   incomingProvider,
                                                   connectorConfig));
        }
        return List.copyOf(descriptors);
    }

    private void configureOutgoingConnectors(List<OutgoingBinding> bindings) {
        for (OutgoingBinding binding : bindings) {
            OutgoingConnector connector = Objects.requireNonNull(
                    binding.provider().createOutgoingConnector(binding.config()),
                    "Outgoing connector");
            addOutgoingConnector(binding.channel(), connector);
        }
    }

    private void configureIncomingConnectors(List<IncomingDescriptor> descriptors) {
        for (IncomingDescriptor descriptor : descriptors) {
            ConnectorSourceContext context = incomingContext(descriptor.channel(), descriptor.failurePolicy());
            IncomingConnector connector = Objects.requireNonNull(
                    descriptor.provider().createIncomingConnector(descriptor.config()),
                    "Incoming connector");
            graph.addIncomingConnector("connector-" + descriptor.channel(), connector, context);
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
                                       Map<String, List<ConsumerRegistration>> consumers,
                                       Map<String, PayloadContribution> payloadContributions) {
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
            validateDeadLetterPayload(source, target, payloadContributions);
            validateDeadLetterConsumers(source, target, consumers.getOrDefault(target, List.of()));
            routes.put(source, target);
        }
        validateFailureRouteCycles(routes);
        routes.forEach(graph::addRoute);
    }

    private void validateDeadLetterPayload(String source,
                                           String target,
                                           Map<String, PayloadContribution> payloadContributions) {
        PayloadContribution sourcePayload = payloadContributions.get(source);
        PayloadContribution targetPayload = payloadContributions.get(target);
        if (sourcePayload != null
                && targetPayload != null
                && !sourcePayload.payloadType().equals(targetPayload.payloadType())) {
            throw new IllegalArgumentException("Dead-letter channel " + target
                                                       + " has payload type " + typeName(targetPayload.payloadType())
                                                       + " but incoming channel " + source
                                                       + " has payload type " + typeName(sourcePayload.payloadType()));
        }
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

    private Map<String, ConnectorProvider> connectorProviders(List<ConnectorProvider> connectorProviders) {
        Map<String, ConnectorProvider> providers = new HashMap<>();
        for (ConnectorProvider provider : connectorProviders) {
            Objects.requireNonNull(provider, "Connector provider");
            String connectorType = Objects.requireNonNull(provider.connectorType(), "Connector provider type");
            if (connectorType.isBlank()) {
                throw new IllegalArgumentException("Connector provider type must not be blank");
            }
            ConnectorProvider previous = providers.putIfAbsent(connectorType, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate connector provider type " + connectorType);
            }
        }
        return Map.copyOf(providers);
    }

    private Set<String> configuredChannels(Config root, String prefix) {
        Config config = root.get(prefix.substring(0, prefix.length() - 1));
        if (!config.exists()) {
            return Set.of();
        }

        Set<String> channels = new TreeSet<>();
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

    private Config connectorConfig(Config root,
                                   Config channelConfig,
                                   ConnectorConfig.Direction direction,
                                   String channel,
                                   String connector) {
        Map<String, String> properties = connectorProperties(root, channelConfig, connector);
        properties.put(ConnectorConfig.CHANNEL_NAME_ATTRIBUTE, channel);
        properties.put(ConnectorConfig.CONNECTOR_ATTRIBUTE, connector);
        properties.put("direction", direction.name());
        return Config.just(ConfigSources.create(properties));
    }

    private Map<String, String> connectorProperties(Config root, Config channelConfig, String connector) {
        Map<String, String> properties = new LinkedHashMap<>();
        root.get(ConnectorConfig.CONNECTOR_PREFIX + connector).detach().asMap().ifPresent(properties::putAll);
        channelConfig.detach().asMap().ifPresent(properties::putAll);
        properties.keySet().removeIf(key -> key.equals("failure") || key.startsWith("failure."));
        return properties;
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
        public Optional<Duration> admissionTimeout() {
            return deliveryEngine.admissionTimeout(channel);
        }

        @Override
        public ConnectorDeliveryReservation reserveDelivery(int maxMessages) {
            return deliveryEngine.reserveConnectorDelivery(channel, maxMessages);
        }

        @Override
        public Optional<ConnectorDeliveryReservation> tryReserveDelivery(int maxMessages) {
            return deliveryEngine.tryReserveConnectorDelivery(channel, maxMessages);
        }

        @Override
        public <T> void emit(Message<T> message) {
            ChannelRegistry.this.emit(channel, message);
        }

        @Override
        public <T> void emitBatch(MessageBatch<T> messages) {
            ChannelRegistry.this.emitBatch(channel, messages);
        }

        @Override
        public <T> ConnectorDelivery submitDelivery(MessageBatch<T> messages, Runnable delivery) {
            return deliveryEngine.submitConnectorDelivery(channel, messages, delivery);
        }

        @Override
        public <T> Optional<ConnectorDelivery> trySubmitDelivery(MessageBatch<T> messages, Runnable delivery) {
            return deliveryEngine.trySubmitConnectorDelivery(channel, messages, delivery);
        }

        @Override
        public <T> FailureResult handleFailure(MessageBatch<T> messages,
                                               int failedAttempt,
                                               RuntimeException failure) {
            if (failedAttempt < 1) {
                throw new IllegalArgumentException("failedAttempt must be greater than zero");
            }
            RuntimeException alignedFailure = BatchDeliveryException.align(messages, failure);
            if (failurePolicy.maxAttempts() == 0 || failedAttempt < failurePolicy.maxAttempts()) {
                return FailureResult.RETRY;
            }

            return switch (failurePolicy.onExhausted()) {
            case FAIL -> throw alignedFailure;
            case DROP -> {
                LOGGER.log(System.Logger.Level.WARNING,
                           "Messaging delivery failed after " + failedAttempt
                                   + " attempt(s); dropping " + messages.size()
                                   + " message(s) from channel " + channel,
                           alignedFailure);
                yield FailureResult.SETTLED;
            }
            case DEAD_LETTER -> {
                String target = failurePolicy.deadLetterChannel().orElseThrow();
                List<DeadLetterMessage<T>> deadLetters = new ArrayList<>(messages.size());
                for (int i = 0; i < messages.size(); i++) {
                    deadLetters.add(DeadLetterMessage.create(messages.get(i),
                                                             channel,
                                                             failedAttempt,
                                                             deadLetterFailure(messages, i, alignedFailure)));
                }
                MessageBatch<T> deadLetterBatch = messages.derive(deadLetters);
                try {
                    ChannelRegistry.this.emitRoutedBatch(target, deadLetterBatch);
                } catch (RuntimeException routeFailure) {
                    RuntimeException result;
                    if (routeFailure instanceof BatchDeliveryException batchFailure
                            && deadLetterBatch.sameDelivery(batchFailure.batch())) {
                        result = new BatchDeliveryException(
                                "Dead-letter delivery from channel " + channel + " to channel " + target + " failed",
                                messages,
                                batchFailure.outcomes(),
                                batchFailure);
                    } else {
                        result = BatchDeliveryException.indeterminate(
                                "Dead-letter delivery from channel " + channel + " to channel " + target,
                                messages,
                                routeFailure);
                    }
                    result.addSuppressed(alignedFailure);
                    throw result;
                }
                yield FailureResult.SETTLED;
            }
            };
        }

        private RuntimeException deadLetterFailure(MessageBatch<?> batch,
                                                   int index,
                                                   RuntimeException failure) {
            Set<RuntimeException> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            RuntimeException current = failure;
            while (visited.add(current) && current instanceof BatchDeliveryException batchFailure) {
                int failureIndex = batch.lineageIndexIn(batchFailure.batch(), index);
                if (failureIndex < 0) {
                    break;
                }
                Throwable itemFailure = batchFailure.outcome(failureIndex).failure().orElse(batchFailure.getCause());
                if (!(itemFailure instanceof RuntimeException nested)) {
                    break;
                }
                current = nested;
            }
            return current;
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

    private record PayloadContribution(GenericType<?> payloadType, String source) {
    }

    private record OutgoingBinding(String channel,
                                   String connectorType,
                                   OutgoingConnectorProvider provider,
                                   Config config) {
    }

    private record IncomingDescriptor(String channel,
                                      String connectorType,
                                      FailurePolicy failurePolicy,
                                      IncomingConnectorProvider provider,
                                      Config config) {
    }

}
