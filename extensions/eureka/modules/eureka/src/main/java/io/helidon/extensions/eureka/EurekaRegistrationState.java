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
package io.helidon.extensions.eureka;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import io.helidon.json.JsonObject;

// Snapshot publication and stopping must be a single atomic transition. HTTP operations are drained separately.
final class EurekaRegistrationState {
    private final AtomicReference<Snapshot> state = new AtomicReference<>(new Snapshot(null, false));

    JsonObject instanceInfo() {
        return state.get().instanceInfo();
    }

    boolean stopped() {
        return state.get().stopped();
    }

    boolean update(JsonObject expected, JsonObject updated) {
        var previous = state.get();
        return !previous.stopped()
                && previous.instanceInfo() == expected
                && (expected == updated || state.compareAndSet(previous, new Snapshot(updated, false)));
    }

    boolean stop(UnaryOperator<JsonObject> statusChange) {
        var previous = state.getAndUpdate(current -> current.stopped()
                ? current
                : new Snapshot(current.instanceInfo() == null ? null : statusChange.apply(current.instanceInfo()), true));
        return !previous.stopped();
    }

    private record Snapshot(JsonObject instanceInfo, boolean stopped) {
    }
}
