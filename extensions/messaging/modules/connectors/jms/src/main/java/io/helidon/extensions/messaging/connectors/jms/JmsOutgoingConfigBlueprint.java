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

package io.helidon.extensions.messaging.connectors.jms;

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;
import io.helidon.messaging.spi.MessagingOutgoingConfig;

/**
 * Outgoing JMS channel configuration. Absent options inherit the connector's defaults.
 */
@Api.Preview
@Prototype.Blueprint(decorator = JmsChannelConfigSupport.Outgoing.BuilderDecorator.class)
@Prototype.Sealed
@Prototype.Configured
@Prototype.CustomMethods(JmsChannelConfigSupport.Outgoing.class)
interface JmsOutgoingConfigBlueprint extends MessagingOutgoingConfig, JmsChannelOptions {
    /**
     * JMS connection password read from configuration. This value is moved to {@link #passwordSource()} and cleared from
     * the builder before the prototype is created.
     *
     * @return configured password
     */
    @Override
    @Option.Configured(JmsRuntimeConfigSupport.PASSWORD_PROPERTY)
    @Option.Confidential
    @Option.Access("")
    @Option.Decorator(JmsChannelConfigSupport.Outgoing.ConfiguredPasswordDecorator.class)
    @Option.Redundant
    Optional<String> configuredPassword();
}
