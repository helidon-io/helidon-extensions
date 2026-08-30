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
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Weighted;
import io.helidon.webserver.spi.ServerFeature;

/**
 * Runtime type produced from {@link ChaosConfig}.
 */
public final class ChaosServerFeature implements RuntimeType.Api<ChaosConfig>, ServerFeature, Weighted {

    static final String CHAOS_ID = "chaos";

    private final ChaosConfig prototype;

    private ChaosServerFeature(ChaosConfig prototype) {
        this.prototype = Objects.requireNonNull(prototype);
    }

    /**
     * Creates the runtime type.
     *
     * @param prototype startup configuration
     * @return runtime type
     */
    public static ChaosServerFeature create(ChaosConfig prototype) {
        return new ChaosServerFeature(Objects.requireNonNull(prototype));
    }

    /**
     * Returns the configuration builder for this runtime type.
     *
     * @return configuration builder
     */
    public static ChaosConfig.Builder builder() {
        return ChaosConfig.builder();
    }

    /**
     * Creates the runtime type after updating its configuration builder.
     *
     * @param builderConsumer builder updates
     * @return runtime type
     */
    public static ChaosServerFeature create(Consumer<ChaosConfig.Builder> builderConsumer) {
        return builder().update(Objects.requireNonNull(builderConsumer)).build();
    }

    @Override
    public String name() {
        return prototype.name();
    }

    @Override
    public ChaosConfig prototype() {
        return prototype;
    }

    @Override
    public void setup(ServerFeatureContext context) {
        Objects.requireNonNull(context);
        if (!prototype.enabled()) {
            return;
        }
        ChaosSocketPolicy.Result policy = ChaosSocketPolicy.validate(prototype, context);
        ChaosRunEngine engine = ChaosRunEngine.create(prototype.limits());
        try {
            context.socket(policy.controlSocket())
                    .httpRouting()
                    .register("/chaos/v1", new ChaosControlService(engine, prototype, policy.anonymousLoopback()));
            policy.applicationSockets().forEach(socket -> context.socket(socket)
                    .httpRouting()
                    .addFilter(new ChaosApplicationFilter(engine)));
        } catch (RuntimeException exception) {
            engine.close();
            throw exception;
        }
    }

    @Override
    public String type() {
        return CHAOS_ID;
    }

    @Override
    public double weight() {
        return prototype.weight();
    }
}
