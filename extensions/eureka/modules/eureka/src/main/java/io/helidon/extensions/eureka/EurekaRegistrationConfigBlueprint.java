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

import java.util.function.Supplier;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Weighted;
import io.helidon.webclient.http1.Http1ClientConfig;
import io.helidon.webserver.spi.ServerFeatureProvider;

import static io.helidon.extensions.eureka.EurekaRegistrationServerFeature.EUREKA_ID;

/**
 * Configuration of {@link io.helidon.extensions.eureka.EurekaRegistrationServerFeature}.
 *
 * @see EurekaRegistrationServerFeature
 */
@Prototype.Blueprint(decorator = EurekaRegistrationConfigSupport.BuilderDecorator.class)
@Prototype.Configured(value = EUREKA_ID, root = false)
@Prototype.Provides(ServerFeatureProvider.class)
interface EurekaRegistrationConfigBlueprint extends Prototype.Factory<EurekaRegistrationServerFeature> {

    /**
     * An {@link Http1ClientConfig.Builder} used to build an internal client for communicating with a Eureka server.
     *
     * <p>An {@link Http1ClientConfig} built from the supplied builder must have its {@link Http1ClientConfig#baseUri()}
     * property set to address a Eureka Server instance.</p>
     *
     * @return an {@link Http1ClientConfig.Builder}
     */
    // Ideally the signature would be Supplier<? extends Http1ClientConfig.Builder>, but generated code in that case
    // does not compile.
    Supplier<Http1ClientConfig.Builder> clientBuilderSupplier();

    /**
     * An {@link InstanceInfoConfig} describing the service instance to be registered.
     *
     * @return the {@link InstanceInfoConfig} describing the service instance to be registered
     * @see InstanceInfoConfig
     */
    @Option.Configured("instance")
    @Option.DefaultMethod("create")
    InstanceInfoConfig instanceInfo();

    /**
     * Whether this feature will be enabled.
     *
     * @return whether this feature will be enabled
     */
    @Option.Configured
    @Option.DefaultBoolean(true)
    boolean enabled();

    /**
     * The non-{@code null} name of this instance; {@value EurekaRegistrationServerFeature#EUREKA_ID} is a default
     * value.
     *
     * @return the non-{@code null} name of this instance; {@value EurekaRegistrationServerFeature#EUREKA_ID} is a
     *         default value
     */
    @Option.Default(EUREKA_ID)
    String name();

    /**
     * The (zero or positive) {@linkplain io.helidon.common.Weighted weight} of this instance.
     *
     * @return the (zero or positive) weight of this instance
     */
    @Option.Configured
    @Option.DefaultDouble(Weighted.DEFAULT_WEIGHT)
    double weight();

}
