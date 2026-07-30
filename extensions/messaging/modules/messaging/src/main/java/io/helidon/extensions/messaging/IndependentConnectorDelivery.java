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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Compatibility task for connector contexts not supplied by the messaging runtime.
 */
final class IndependentConnectorDelivery implements ConnectorDelivery {
    private final String channel;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final Thread thread;

    private IndependentConnectorDelivery(String channel, Runnable delivery) {
        this.channel = channel;
        this.thread = Thread.ofVirtual()
                .name("helidon-messaging-independent-delivery-" + channel)
                .inheritInheritableThreadLocals(false)
                .unstarted(() -> {
                    try {
                        delivery.run();
                        completion.complete(null);
                    } catch (Throwable t) {
                        completion.completeExceptionally(t);
                    }
                });
    }

    static ConnectorDelivery start(String channel, Runnable delivery) {
        IndependentConnectorDelivery task = new IndependentConnectorDelivery(channel, delivery);
        task.thread.start();
        return task;
    }

    @Override
    public boolean isDone() {
        return completion.isDone();
    }

    @Override
    public boolean isCurrentThread() {
        return thread == Thread.currentThread();
    }

    @Override
    public void await() throws InterruptedException {
        try {
            completion.get();
        } catch (ExecutionException e) {
            rethrow(e);
        }
    }

    @Override
    public boolean await(Duration timeout) throws InterruptedException {
        try {
            completion.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException e) {
            rethrow(e);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    @Override
    public void cancel() {
        thread.interrupt();
    }

    @Override
    public void close() {
        if (!isDone()) {
            cancel();
        }
    }

    private void rethrow(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new MessagingException("Connector delivery failed on channel " + channel, cause);
    }
}
