/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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

import java.lang.System.Logger;
import java.util.Objects;
import java.util.function.Consumer;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Weighted;
import io.helidon.webserver.spi.ServerFeature;

import static io.helidon.webserver.WebServer.DEFAULT_SOCKET_NAME;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.getLogger;

/**
 * A {@link ServerFeature} that automatically and unobtrusively attempts to register the currently running microservice
 * as a Eureka Server <dfn>service instance</dfn>.
 *
 * <p>Most users will never need to programmatically interact with any of the classes in this package.</p>
 *
 * @see EurekaRegistrationServerFeatureProvider#create(io.helidon.config.Config, String)
 */
public final class EurekaRegistrationServerFeature implements RuntimeType.Api<EurekaRegistrationConfig>, ServerFeature, Weighted {

    static final String EUREKA_ID = "eureka";

    private static final Logger LOGGER = getLogger(EurekaRegistrationServerFeature.class.getName());

    private final EurekaRegistrationConfig prototype;

    private EurekaRegistrationServerFeature(EurekaRegistrationConfig prototype) {
        super();
        this.prototype = Objects.requireNonNull(prototype, "prototype");
    }

    /**
     * Creates a {@link EurekaRegistrationServerFeature}.
     *
     * <p>Most users will never need to programmatically interact with any of the classes in this package.</p>
     *
     * @param prototype a prototype
     * @return a {@link EurekaRegistrationServerFeature}
     */
    public static EurekaRegistrationServerFeature create(EurekaRegistrationConfig prototype) {
        return new EurekaRegistrationServerFeature(prototype);
    }

    /**
     * Returns a builder of the prototype that also knows how to build instances of this runtime type.
     *
     * <p>Most users will never need to programmatically interact with any of the classes in this package.</p>
     *
     * @return a builder of the prototype that also knows how to build instances of this runtime type
     */
    public static EurekaRegistrationConfig.Builder builder() {
        return EurekaRegistrationConfig.builder();
    }

    /**
     * Creates a {@link EurekaRegistrationServerFeature}.
     *
     * <p>Most users will never need to programmatically interact with any of the classes in this package.</p>
     *
     * @param builderConsumer a {@link Consumer} that updates a supplied {@link EurekaRegistrationConfig.Builder}; must
     *                        not be {@code null}
     * @return a non-{@code null} {@link EurekaRegistrationServerFeature}
     * @throws NullPointerException if {@code builderConsumer} is {@code null}
     * @see #builder()
     */
    public static EurekaRegistrationServerFeature create(Consumer<EurekaRegistrationConfig.Builder> builderConsumer) {
        return builder()
                .update(builderConsumer)
                .build();
    }

    @Override // ServerFeature
    public String name() {
        return this.prototype.name();
    }

    /**
     * Returns the prototype.
     *
     * @return the prototype
     */
    @Override // RuntimeType.Api<EurekaRegistrationConfig>
    public EurekaRegistrationConfig prototype() {
        return this.prototype;
    }

    @Override // ServerFeature
    public void setup(ServerFeatureContext featureContext) {
        if (!this.prototype().enabled()) {
            if (LOGGER.isLoggable(INFO)) {
                LOGGER.log(INFO,
                           "The " + this.type() + " ServerFeature implementation is disabled");
            }
            return;
        }
        // Just the default?
        featureContext.socket(DEFAULT_SOCKET_NAME)
                .httpRouting() // a builder
                .addFeature(new EurekaRegistrationHttpFeature(this.prototype()));
    }

    @Override // ServerFeature
    public String type() {
        return EUREKA_ID;
    }

    @Override // Weighted
    public double weight() {
        return this.prototype.weight();
    }

}
