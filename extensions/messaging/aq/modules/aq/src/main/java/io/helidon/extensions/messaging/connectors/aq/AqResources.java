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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.Topic;
import javax.naming.NamingException;

import io.helidon.messaging.MessagingException;

import oracle.jms.AQjmsSession;

/**
 * Owns one channel's provider resources, including resources returned after startup cancellation.
 */
final class AqResources implements AutoCloseable {
    private final AqRuntimeConfig config;
    private final AqResourceResolver resolver;
    private final ReentrantLock lock = new ReentrantLock();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    private Connection connection;
    private Thread initializer;
    private boolean started;
    private boolean closed;
    private boolean initializationFinished;
    private int closingConnections;
    private Throwable closeFailure;
    private volatile Session session;
    private volatile MessageConsumer consumer;
    private volatile MessageProducer producer;

    AqResources(AqRuntimeConfig config, AqResourceResolver resolver) {
        this.config = Objects.requireNonNull(config);
        this.resolver = Objects.requireNonNull(resolver);
    }

    void open(boolean incoming) {
        lock.lock();
        try {
            if (closed || started) {
                throw new IllegalStateException("AQ channel cannot be started more than once or after closing");
            }
            started = true;
            initializer = Thread.ofVirtual()
                    .name("aq-start-" + config.channelName())
                    .unstarted(() -> initialize(incoming));
            initializer.start();
        } finally {
            lock.unlock();
        }
        awaitReady();
    }

    Session session() {
        return Objects.requireNonNull(session, "AQ session is not ready");
    }

    MessageConsumer consumer() {
        return Objects.requireNonNull(consumer, "AQ consumer is not ready");
    }

    MessageProducer producer() {
        return Objects.requireNonNull(producer, "AQ producer is not ready");
    }

    void beginReceiving() throws JMSException {
        Connection current;
        lock.lock();
        try {
            if (closed) {
                throw new MessagingException("AQ channel has been closed");
            }
            current = connection;
        } finally {
            lock.unlock();
        }
        Objects.requireNonNull(current, "AQ connection is not ready").start();
    }

    void forceClose() {
        Connection closing;
        Thread starting;
        lock.lock();
        try {
            closed = true;
            closing = connection;
            connection = null;
            if (closing != null) {
                closingConnections++;
            }
            starting = initializer;
            if (!started) {
                initializationFinished = true;
            }
            ready.completeExceptionally(new MessagingException("AQ channel closed during startup"));
            completeTermination();
        } finally {
            lock.unlock();
        }
        if (starting != null && starting != Thread.currentThread()) {
            starting.interrupt();
        }
        if (closing != null) {
            closeConnection(closing);
        }
    }

    void awaitCleanup() {
        try {
            terminated.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while retiring AQ channel " + config.channelName(), e);
        } catch (ExecutionException _) {
            // A broken connection can report a close failure after all cleanup has completed.
        }
    }

    @Override
    public void close() {
        forceClose();
        // Cleanup is bounded even when the caller was interrupted to cancel provider work.
        boolean interrupted = Thread.interrupted();
        try {
            terminated.get(config.closeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
            throw new MessagingException("Interrupted while closing AQ channel " + config.channelName(), e);
        } catch (ExecutionException e) {
            throw new MessagingException("Cannot close AQ channel " + config.channelName(), e.getCause());
        } catch (TimeoutException e) {
            throw new MessagingException("Timed out closing AQ channel " + config.channelName(), e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void initialize(boolean incoming) {
        try {
            ConnectionFactory factory = resolver.resolve(config);
            if (isClosed()) {
                return;
            }
            Connection created = config.username().isPresent()
                    ? factory.createConnection(config.username().orElseThrow(), config.password().orElseThrow())
                    : factory.createConnection();
            if (!publish(created)) {
                return;
            }
            if (config.clientId().isPresent()) {
                created.setClientID(config.clientId().orElseThrow());
            }
            Session createdSession = created.createSession(config.transacted(),
                                                          config.transacted()
                                                                  ? Session.SESSION_TRANSACTED
                                                                  : Session.CLIENT_ACKNOWLEDGE);
            session = createdSession;
            if (isClosed()) {
                closeLate(createdSession);
                return;
            }
            Destination destination = destination(createdSession);
            if (isClosed()) {
                closeLate(createdSession);
                return;
            }
            if (incoming) {
                MessageConsumer createdConsumer = createConsumer(createdSession, destination);
                if (isClosed()) {
                    closeLate(createdConsumer, createdSession);
                    return;
                }
                consumer = createdConsumer;
            } else {
                MessageProducer createdProducer = createdSession instanceof AQjmsSession aq
                        && config.destinationType() == AqDestinationType.TOPIC
                        ? aq.createPublisher((Topic) destination)
                        : createdSession.createProducer(destination);
                if (isClosed()) {
                    closeLate(createdProducer, createdSession);
                    return;
                }
                if (config.enqueueTransformation().isPresent()) {
                    AqEnqueueTransformation.configure(createdProducer, config.enqueueTransformation().orElseThrow());
                    AqEnqueueTransformation.requireClassicDestination(createdSession, destination);
                    if (isClosed()) {
                        closeLate(createdProducer, createdSession);
                        return;
                    }
                }
                producer = createdProducer;
            }
            ready.complete(null);
        } catch (Throwable failure) {
            ready.completeExceptionally(failure);
            forceClose();
        } finally {
            lock.lock();
            try {
                initializationFinished = true;
                completeTermination();
            } finally {
                lock.unlock();
            }
        }
    }

    private boolean publish(Connection created) {
        boolean accepted;
        lock.lock();
        try {
            accepted = !closed;
            if (accepted) {
                connection = created;
            } else {
                closingConnections++;
            }
        } finally {
            lock.unlock();
        }
        if (!accepted) {
            closeConnection(created);
        }
        return accepted;
    }

    private boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    private Destination destination(Session current) throws JMSException, NamingException {
        if (config.jndi().isPresent()) {
            return AqJndiDestination.resolve(config.jndi().orElseThrow(), config.destinationType());
        }
        if (config.schema().isPresent()) {
            AQjmsSession aq = aqSession(current);
            return switch (config.destinationType()) {
            case QUEUE -> aq.getQueue(config.schema().orElseThrow(), config.destination());
            case TOPIC -> aq.getTopic(config.schema().orElseThrow(), config.destination());
            };
        }
        return switch (config.destinationType()) {
        case QUEUE -> current.createQueue(config.destination());
        case TOPIC -> current.createTopic(config.destination());
        };
    }

    private MessageConsumer createConsumer(Session current, Destination destination) throws JMSException {
        String selector = config.messageSelector().orElse(null);
        if (config.recipientName().isPresent()) {
            return aqSession(current).createTopicReceiver((Topic) destination,
                                                         config.recipientName().orElseThrow(),
                                                         selector,
                                                         AqMessageMapper.payloadFactory(config));
        }
        if (config.durable() && config.payloadType() != AqPayloadType.JMS) {
            return aqSession(current).createDurableSubscriber((Topic) destination,
                                                             config.subscriptionName().orElseThrow(),
                                                             selector,
                                                             config.noLocal(),
                                                             AqMessageMapper.payloadFactory(config));
        }
        if (config.payloadType() != AqPayloadType.JMS) {
            return aqSession(current).createConsumer(destination,
                                                     selector,
                                                     AqMessageMapper.payloadFactory(config),
                                                     null,
                                                     config.noLocal());
        }
        if (config.durable()) {
            return current.createDurableSubscriber((Topic) destination,
                                                   config.subscriptionName().orElseThrow(),
                                                   selector,
                                                   config.noLocal());
        }
        return current.createConsumer(destination, selector, config.noLocal());
    }

    private AQjmsSession aqSession(Session current) {
        if (current instanceof AQjmsSession aq) {
            return aq;
        }
        throw new MessagingException("Oracle AQ session required for channel " + config.channelName());
    }

    private void awaitReady() {
        try {
            ready.get(config.reconnect().connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            forceClose();
            Thread.currentThread().interrupt();
            throw new MessagingException("Interrupted while starting AQ channel " + config.channelName(), e);
        } catch (TimeoutException e) {
            forceClose();
            throw new AqConnectionTimeoutException("Timed out connecting AQ channel " + config.channelName(), e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (e.getCause() instanceof Error failure) {
                throw failure;
            }
            throw new MessagingException("Cannot start AQ channel " + config.channelName(), e.getCause());
        }
    }

    private void closeConnection(Connection closing) {
        Thread.ofVirtual().name("aq-close-" + config.channelName()).start(() -> {
            Throwable failure = null;
            try {
                // Closing the connection also closes its sessions, consumers and producers.
                closing.close();
            } catch (Throwable e) {
                failure = e;
            } finally {
                lock.lock();
                try {
                    closingConnections--;
                    if (failure != null) {
                        recordCloseFailure(failure);
                    }
                    completeTermination();
                } finally {
                    lock.unlock();
                }
            }
        });
    }

    private void closeLate(AutoCloseable... lateResources) {
        for (AutoCloseable resource : lateResources) {
            try {
                resource.close();
            } catch (Throwable failure) {
                lock.lock();
                try {
                    recordCloseFailure(failure);
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private void recordCloseFailure(Throwable failure) {
        if (closeFailure == null) {
            closeFailure = failure;
        } else if (closeFailure != failure) {
            closeFailure.addSuppressed(failure);
        }
    }

    private void completeTermination() {
        if (closed && initializationFinished && connection == null && closingConnections == 0) {
            if (closeFailure == null) {
                terminated.complete(null);
            } else {
                terminated.completeExceptionally(closeFailure);
            }
        }
    }
}
