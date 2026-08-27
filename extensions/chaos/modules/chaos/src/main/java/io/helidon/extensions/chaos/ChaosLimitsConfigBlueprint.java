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

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;

/**
 * Server-enforced ceilings for Chaos runs.
 */
@Prototype.Blueprint(decorator = ChaosLimitsConfigSupport.BuilderDecorator.class)
@Prototype.Configured
interface ChaosLimitsConfigBlueprint {

    /**
     * Maximum concurrently active runs.
     *
     * @return maximum active runs
     */
    @Option.Configured
    @Option.DefaultInt(1)
    int maximumActiveRuns();

    /**
     * Maximum guard duration of one run.
     *
     * @return maximum run duration
     */
    @Option.Configured
    @Option.Default("PT15M")
    Duration maximumRunDuration();

    /**
     * Maximum cumulative activations of one disruption.
     *
     * @return maximum activations
     */
    @Option.Configured
    @Option.DefaultLong(10_000)
    long maximumActivationsPerDisruption();

    /**
     * Maximum concurrent activations of one disruption.
     *
     * @return maximum concurrent activations
     */
    @Option.Configured
    @Option.DefaultInt(64)
    int maximumConcurrentActivationsPerDisruption();

    /**
     * Maximum synthetic response body size in UTF-8 bytes.
     *
     * @return maximum body size
     */
    @Option.Configured
    @Option.DefaultInt(65_536)
    int maximumSyntheticBodyBytes();

    /**
     * Maximum control request size in bytes.
     *
     * @return maximum control request size
     */
    @Option.Configured
    @Option.DefaultLong(65_536)
    long maximumControlRequestBytes();

    /**
     * Maximum concurrent control requests.
     *
     * @return maximum concurrent control requests
     */
    @Option.Configured
    @Option.DefaultInt(16)
    int maximumConcurrentControlRequests();

    /**
     * Maximum retained run representations.
     *
     * @return maximum retained runs
     */
    @Option.Configured
    @Option.DefaultInt(32)
    int maximumRetainedRuns();

    /**
     * Duration for retaining a terminal run.
     *
     * @return terminal run retention
     */
    @Option.Configured
    @Option.Default("PT15M")
    Duration terminalRunRetention();
}
