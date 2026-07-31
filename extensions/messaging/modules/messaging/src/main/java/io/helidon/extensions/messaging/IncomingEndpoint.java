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

/**
 * Incoming endpoint for one configured binding.
 * <p>
 * The messaging runtime invokes {@link #run()} on an owned virtual thread. The endpoint establishes its transport
 * resources, reports readiness, and waits for admission before acquiring deliveries.
 */
public interface IncomingEndpoint extends ConnectorSource, ConnectorEndpoint {
    /**
     * Put the endpoint under graph-controlled admission before its task is started.
     * <p>
     * The runtime invokes this callback on a bounded lifecycle task without holding the graph lifecycle lock. The
     * implementation must not acquire transport resources, must remain interruptible, and must tolerate a concurrent
     * {@link #forceClose()} if graph preparation is cancelled.
     */
    void prepareForGraph();

    /**
     * Wait until the endpoint has synchronously established the resources required to run.
     *
     * @param timeout maximum wait
     * @throws RuntimeException if startup failed or timed out
     */
    void awaitReady(Duration timeout);

    /**
     * Allow transport acquisition after every graph endpoint has reported readiness.
     * <p>
     * The runtime invokes this callback on a bounded lifecycle task without holding the graph lifecycle lock. The
     * implementation must return promptly after releasing its admission barrier, must remain interruptible, and must
     * tolerate a concurrent {@link #forceClose()} if graph startup is cancelled.
     */
    void startAdmission();

    /**
     * Stop acquiring new transport deliveries while allowing already acquired deliveries to settle. Once those
     * delivery handoffs finish, {@link #run()} must return so the runtime can complete its graceful drain before
     * checkpointing and closing the endpoint.
     */
    void stopAdmission();

    /**
     * Persist or verify the transport checkpoint after all admitted deliveries have settled.
     */
    void checkpoint();
}
