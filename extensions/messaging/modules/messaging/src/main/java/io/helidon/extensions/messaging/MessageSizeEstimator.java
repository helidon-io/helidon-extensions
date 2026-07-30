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

import java.util.OptionalLong;

import io.helidon.service.registry.Service;

/**
 * Supplies a conservative logical byte weight for message admission control.
 * <p>
 * The weight is a stable content budget, not a JVM object-graph measurement. An estimator must return an empty result
 * when it cannot provide a conservative value without guessing. A present result must cover the payload, headers,
 * metadata, and any other logical content retained while the message occupies pending or in-flight admission,
 * including an unsettled connector delivery. When multiple estimators apply, the runtime uses the largest result.
 * Estimators run synchronously before admission and therefore must be deterministic, side-effect-free, non-blocking,
 * and bounded in cost.
 */
@Service.Contract
@FunctionalInterface
public interface MessageSizeEstimator {
    /**
     * Estimate the full admission weight of a message.
     *
     * @param message message to estimate
     * @return full message admission weight, or empty when this estimator cannot determine it
     */
    OptionalLong estimate(Message<?> message);
}
