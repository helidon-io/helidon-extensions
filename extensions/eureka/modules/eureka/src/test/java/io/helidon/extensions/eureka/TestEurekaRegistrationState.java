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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import io.helidon.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

class TestEurekaRegistrationState {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void testStaleRenewalDoesNotOverwriteStartupStatus() throws Exception {
        var starting = instanceInfo("STARTING");
        var up = instanceInfo("UP");
        var state = new EurekaRegistrationState();
        assertThat(state.update(null, starting), is(true));

        completeRenewalAfter(state, snapshot -> snapshot, () -> assertThat(state.update(starting, up), is(true)));

        assertThat("Renewal must preserve the newer startup snapshot", state.instanceInfo(), sameInstance(up));
        assertThat(state.stopped(), is(false));
    }

    @Test
    void testStoppedRegistrationRejectsRenewalResult() throws Exception {
        var up = instanceInfo("UP");
        var renewed = instanceInfo("OUT_OF_SERVICE");
        var down = instanceInfo("DOWN");
        var state = new EurekaRegistrationState();
        assertThat(state.update(null, up), is(true));

        completeRenewalAfter(state, _ -> renewed, () -> assertThat(state.stop(_ -> down), is(true)));

        assertThat("Renewal must preserve the shutdown snapshot", state.instanceInfo(), sameInstance(down));
        assertThat(state.stopped(), is(true));
        assertThat("Stopping is idempotent", state.stop(_ -> renewed), is(false));
        assertThat(state.instanceInfo(), sameInstance(down));
        assertThat("Stopped registrations cannot be updated", state.update(down, renewed), is(false));
    }

    @Test
    void testCurrentRenewalPublishesResponse() {
        var up = instanceInfo("UP");
        var renewed = instanceInfo("OUT_OF_SERVICE");
        var state = new EurekaRegistrationState();
        assertThat(state.update(null, up), is(true));

        assertThat(state.update(up, renewed), is(true));
        assertThat(state.instanceInfo(), sameInstance(renewed));
    }

    private static JsonObject instanceInfo(String status) {
        return JsonObject.builder().set("instance", JsonObject.builder().set("status", status).build()).build();
    }

    private static void completeRenewalAfter(EurekaRegistrationState state,
                                            UnaryOperator<JsonObject> renewal,
                                            Runnable statusChange) throws Exception {
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var result = new CompletableFuture<Boolean>();
        var renewer = Thread.ofVirtual().start(() -> {
            try {
                var snapshot = state.instanceInfo();
                captured.countDown();
                assertThat("Renewal was not released", release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
                result.complete(state.update(snapshot, renewal.apply(snapshot)));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            assertThat("Renewal did not capture a snapshot", captured.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(true));
            statusChange.run();
            release.countDown();
            assertThat("Obsolete renewal result was accepted", result.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), is(false));
        } finally {
            release.countDown();
            assertThat("Renewal thread did not finish", renewer.join(TIMEOUT), is(true));
        }
    }
}
