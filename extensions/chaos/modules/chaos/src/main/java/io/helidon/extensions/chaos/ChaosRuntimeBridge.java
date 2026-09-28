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
package io.helidon.extensions.chaos;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

final class ChaosRuntimeBridge {
    private static final AtomicReference<ChaosRuntimeRegistration> REGISTRATION = new AtomicReference<>();

    private ChaosRuntimeBridge() {
    }

    static ChaosRuntimeRegistration register(ChaosRunEngine engine) {
        Objects.requireNonNull(engine, "engine is null");
        ChaosRuntimeRegistration registration = new ChaosRuntimeRegistration(engine);
        if (!REGISTRATION.compareAndSet(null, registration)) {
            throw new IllegalStateException("A chaos runtime is already registered");
        }
        return registration;
    }

    static Optional<ChaosRunEngine.Reservation> reserveOutbound(String method,
                                                                 String scheme,
                                                                 String host,
                                                                 int port,
                                                                 String requestPath) {
        ChaosRuntimeRegistration registration = REGISTRATION.get();
        if (registration == null || !registration.isActive()) {
            return Optional.empty();
        }
        return registration.reserveOutbound(method, scheme, host, port, requestPath);
    }

    static void unregister(ChaosRuntimeRegistration registration) {
        REGISTRATION.compareAndSet(registration, null);
    }
}
