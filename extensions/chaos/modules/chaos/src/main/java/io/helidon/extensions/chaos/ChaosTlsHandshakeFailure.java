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

import java.io.UncheckedIOException;

import javax.net.ssl.SSLHandshakeException;

/**
 * Simulates an outbound TLS handshake failure before network access.
 */
final class ChaosTlsHandshakeFailure implements ChaosEffect, ChaosEffectAction {
    private static final ChaosTlsHandshakeFailure INSTANCE = new ChaosTlsHandshakeFailure();
    private static final String MESSAGE = "TLS handshake failed due to chaos disruption";
    private static final String TYPE = "tls-handshake-failure";

    private ChaosTlsHandshakeFailure() {
    }

    static ChaosTlsHandshakeFailure instance() {
        return INSTANCE;
    }

    String type() {
        return TYPE;
    }

    UncheckedIOException exception() {
        return new UncheckedIOException("Failed to execute SSL handshake", new SSLHandshakeException(MESSAGE));
    }
}
