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

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChaosConfigTest {

    @Test
    void defaultsAreDisabledAndBounded() {
        ChaosConfig config = ChaosConfig.builder().buildPrototype();

        assertThat(config.enabled(), is(false));
        assertThat(config.security().requiredRole(), is("chaos-operator"));
        assertThat(config.security().allowUnauthenticatedLoopback(), is(false));
        assertThat(config.limits().maximumActiveRuns(), is(1));
        assertThat(config.limits().maximumRunDuration(), is(Duration.ofMinutes(15)));
        assertThat(config.limits().maximumActivationsPerDisruption(), is(10_000L));
        assertThat(config.limits().maximumConcurrentActivationsPerDisruption(), is(64));
        assertThat(config.limits().maximumSyntheticBodyBytes(), is(65_536));
        assertThat(config.limits().maximumControlRequestBytes(), is(65_536L));
        assertThat(config.limits().maximumConcurrentControlRequests(), is(16));
        assertThat(config.limits().maximumRetainedRuns(), is(32));
        assertThat(config.limits().terminalRunRetention(), is(Duration.ofMinutes(15)));
    }

    @Test
    void disabledConfigurationDoesNotRequireSockets() {
        assertThat(ChaosConfig.builder().enabled(false).buildPrototype().enabled(), is(false));
    }

    @Test
    void enabledConfigurationRequiresControlSocket() {
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosConfig.builder()
                             .enabled(true)
                             .addApplicationSocket("@default")
                             .buildPrototype());
    }

    @Test
    void enabledConfigurationRequiresApplicationSocket() {
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosConfig.builder()
                             .enabled(true)
                             .controlSocket("chaos-control")
                             .buildPrototype());
    }

    @Test
    void rejectsOverlappingControlAndApplicationSockets() {
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosConfig.builder()
                             .enabled(true)
                             .controlSocket("chaos-control")
                             .addApplicationSocket("chaos-control")
                             .buildPrototype());
    }

    @Test
    void acceptsDistinctSockets() {
        ChaosConfig config = ChaosConfig.builder()
                .enabled(true)
                .controlSocket("chaos-control")
                .addApplicationSocket("@default")
                .buildPrototype();

        assertThat(config.controlSocket().orElseThrow(), is("chaos-control"));
        assertThat(config.applicationSockets(), contains("@default"));
    }

    @Test
    void configBuildsItsRuntimeType() {
        ChaosServerFeature feature = ChaosConfig.builder().build();

        assertThat(feature.prototype().enabled(), is(false));
    }

    @Test
    void rejectsBlankSecurityRole() {
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosSecurityConfig.builder().requiredRole(" ").build());
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumActiveRuns(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumRunDuration(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumActivationsPerDisruption(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumConcurrentActivationsPerDisruption(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumSyntheticBodyBytes(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumControlRequestBytes(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumConcurrentControlRequests(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().maximumRetainedRuns(0).build());
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder().terminalRunRetention(Duration.ZERO).build());
    }

    @Test
    void retainedRunLimitCannotBeLowerThanActiveRunLimit() {
        assertThrows(IllegalArgumentException.class,
                     () -> ChaosLimitsConfig.builder()
                             .maximumActiveRuns(2)
                             .maximumRetainedRuns(1)
                             .build());
    }
}
