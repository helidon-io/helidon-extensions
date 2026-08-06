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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One validated messaging topology and lifecycle.
 */
final class DefaultMessagingGraph implements MessagingGraph {
    private static final AtomicLong LIFECYCLE_SEQUENCE = new AtomicLong();

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final DeliveryEngine deliveryEngine;
    private final Map<String, MessagingChannel<?>> channels = new LinkedHashMap<>();
    private final Map<MessagingChannel<?>, Emitter<?>> emitters = new IdentityHashMap<>();
    private final Map<String, Set<String>> routes = new LinkedHashMap<>();
    private final Map<String, SourceBinding> sources = new LinkedHashMap<>();
    private final Set<ConnectorSource> sourceIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<ConnectorEndpoint> connectorBindings = new ArrayList<>();
    private final List<OutgoingEndpoint> outgoingEndpoints = new ArrayList<>();
    private final Set<ConnectorEndpoint> connectorEndpointIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean shutdownOwner = new AtomicBoolean();
    private final CompletableFuture<Void> preparationCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> startupCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> shutdownCompletion = new CompletableFuture<>();
    private boolean preparationInProgress;
    private boolean sealed;
    private volatile State state = State.NEW;
    private volatile Throwable failure;

    DefaultMessagingGraph(DeliveryEngine deliveryEngine) {
        this.deliveryEngine = Objects.requireNonNull(deliveryEngine);
    }

    DeliveryEngine deliveryEngine() {
        return deliveryEngine;
    }

    State state() {
        return state;
    }

    Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Emitter<T> emitter(MessagingChannel<T> channel) {
        Objects.requireNonNull(channel);
        lifecycleLock.lock();
        try {
            Emitter<?> emitter = emitters.get(channel);
            if (emitter == null) {
                throw new IllegalArgumentException("Messaging channel " + channel.name()
                                                           + " is not owned by this messaging graph");
            }
            return (Emitter<T>) emitter;
        } finally {
            lifecycleLock.unlock();
        }
    }

    <T> void addEmitter(MessagingChannel<T> channel, Emitter<T> emitter) {
        Objects.requireNonNull(channel);
        Objects.requireNonNull(emitter);
        lifecycleLock.lock();
        try {
            requireMutable();
            if (emitters.containsKey(channel)) {
                throw new IllegalArgumentException("Messaging channel handle is already registered");
            }
            emitters.put(channel, new GraphEmitter<>(this, emitter));
        } finally {
            lifecycleLock.unlock();
        }
    }

    void addChannel(String name,
                    MessagingChannel<?> channel,
                    MessagingExecutionConfig executionConfig) {
        addChannelContribution(name, channel, executionConfig, Map.of(), List.of(), List.of());
    }

    void addChannelContribution(String name,
                                MessagingChannel<?> channel,
                                MessagingExecutionConfig executionConfig,
                                Map<String, ConnectorSource> channelSources,
                                List<?> bindings,
                                List<String> inputChannels) {
        addChannelContribution(name,
                               channel,
                               executionConfig,
                               channelSources,
                               bindings,
                               inputChannels,
                               () -> { });
    }

    void addChannelContribution(String name,
                                MessagingChannel<?> channel,
                                MessagingExecutionConfig executionConfig,
                                Map<String, ConnectorSource> channelSources,
                                List<?> bindings,
                                List<String> inputChannels,
                                Runnable connectInputs) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(channel);
        Objects.requireNonNull(executionConfig);
        Objects.requireNonNull(channelSources);
        Objects.requireNonNull(bindings);
        Objects.requireNonNull(inputChannels);
        Objects.requireNonNull(connectInputs);
        lifecycleLock.lock();
        try {
            requireMutable();
            if (channels.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate messaging channel " + name);
            }
            validateContributionSources(channelSources);
            validateContributionBindings(bindings, channelSources.values());
            Set<String> uniqueInputs = new LinkedHashSet<>(inputChannels);
            if (uniqueInputs.size() != inputChannels.size()) {
                throw new IllegalArgumentException("Duplicate imperative input channel for " + name);
            }
            for (String inputChannel : uniqueInputs) {
                if (!channels.containsKey(inputChannel)) {
                    throw new IllegalArgumentException("Unknown imperative input channel " + inputChannel);
                }
            }

            deliveryEngine.registerChannel(name, executionConfig);
            channels.put(name, channel);
            channelSources.forEach(this::addSourceLocked);
            bindings.forEach(this::addBindingLocked);
            for (String inputChannel : uniqueInputs) {
                routes.computeIfAbsent(inputChannel, ignored -> new LinkedHashSet<>()).add(name);
            }
            connectInputs.run();
        } finally {
            lifecycleLock.unlock();
        }
    }

    Optional<MessagingChannel<?>> channel(String name) {
        lifecycleLock.lock();
        try {
            return Optional.ofNullable(channels.get(name));
        } finally {
            lifecycleLock.unlock();
        }
    }

    void addRoute(String source, String target) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        lifecycleLock.lock();
        try {
            requireMutable();
            routes.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
        } finally {
            lifecycleLock.unlock();
        }
    }

    void addBinding(Object binding) {
        lifecycleLock.lock();
        try {
            requireMutable();
            validateContributionBindings(List.of(binding), List.of());
            addBindingLocked(binding);
        } finally {
            lifecycleLock.unlock();
        }
    }

    void addSource(String name, ConnectorSource source) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(source);
        lifecycleLock.lock();
        try {
            requireMutable();
            validateContributionSources(Map.of(name, source));
            validateContributionBindings(List.of(), List.of(source));
            addSourceLocked(name, source);
        } finally {
            lifecycleLock.unlock();
        }
    }

    void seal() {
        lifecycleLock.lock();
        try {
            requireMutable();
            validateTopology();
            sealed = true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    void prepare() {
        prepareIfNeeded(true);
    }

    private void prepareIfNeeded(boolean rejectIfNotNeeded) {
        boolean preparationOwner = false;
        boolean preparationWaiter = false;
        lifecycleLock.lock();
        try {
            if (preparationInProgress) {
                preparationWaiter = true;
            } else if (state == State.NEW) {
                preparationInProgress = true;
                preparationOwner = true;
            } else if (state != State.PREPARED && rejectIfNotNeeded) {
                throw illegalTransition("prepare");
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (preparationWaiter) {
            awaitPreparation();
            return;
        }
        if (!preparationOwner) {
            return;
        }

        Throwable preparationFailure = null;
        try {
            prepareGraph(deadline(deliveryEngine.shutdownTimeout()));
        } catch (RuntimeException | Error e) {
            preparationFailure = e;
        }

        boolean cleanup = false;
        lifecycleLock.lock();
        try {
            if (preparationFailure == null) {
                if (state == State.NEW) {
                    state = State.PREPARED;
                } else {
                    preparationFailure = new MessagingException("Messaging graph preparation was cancelled in state "
                                                                        + state);
                }
            }
            if (preparationFailure != null && state == State.NEW) {
                cleanup = transitionToFailed(preparationFailure);
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (cleanup) {
            rollback(preparationFailure, false);
        }
        finishPreparation(preparationFailure);
        if (preparationFailure != null) {
            rethrow(preparationFailure);
        }
    }

    @Override
    public void start() {
        prepareIfNeeded(false);

        boolean startOwner;
        lifecycleLock.lock();
        try {
            if (state == State.RUNNING) {
                return;
            }
            if (state == State.STARTING) {
                startOwner = false;
            } else {
                if (state != State.PREPARED) {
                    throw illegalTransition("start");
                }
                state = State.STARTING;
                startOwner = true;
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (!startOwner) {
            awaitStartup();
            return;
        }

        long deadline = deadline(deliveryEngine.shutdownTimeout());
        try {
            startOutgoingEndpoints(deadline);
            for (SourceBinding source : sources.values()) {
                requireStarting();
                source.start(deliveryEngine);
            }
            for (SourceBinding source : sources.values()) {
                requireStarting();
                source.awaitReady(remaining(deadline));
            }
            startSourceAdmission(deadline);
            lifecycleLock.lock();
            try {
                requireStarting();
                state = State.RUNNING;
                startupCompletion.complete(null);
            } finally {
                lifecycleLock.unlock();
            }
            reportNormalSourceTerminations();
        } catch (RuntimeException | Error e) {
            boolean cleanup;
            lifecycleLock.lock();
            try {
                startupCompletion.completeExceptionally(e);
                cleanup = state == State.STARTING && transitionToFailed(e);
            } finally {
                lifecycleLock.unlock();
            }
            if (cleanup) {
                rollback(e, false);
            } else if (state == State.FAILED) {
                awaitShutdown();
            }
            throw e;
        }
    }

    private void startOutgoingEndpoints(long deadline) {
        for (OutgoingEndpoint outgoing : outgoingEndpoints) {
            requireStarting();
            OperationResult result = invokeBounded("start outgoing connector endpoint "
                                                           + outgoing.getClass().getName(),
                                                   deadline,
                                                   outgoing::start);
            if (result.failure() != null) {
                throw result.failure();
            }
        }
    }

    private void startSourceAdmission(long deadline) {
        for (SourceBinding source : sources.values()) {
            requireStarting();
            OperationResult result = invokeBounded("start admission for source " + source.name(),
                                                   deadline,
                                                   () -> {
                                                       requireStarting();
                                                       source.startAdmission();
                                                   });
            if (result.failure() != null) {
                throw result.failure();
            }
        }
    }

    void ensureRunning() {
        State current = state;
        if (current == State.RUNNING || current == State.DRAINING) {
            return;
        }
        if (current == State.FORCING || current == State.CLOSED || current == State.FAILED) {
            throw illegalTransition("emit through");
        }
        start();
    }

    private void requireEmissionPathOpen() {
        State current = state;
        if (current != State.RUNNING && current != State.DRAINING) {
            throw illegalTransition("emit through");
        }
    }

    void abortPreparation(Throwable cause) {
        Objects.requireNonNull(cause);
        boolean cleanup;
        lifecycleLock.lock();
        try {
            if (state == State.CLOSED || state == State.FAILED) {
                return;
            }
            cleanup = transitionToFailed(cause);
        } finally {
            lifecycleLock.unlock();
        }
        if (cleanup) {
            rollback(cause, false);
        }
    }

    @Override
    public void close() {
        boolean closeOwner;
        boolean graceful;
        lifecycleLock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            if (state == State.RUNNING) {
                deliveryEngine.beginDrain();
                state = State.DRAINING;
                graceful = true;
                closeOwner = shutdownOwner.compareAndSet(false, true);
            } else if (state == State.FAILED || state == State.DRAINING || state == State.FORCING) {
                graceful = false;
                closeOwner = false;
            } else {
                state = State.FORCING;
                graceful = false;
                closeOwner = shutdownOwner.compareAndSet(false, true);
                startupCompletion.completeExceptionally(new MessagingException("Messaging graph startup was cancelled"));
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (!closeOwner) {
            awaitShutdown();
            return;
        }

        RuntimeException closeFailure = null;
        try {
            closeFailure = graceful ? closeGracefully() : closeForced(null, "Messaging graph shutdown");
        } catch (RuntimeException e) {
            closeFailure = append(closeFailure, e);
        } catch (Error e) {
            closeFailure = append(closeFailure, new MessagingException("Messaging graph shutdown failed", e));
        } finally {
            lifecycleLock.lock();
            try {
                if (closeFailure == null) {
                    state = State.CLOSED;
                } else {
                    failure = closeFailure;
                    state = State.FAILED;
                }
            } finally {
                lifecycleLock.unlock();
            }
            if (closeFailure == null) {
                shutdownCompletion.complete(null);
            } else {
                shutdownCompletion.completeExceptionally(closeFailure);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private CleanupResult stopSourceAdmission(RuntimeException current, long deadline) {
        boolean failed = false;
        for (SourceBinding source : sources.values()) {
            if (source.incomingEndpoint() == null) {
                continue;
            }
            OperationResult result = invokeBounded("stop admission for source " + source.name(),
                                                   deadline,
                                                   source::stopAdmission);
            current = append(current, result.failure());
            failed |= result.failure() != null;
        }
        return new CleanupResult(current, failed);
    }

    private RuntimeException flushOutgoingEndpoints(RuntimeException current, long deadline) {
        for (int i = outgoingEndpoints.size() - 1; i >= 0; i--) {
            OutgoingEndpoint outgoing = outgoingEndpoints.get(i);
            OperationResult result = invokeBounded("flush outgoing connector endpoint "
                                                           + outgoing.getClass().getName(),
                                                   deadline,
                                                   outgoing::flush);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException checkpointIncomingEndpoints(RuntimeException current, long deadline) {
        List<SourceBinding> sourceBindings = new ArrayList<>(sources.values());
        for (int i = sourceBindings.size() - 1; i >= 0; i--) {
            IncomingEndpoint incoming = sourceBindings.get(i).incomingEndpoint();
            if (incoming == null) {
                continue;
            }
            OperationResult result = invokeBounded("checkpoint incoming connector endpoint "
                                                           + incoming.getClass().getName(),
                                                   deadline,
                                                   incoming::checkpoint);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException forceBindings(RuntimeException current,
                                           long deadline,
                                           ForcedCleanupOrdering cleanupOrdering) {
        for (int i = connectorBindings.size() - 1; i >= 0; i--) {
            ConnectorEndpoint binding = connectorBindings.get(i);
            CompletableFuture<Void> forceCompletion = cleanupOrdering.register(binding);
            OperationResult result = invokeCleanupBounded("force close connector binding "
                                                                  + binding.getClass().getName(),
                                                          deadline,
                                                          binding::forceClose,
                                                          forceCompletion);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException closeBindings(RuntimeException current, long deadline) {
        return closeBindings(current, deadline, null);
    }

    private RuntimeException closeBindings(RuntimeException current,
                                           long deadline,
                                           ForcedCleanupOrdering cleanupOrdering) {
        for (int i = connectorBindings.size() - 1; i >= 0; i--) {
            ConnectorEndpoint binding = connectorBindings.get(i);
            String operation = "close connector binding " + binding.getClass().getName();
            OperationResult result = cleanupOrdering == null
                    ? invokeCleanupBounded(operation, deadline, binding::close)
                    : invokeCleanupAfterForce(operation,
                                              deadline,
                                              binding::close,
                                              cleanupOrdering.forceCompletion(binding));
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException forceSources(RuntimeException current,
                                          long deadline,
                                          ForcedCleanupOrdering cleanupOrdering) {
        List<SourceBinding> sourceBindings = new ArrayList<>(sources.values());
        for (int i = sourceBindings.size() - 1; i >= 0; i--) {
            ConnectorEndpoint endpoint = sourceBindings.get(i).connectorEndpoint();
            if (endpoint == null) {
                continue;
            }
            CompletableFuture<Void> forceCompletion = cleanupOrdering.register(endpoint);
            OperationResult result = invokeCleanupBounded("force close connector source "
                                                                  + endpoint.getClass().getName(),
                                                          deadline,
                                                          endpoint::forceClose,
                                                          forceCompletion);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException closeSources(RuntimeException current, long deadline) {
        return closeSources(current, deadline, null);
    }

    private RuntimeException closeSources(RuntimeException current,
                                          long deadline,
                                          ForcedCleanupOrdering cleanupOrdering) {
        List<SourceBinding> sourceBindings = new ArrayList<>(sources.values());
        for (int i = sourceBindings.size() - 1; i >= 0; i--) {
            ConnectorEndpoint endpoint = sourceBindings.get(i).connectorEndpoint();
            if (endpoint == null) {
                continue;
            }
            String operation = "close connector source " + endpoint.getClass().getName();
            OperationResult result = cleanupOrdering == null
                    ? invokeCleanupBounded(operation, deadline, endpoint::close)
                    : invokeCleanupAfterForce(operation,
                                              deadline,
                                              endpoint::close,
                                              cleanupOrdering.forceCompletion(endpoint));
            current = append(current, result.failure());
        }
        return current;
    }

    private void rollback(Throwable primary, boolean reportFailureOnClose) {
        for (SourceBinding source : sources.values()) {
            source.cancelAdmission();
        }
        long cleanupDeadline = deadline(deliveryEngine.shutdownTimeout());
        ForcedCleanupOrdering cleanupOrdering = new ForcedCleanupOrdering();
        RuntimeException cleanupFailure = forceSources(null, cleanupDeadline, cleanupOrdering);
        deliveryEngine.forceShutdown();
        cleanupFailure = forceBindings(cleanupFailure, cleanupDeadline, cleanupOrdering);
        cleanupFailure = closeSources(cleanupFailure, cleanupDeadline, cleanupOrdering);
        cleanupFailure = closeBindings(cleanupFailure, cleanupDeadline, cleanupOrdering);
        boolean terminated = awaitTermination(cleanupDeadline);
        if (!terminated) {
            cleanupFailure = append(cleanupFailure,
                                    new MessagingException("Messaging startup rollback timed out after "
                                                                   + deliveryEngine.shutdownTimeout()));
        }
        if (cleanupFailure != null && cleanupFailure != primary) {
            primary.addSuppressed(cleanupFailure);
        }
        if (reportFailureOnClose) {
            shutdownCompletion.completeExceptionally(primary);
        } else {
            shutdownCompletion.complete(null);
        }
    }

    private void validateTopology() {
        for (Map.Entry<String, Set<String>> route : routes.entrySet()) {
            if (!channels.containsKey(route.getKey())) {
                throw new IllegalArgumentException("Unknown messaging route source " + route.getKey());
            }
            for (String target : route.getValue()) {
                if (!channels.containsKey(target)) {
                    throw new IllegalArgumentException("Unknown messaging route target " + target
                                                               + " from " + route.getKey());
                }
            }
        }

        Set<String> visited = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        List<String> path = new ArrayList<>();
        for (String channel : channels.keySet()) {
            visit(channel, visited, visiting, path);
        }
    }

    private void validateContributionSources(Map<String, ConnectorSource> contributionSources) {
        Set<ConnectorSource> newIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, ConnectorSource> entry : contributionSources.entrySet()) {
            String sourceName = Objects.requireNonNull(entry.getKey());
            ConnectorSource source = Objects.requireNonNull(entry.getValue());
            if (sources.containsKey(sourceName)) {
                throw new IllegalArgumentException("Duplicate messaging source " + sourceName);
            }
            if (sourceIdentities.contains(source) || !newIdentities.add(source)) {
                throw new IllegalArgumentException("Messaging source is already owned by this messaging graph");
            }
        }
    }

    private void validateContributionBindings(List<?> bindings,
                                              Iterable<? extends ConnectorSource> contributionSources) {
        Set<ConnectorEndpoint> newIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ConnectorSource source : contributionSources) {
            if (source instanceof ConnectorEndpoint endpoint
                    && (connectorEndpointIdentities.contains(endpoint) || !newIdentities.add(endpoint))) {
                throw new IllegalArgumentException("Connector endpoint is already owned by this messaging graph");
            }
        }
        for (Object binding : bindings) {
            Objects.requireNonNull(binding);
            if (binding instanceof ConnectorEndpoint endpoint
                    && (connectorEndpointIdentities.contains(endpoint) || !newIdentities.add(endpoint))) {
                throw new IllegalArgumentException("Connector endpoint is already owned by this messaging graph");
            }
        }
    }

    private void addSourceLocked(String name, ConnectorSource source) {
        ConnectorEndpoint connectorEndpoint = source instanceof ConnectorEndpoint endpoint ? endpoint : null;
        IncomingEndpoint incomingEndpoint = source instanceof IncomingEndpoint endpoint ? endpoint : null;
        sources.put(name, new SourceBinding(name, source, connectorEndpoint, incomingEndpoint));
        sourceIdentities.add(source);
        if (connectorEndpoint != null) {
            connectorEndpointIdentities.add(connectorEndpoint);
        }
    }

    private void addBindingLocked(Object binding) {
        if (binding instanceof ConnectorEndpoint endpoint) {
            connectorEndpointIdentities.add(endpoint);
            connectorBindings.add(endpoint);
            if (endpoint instanceof OutgoingEndpoint outgoingEndpoint) {
                outgoingEndpoints.add(outgoingEndpoint);
            }
        }
    }

    private void prepareGraph(long deadline) {
        validateTopology();
        for (SourceBinding source : sources.values()) {
            requirePreparing();
            OperationResult result = invokeBounded("prepare connector source " + source.name(),
                                                   deadline,
                                                   () -> {
                                                       requirePreparing();
                                                       source.prepare();
                                                   });
            if (result.failure() != null) {
                throw result.failure();
            }
        }
    }

    private void visit(String channel,
                       Set<String> visited,
                       Set<String> visiting,
                       List<String> path) {
        if (visited.contains(channel)) {
            return;
        }
        if (!visiting.add(channel)) {
            int cycleStart = path.indexOf(channel);
            List<String> cycle = new ArrayList<>(path.subList(cycleStart, path.size()));
            cycle.add(channel);
            throw new IllegalArgumentException("Cyclic synchronous messaging route: " + String.join(" -> ", cycle));
        }

        path.add(channel);
        for (String target : routes.getOrDefault(channel, Set.of())) {
            visit(target, visited, visiting, path);
        }
        path.removeLast();
        visiting.remove(channel);
        visited.add(channel);
    }

    private void requireMutable() {
        if (sealed) {
            throw new IllegalStateException("Cannot modify a built messaging graph");
        }
        if (preparationInProgress) {
            throw new IllegalStateException("Cannot modify messaging graph after preparation has started");
        }
        if (state != State.NEW) {
            throw illegalTransition("modify");
        }
    }

    private IllegalStateException illegalTransition(String operation) {
        return new IllegalStateException("Cannot " + operation + " messaging graph in state " + state
                                                 + "; closed and failed graphs cannot be restarted");
    }

    private void sourceFailed(String name, Throwable cause) {
        MessagingException sourceFailure;
        boolean cleanup;
        boolean reportFailureOnClose;
        lifecycleLock.lock();
        try {
            if (state != State.STARTING && state != State.RUNNING) {
                return;
            }
            reportFailureOnClose = state == State.RUNNING;
            sourceFailure = new MessagingException("Messaging source " + name + " failed", cause);
            cleanup = transitionToFailed(sourceFailure);
        } finally {
            lifecycleLock.unlock();
        }
        if (cleanup) {
            rollback(sourceFailure, reportFailureOnClose);
        }
    }

    private void reportNormalSourceTerminations() {
        sources.values().forEach(SourceBinding::reportNormalCompletion);
    }

    private static RuntimeException append(RuntimeException current, RuntimeException additional) {
        if (additional == null) {
            return current;
        }
        if (current == null) {
            return additional;
        }
        if (current == additional) {
            return current;
        }
        current.addSuppressed(additional);
        return current;
    }

    private RuntimeException closeGracefully() {
        RuntimeException closeFailure = null;
        long drainDeadline = deadline(deliveryEngine.shutdownTimeout());
        CleanupResult admissionStop = stopSourceAdmission(closeFailure, drainDeadline);
        closeFailure = admissionStop.failure();
        boolean drained = !admissionStop.failed() && awaitDrained(drainDeadline);
        closeFailure = collectSourceFailures(closeFailure);
        if (!drained || closeFailure != null) {
            transitionToForcing();
            String message = admissionStop.failed()
                    ? "Messaging graph source admission could not be stopped; forced shutdown was requested"
                    : !drained
                            ? "Messaging graph drain timed out after " + deliveryEngine.shutdownTimeout()
                                    + "; forced shutdown was requested"
                            : "Messaging source failed while draining; forced shutdown was requested";
            closeFailure = append(closeFailure, new MessagingException(message));
            return closeForced(closeFailure, "Messaging graph forced shutdown");
        }

        long cleanupDeadline = deadline(deliveryEngine.shutdownTimeout());
        closeFailure = flushOutgoingEndpoints(closeFailure, cleanupDeadline);
        if (closeFailure != null) {
            transitionToForcing();
            return closeForced(closeFailure, "Messaging graph endpoint flush failed");
        }
        closeFailure = checkpointIncomingEndpoints(closeFailure, cleanupDeadline);
        if (closeFailure != null) {
            transitionToForcing();
            return closeForced(closeFailure, "Messaging graph endpoint checkpoint failed");
        }
        closeFailure = closeSources(closeFailure, cleanupDeadline);
        if (closeFailure != null) {
            transitionToForcing();
            return closeForced(closeFailure, "Messaging graph endpoint forced shutdown");
        }
        deliveryEngine.forceShutdown();
        closeFailure = closeBindings(closeFailure, cleanupDeadline);
        if (closeFailure != null) {
            transitionToForcing();
            return closeForced(closeFailure, "Messaging graph endpoint forced shutdown");
        }
        if (!awaitTermination(cleanupDeadline)) {
            closeFailure = append(closeFailure,
                                  new MessagingException("Messaging graph endpoint shutdown timed out after "
                                                                 + deliveryEngine.shutdownTimeout()));
            transitionToForcing();
            return closeForced(closeFailure, "Messaging graph endpoint forced shutdown");
        }
        return closeFailure;
    }

    private RuntimeException closeForced(RuntimeException current, String operation) {
        for (SourceBinding source : sources.values()) {
            source.cancelAdmission();
        }
        long cleanupDeadline = deadline(deliveryEngine.shutdownTimeout());
        ForcedCleanupOrdering cleanupOrdering = new ForcedCleanupOrdering();
        RuntimeException closeFailure = forceSources(current, cleanupDeadline, cleanupOrdering);
        deliveryEngine.forceShutdown();
        closeFailure = forceBindings(closeFailure, cleanupDeadline, cleanupOrdering);
        closeFailure = closeSources(closeFailure, cleanupDeadline, cleanupOrdering);
        closeFailure = closeBindings(closeFailure, cleanupDeadline, cleanupOrdering);
        boolean terminated = awaitTermination(cleanupDeadline);
        if (!terminated) {
            closeFailure = append(closeFailure,
                                  new MessagingException(operation + " timed out after "
                                                                 + deliveryEngine.shutdownTimeout()));
        }
        return closeFailure;
    }

    private RuntimeException collectSourceFailures(RuntimeException current) {
        RuntimeException result = current;
        for (SourceBinding source : sources.values()) {
            Optional<Throwable> sourceFailure = source.takeFailure();
            if (sourceFailure.isEmpty()) {
                continue;
            }
            Throwable cause = sourceFailure.get();
            if (deliveryEngine.ownsShutdownRejection(cause)) {
                continue;
            }
            RuntimeException failure = cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new MessagingException("Messaging source " + source.name() + " failed during shutdown", cause);
            result = append(result, failure);
        }
        return result;
    }

    private boolean awaitDrained(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && deliveryEngine.awaitDrained(Duration.ofNanos(remaining));
    }

    private boolean awaitTermination(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining > 0 && deliveryEngine.awaitTermination(Duration.ofNanos(remaining));
    }

    private OperationResult invokeBounded(String operation, long deadline, Runnable action) {
        return invokeBounded(operation, deadline, action, false, new CompletableFuture<>(), true);
    }

    private OperationResult invokeCleanupBounded(String operation, long deadline, Runnable action) {
        return invokeCleanupBounded(operation, deadline, action, new CompletableFuture<>());
    }

    private OperationResult invokeCleanupBounded(String operation,
                                                 long deadline,
                                                 Runnable action,
                                                 CompletableFuture<Void> callbackCompletion) {
        // Earlier callbacks share this deadline, but must not prevent later owned resources from seeing cleanup.
        return invokeBounded(operation, deadline, action, true, callbackCompletion, true);
    }

    private OperationResult invokeCleanupAfterForce(String operation,
                                                    long deadline,
                                                    Runnable action,
                                                    CompletableFuture<Void> forceCompletion) {
        if (forceCompletion.isDone()) {
            // The forced callback may have consumed the deadline. Keep the caller bounded, but do not interrupt
            // its normal-close callback: a deadline crossing during thread handoff must not make close enter with
            // a stale interrupt inherited from this lifecycle coordinator.
            return invokeBounded(operation, deadline, action, true, new CompletableFuture<>(), false);
        }
        forceCompletion.whenComplete((ignored, ignoredFailure) -> invokeDeferredCleanup(action));
        return new OperationResult(new MessagingException("Timed out while attempting to " + operation));
    }

    private void invokeDeferredCleanup(Runnable action) {
        try {
            Thread.ofVirtual()
                    .name("helidon-messaging-lifecycle-" + LIFECYCLE_SEQUENCE.incrementAndGet())
                    .inheritInheritableThreadLocals(false)
                    .start(() -> {
                        try {
                            action.run();
                        } catch (Throwable ignored) {
                            // The force timeout already reports that cleanup did not complete within its deadline.
                        }
                    });
        } catch (RuntimeException | Error ignored) {
            // The force timeout already reports that cleanup did not complete within its deadline.
        }
    }

    private OperationResult invokeBounded(String operation,
                                          long deadline,
                                          Runnable action,
                                          boolean attemptAfterDeadline,
                                          CompletableFuture<Void> callbackCompletion,
                                          boolean interruptOnTimeout) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 && !attemptAfterDeadline) {
            callbackCompletion.complete(null);
            return new OperationResult(new MessagingException("Timed out before attempting to " + operation));
        }
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Thread thread;
        try {
            thread = Thread.ofVirtual()
                    .name("helidon-messaging-lifecycle-" + LIFECYCLE_SEQUENCE.incrementAndGet())
                    .inheritInheritableThreadLocals(false)
                    .start(() -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            callbackFailure.set(t);
                        } finally {
                            callbackCompletion.complete(null);
                            completed.countDown();
                        }
                    });
        } catch (RuntimeException | Error e) {
            callbackCompletion.complete(null);
            return new OperationResult(new MessagingException("Cannot start task to " + operation, e));
        }

        remaining = deadline - System.nanoTime();
        boolean finished = false;
        if (remaining > 0) {
            try {
                finished = completed.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                if (interruptOnTimeout) {
                    thread.interrupt();
                }
                Thread.currentThread().interrupt();
                return new OperationResult(new MessagingException("Interrupted while attempting to " + operation, e));
            }
        }
        if (!finished) {
            if (interruptOnTimeout) {
                thread.interrupt();
            }
            return new OperationResult(new MessagingException("Timed out while attempting to " + operation));
        }

        Throwable callbackThrowable = callbackFailure.get();
        if (callbackThrowable == null) {
            return new OperationResult(null);
        }
        if (callbackThrowable instanceof RuntimeException runtimeException) {
            return new OperationResult(runtimeException);
        }
        return new OperationResult(new MessagingException("Failed to " + operation, callbackThrowable));
    }

    private boolean transitionToFailed(Throwable cause) {
        failure = cause;
        state = State.FAILED;
        startupCompletion.completeExceptionally(cause);
        return shutdownOwner.compareAndSet(false, true);
    }

    private void transitionToForcing() {
        lifecycleLock.lock();
        try {
            state = State.FORCING;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void requireStarting() {
        if (state != State.STARTING) {
            throw new MessagingException("Messaging graph startup was cancelled in state " + state);
        }
    }

    private void requirePreparing() {
        if (state != State.NEW) {
            throw new MessagingException("Messaging graph preparation was cancelled in state " + state);
        }
    }

    private void awaitStartup() {
        try {
            startupCompletion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while waiting for messaging graph startup", e);
        } catch (java.util.concurrent.ExecutionException e) {
            awaitFailedStartupCleanup();
            rethrow(e.getCause());
        }
    }

    private void awaitPreparation() {
        try {
            preparationCompletion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while waiting for messaging graph preparation", e);
        } catch (java.util.concurrent.ExecutionException e) {
            rethrow(e.getCause());
        }
    }

    private void finishPreparation(Throwable preparationFailure) {
        lifecycleLock.lock();
        try {
            preparationInProgress = false;
            if (preparationFailure == null) {
                preparationCompletion.complete(null);
            } else {
                preparationCompletion.completeExceptionally(preparationFailure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void awaitFailedStartupCleanup() {
        try {
            shutdownCompletion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while waiting for failed messaging graph cleanup", e);
        } catch (java.util.concurrent.ExecutionException ignored) {
            // Startup callers consistently observe the startup failure after shutdown cleanup has completed.
        }
    }

    private void awaitShutdown() {
        try {
            shutdownCompletion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while waiting for messaging graph shutdown", e);
        } catch (java.util.concurrent.ExecutionException e) {
            rethrow(e.getCause());
        }
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new MessagingException("Messaging graph lifecycle failed", throwable);
    }

    private static long deadline(Duration timeout) {
        long now = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        long result = now + timeoutNanos;
        if (((now ^ result) & (timeoutNanos ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static Duration remaining(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new MessagingException("Messaging graph startup timed out");
        }
        return Duration.ofNanos(remaining);
    }

    private static final class ForcedCleanupOrdering {
        private final Map<ConnectorEndpoint, CompletableFuture<Void>> forceCompletions = new IdentityHashMap<>();

        private CompletableFuture<Void> register(ConnectorEndpoint endpoint) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            if (forceCompletions.put(Objects.requireNonNull(endpoint), completion) != null) {
                throw new IllegalStateException("Connector endpoint force cleanup was registered more than once");
            }
            return completion;
        }

        private CompletableFuture<Void> forceCompletion(ConnectorEndpoint endpoint) {
            CompletableFuture<Void> forceCompletion = forceCompletions.get(Objects.requireNonNull(endpoint));
            if (forceCompletion == null) {
                throw new IllegalStateException("Connector endpoint close was registered before force cleanup");
            }
            return forceCompletion;
        }
    }

    private record CleanupResult(RuntimeException failure, boolean failed) {
    }

    private record OperationResult(RuntimeException failure) {
    }

    private record GraphEmitter<T>(DefaultMessagingGraph graph, Emitter<T> delegate) implements Emitter<T> {
        @Override
        public void emitBatch(MessageBatch<? extends T> messages) {
            graph.requireEmissionPathOpen();
            delegate.emitBatch(messages);
        }
    }

    enum State {
        NEW,
        PREPARED,
        STARTING,
        RUNNING,
        DRAINING,
        FORCING,
        CLOSED,
        FAILED
    }

    private final class SourceBinding {
        private final String name;
        private final ConnectorSource source;
        private final ConnectorEndpoint connectorEndpoint;
        private final IncomingEndpoint incomingEndpoint;
        private final CountDownLatch admissionSignal = new CountDownLatch(1);
        private final AtomicBoolean admissionCancelled = new AtomicBoolean();
        private final AtomicBoolean failureReported = new AtomicBoolean();
        private final AtomicBoolean normalCompletionPending = new AtomicBoolean();
        private DeliveryEngine.SourceTask sourceTask;

        private SourceBinding(String name,
                              ConnectorSource source,
                              ConnectorEndpoint connectorEndpoint,
                              IncomingEndpoint incomingEndpoint) {
            this.name = name;
            this.source = source;
            this.connectorEndpoint = connectorEndpoint;
            this.incomingEndpoint = incomingEndpoint;
        }

        private String name() {
            return name;
        }

        private ConnectorEndpoint connectorEndpoint() {
            return connectorEndpoint;
        }

        private IncomingEndpoint incomingEndpoint() {
            return incomingEndpoint;
        }

        private void start(DeliveryEngine deliveryEngine) {
            sourceTask = deliveryEngine.startSource(name, this::run);
            sourceTask.onCompletion(completionFailure -> {
                if (completionFailure.isPresent()) {
                    sourceFailed(name, completionFailure.get());
                } else if (incomingEndpoint != null) {
                    normalCompletionPending.set(true);
                    reportNormalCompletion();
                }
            });
        }

        private void reportNormalCompletion() {
            boolean report;
            lifecycleLock.lock();
            try {
                report = state == State.RUNNING && normalCompletionPending.compareAndSet(true, false);
            } finally {
                lifecycleLock.unlock();
            }
            if (report) {
                sourceFailed(name, new MessagingException("Managed messaging source stopped unexpectedly"));
            }
        }

        private void prepare() {
            if (incomingEndpoint != null) {
                incomingEndpoint.prepareForGraph();
            }
        }

        private void awaitReady(Duration timeout) {
            if (incomingEndpoint != null) {
                incomingEndpoint.awaitReady(timeout);
            }
            sourceTask.failure().ifPresent(SourceBinding::rethrow);
        }

        private void startAdmission() {
            if (incomingEndpoint != null) {
                incomingEndpoint.startAdmission();
            } else {
                admissionSignal.countDown();
            }
        }

        private void stopAdmission() {
            if (incomingEndpoint != null) {
                incomingEndpoint.stopAdmission();
            }
        }

        private void cancelAdmission() {
            admissionCancelled.set(true);
            admissionSignal.countDown();
        }

        private void run() {
            if (incomingEndpoint == null && !awaitAdmission()) {
                return;
            }
            source.run();
        }

        private boolean awaitAdmission() {
            try {
                admissionSignal.await();
                return !admissionCancelled.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private Optional<Throwable> takeFailure() {
            if (sourceTask == null) {
                return Optional.empty();
            }
            Optional<Throwable> sourceFailure = sourceTask.failure();
            if (sourceFailure.isEmpty() || !failureReported.compareAndSet(false, true)) {
                return Optional.empty();
            }
            return sourceFailure;
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new MessagingException("Messaging source startup failed", failure);
        }
    }
}
