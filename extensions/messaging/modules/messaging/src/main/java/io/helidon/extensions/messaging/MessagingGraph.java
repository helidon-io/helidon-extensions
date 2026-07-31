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
final class MessagingGraph implements AutoCloseable {
    private static final AtomicLong LIFECYCLE_SEQUENCE = new AtomicLong();

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final DeliveryEngine deliveryEngine;
    private final Map<String, MessagingChannel<?>> channels = new LinkedHashMap<>();
    private final Map<String, Set<String>> routes = new LinkedHashMap<>();
    private final Map<String, SourceBinding> sources = new LinkedHashMap<>();
    private final Set<ConnectorSource> sourceIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<ManagedConnectorBinding> managedBindings = new ArrayList<>();
    private final Set<ManagedConnectorBinding> managedBindingIdentities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean shutdownOwner = new AtomicBoolean();
    private final CompletableFuture<Void> preparationCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> startupCompletion = new CompletableFuture<>();
    private final CompletableFuture<Void> shutdownCompletion = new CompletableFuture<>();
    private boolean preparationInProgress;
    private volatile State state = State.NEW;
    private volatile Throwable failure;

    MessagingGraph(DeliveryEngine deliveryEngine) {
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

    void prepare() {
        prepareIfNeeded(true);
    }

    private void prepareIfNeeded(boolean rejectIfNotNeeded) {
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        boolean preparationOwner = false;
        boolean preparationWaiter = false;
        boolean cleanup = false;
        lifecycleLock.lock();
        try {
            if (preparationInProgress) {
                preparationWaiter = true;
            } else if (state == State.NEW) {
                preparationInProgress = true;
                preparationOwner = true;
                try {
                    prepareGraph();
                } catch (RuntimeException e) {
                    runtimeFailure = e;
                    cleanup = transitionToFailed(e);
                } catch (Error e) {
                    errorFailure = e;
                    cleanup = transitionToFailed(e);
                }
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

        Throwable preparationFailure = runtimeFailure == null ? errorFailure : runtimeFailure;
        if (cleanup) {
            rollback(preparationFailure);
        }
        finishPreparation(preparationFailure);
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (errorFailure != null) {
            throw errorFailure;
        }
    }

    void start() {
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
            for (SourceBinding source : sources.values()) {
                requireStarting();
                source.start(deliveryEngine);
            }
            for (SourceBinding source : sources.values()) {
                requireStarting();
                source.awaitReady(remaining(deadline));
            }
            lifecycleLock.lock();
            try {
                requireStarting();
                for (SourceBinding source : sources.values()) {
                    source.startAdmission();
                }
                state = State.RUNNING;
                startupCompletion.complete(null);
            } finally {
                lifecycleLock.unlock();
            }
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
                rollback(e);
            } else if (state == State.FAILED) {
                awaitShutdown();
            }
            throw e;
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
            rollback(cause);
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
            if (!(source.source() instanceof ManagedConnectorSource)) {
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

    private RuntimeException forceBindings(RuntimeException current, long deadline) {
        for (int i = managedBindings.size() - 1; i >= 0; i--) {
            ManagedConnectorBinding binding = managedBindings.get(i);
            if (binding instanceof ManagedConnectorSource) {
                continue;
            }
            OperationResult result = invokeBounded("force close connector binding " + binding.getClass().getName(),
                                                   deadline,
                                                   binding::forceClose);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException closeBindings(RuntimeException current, long deadline) {
        return closeBindings(current, deadline, true);
    }

    private RuntimeException closeNonSourceBindings(RuntimeException current, long deadline) {
        return closeBindings(current, deadline, false);
    }

    private RuntimeException closeBindings(RuntimeException current, long deadline, boolean includeSources) {
        for (int i = managedBindings.size() - 1; i >= 0; i--) {
            ManagedConnectorBinding binding = managedBindings.get(i);
            if (!includeSources && binding instanceof ManagedConnectorSource) {
                continue;
            }
            OperationResult result = invokeBounded("close connector binding " + binding.getClass().getName(),
                                                   deadline,
                                                   binding::close);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException forceSources(RuntimeException current, long deadline) {
        List<SourceBinding> sourceBindings = new ArrayList<>(sources.values());
        for (int i = sourceBindings.size() - 1; i >= 0; i--) {
            ConnectorSource source = sourceBindings.get(i).source();
            if (!(source instanceof ManagedConnectorSource managed)) {
                continue;
            }
            OperationResult result = invokeBounded("force close connector source " + managed.getClass().getName(),
                                                   deadline,
                                                   managed::forceClose);
            current = append(current, result.failure());
        }
        return current;
    }

    private RuntimeException closeSources(RuntimeException current, long deadline) {
        List<SourceBinding> sourceBindings = new ArrayList<>(sources.values());
        for (int i = sourceBindings.size() - 1; i >= 0; i--) {
            ConnectorSource source = sourceBindings.get(i).source();
            if (!(source instanceof ManagedConnectorSource managed)) {
                continue;
            }
            OperationResult result = invokeBounded("close connector source " + managed.getClass().getName(),
                                                   deadline,
                                                   managed::close);
            current = append(current, result.failure());
        }
        return current;
    }

    private void rollback(Throwable primary) {
        for (SourceBinding source : sources.values()) {
            source.cancelAdmission();
        }
        long cleanupDeadline = deadline(deliveryEngine.shutdownTimeout());
        RuntimeException cleanupFailure = forceSources(null, cleanupDeadline);
        deliveryEngine.forceShutdown();
        cleanupFailure = forceBindings(cleanupFailure, cleanupDeadline);
        cleanupFailure = closeBindings(cleanupFailure, cleanupDeadline);
        boolean terminated = awaitTermination(cleanupDeadline);
        if (!terminated) {
            cleanupFailure = append(cleanupFailure,
                                    new MessagingException("Messaging startup rollback timed out after "
                                                                   + deliveryEngine.shutdownTimeout()));
        } else {
            cleanupFailure = closeSources(cleanupFailure, deadline(deliveryEngine.shutdownTimeout()));
        }
        if (cleanupFailure != null && cleanupFailure != primary) {
            primary.addSuppressed(cleanupFailure);
        }
        shutdownCompletion.complete(null);
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
        Set<ManagedConnectorBinding> newIdentities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ConnectorSource source : contributionSources) {
            if (source instanceof ManagedConnectorBinding managed
                    && (managedBindingIdentities.contains(managed) || !newIdentities.add(managed))) {
                throw new IllegalArgumentException("Managed connector binding is already owned by this messaging graph");
            }
        }
        for (Object binding : bindings) {
            Objects.requireNonNull(binding);
            if (binding instanceof ManagedConnectorBinding managed
                    && (managedBindingIdentities.contains(managed) || !newIdentities.add(managed))) {
                throw new IllegalArgumentException("Managed connector binding is already owned by this messaging graph");
            }
        }
    }

    private void addSourceLocked(String name, ConnectorSource source) {
        sources.put(name, new SourceBinding(name, source));
        sourceIdentities.add(source);
        addBindingLocked(source);
    }

    private void addBindingLocked(Object binding) {
        if (binding instanceof ManagedConnectorBinding managed) {
            managedBindingIdentities.add(managed);
            managedBindings.add(managed);
        }
    }

    private void prepareGraph() {
        validateTopology();
        for (SourceBinding source : sources.values()) {
            source.prepare();
        }
        state = State.PREPARED;
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
        lifecycleLock.lock();
        try {
            if (state != State.STARTING && state != State.RUNNING) {
                return;
            }
            sourceFailure = new MessagingException("Messaging source " + name + " failed", cause);
            cleanup = transitionToFailed(sourceFailure);
        } finally {
            lifecycleLock.unlock();
        }
        if (cleanup) {
            rollback(sourceFailure);
        }
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
        closeFailure = closeSources(closeFailure, cleanupDeadline);
        if (closeFailure != null) {
            transitionToForcing();
            return closeForced(closeFailure, "Messaging graph endpoint forced shutdown");
        }
        deliveryEngine.forceShutdown();
        closeFailure = closeNonSourceBindings(closeFailure, cleanupDeadline);
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
        RuntimeException closeFailure = forceSources(current, cleanupDeadline);
        deliveryEngine.forceShutdown();
        closeFailure = forceBindings(closeFailure, cleanupDeadline);
        closeFailure = closeBindings(closeFailure, cleanupDeadline);
        boolean terminated = awaitTermination(cleanupDeadline);
        if (!terminated) {
            closeFailure = append(closeFailure,
                                  new MessagingException(operation + " timed out after "
                                                                 + deliveryEngine.shutdownTimeout()));
        } else {
            closeFailure = closeSources(closeFailure, deadline(deliveryEngine.shutdownTimeout()));
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
            if (cause instanceof MessagingRejectedException rejected
                    && rejected.reason() == MessagingRejectedException.Reason.SHUTDOWN) {
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
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
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
                            completed.countDown();
                        }
                    });
        } catch (RuntimeException | Error e) {
            return new OperationResult(new MessagingException("Cannot start task to " + operation, e));
        }

        remaining = deadline - System.nanoTime();
        boolean finished = false;
        if (remaining > 0) {
            try {
                finished = completed.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                thread.interrupt();
                Thread.currentThread().interrupt();
                return new OperationResult(new MessagingException("Interrupted while attempting to " + operation, e));
            }
        }
        if (!finished) {
            thread.interrupt();
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

    private record CleanupResult(RuntimeException failure, boolean failed) {
    }

    private record OperationResult(RuntimeException failure) {
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
        private final CountDownLatch admissionSignal = new CountDownLatch(1);
        private final AtomicBoolean admissionCancelled = new AtomicBoolean();
        private final AtomicBoolean failureReported = new AtomicBoolean();
        private DeliveryEngine.SourceTask sourceTask;

        private SourceBinding(String name, ConnectorSource source) {
            this.name = name;
            this.source = source;
        }

        private ConnectorSource source() {
            return source;
        }

        private String name() {
            return name;
        }

        private void start(DeliveryEngine deliveryEngine) {
            sourceTask = deliveryEngine.startSource(name, this::run);
            sourceTask.onCompletion(completionFailure -> {
                if (completionFailure.isPresent()) {
                    sourceFailed(name, completionFailure.get());
                } else if (source instanceof ManagedConnectorSource) {
                    sourceFailed(name, new MessagingException("Managed messaging source stopped unexpectedly"));
                }
            });
        }

        private void prepare() {
            if (source instanceof ManagedConnectorSource managed) {
                managed.prepareForGraph();
            }
        }

        private void awaitReady(Duration timeout) {
            if (source instanceof ManagedConnectorSource managed) {
                managed.awaitReady(timeout);
            }
            sourceTask.failure().ifPresent(SourceBinding::rethrow);
        }

        private void startAdmission() {
            if (source instanceof ManagedConnectorSource managed) {
                managed.startAdmission();
            } else {
                admissionSignal.countDown();
            }
        }

        private void stopAdmission() {
            if (source instanceof ManagedConnectorSource managed) {
                managed.stopAdmission();
            }
        }

        private void cancelAdmission() {
            admissionCancelled.set(true);
            admissionSignal.countDown();
        }

        private void run() {
            if (!(source instanceof ManagedConnectorSource) && !awaitAdmission()) {
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
