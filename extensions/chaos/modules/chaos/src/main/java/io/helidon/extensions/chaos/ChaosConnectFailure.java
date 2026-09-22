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
import java.net.ConnectException;

/**
 * Simulates an outbound connection failure before network access.
 */
final class ChaosConnectFailure implements ChaosEffect, ChaosEffectAction {
    private static final ChaosConnectFailure INSTANCE = new ChaosConnectFailure();
    private static final String MESSAGE = "Connection refused by chaos disruption";
    private static final String TYPE = "connect-failure";

    private ChaosConnectFailure() {
    }

    static ChaosConnectFailure instance() {
        return INSTANCE;
    }

    String type() {
        return TYPE;
    }

    UncheckedIOException exception() {
        return new UncheckedIOException(new ConnectException(MESSAGE));
    }
}
