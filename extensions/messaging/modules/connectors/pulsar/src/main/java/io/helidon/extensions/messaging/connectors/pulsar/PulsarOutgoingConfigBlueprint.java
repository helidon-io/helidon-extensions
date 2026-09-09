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

package io.helidon.extensions.messaging.connectors.pulsar;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * Apache Pulsar outgoing channel overrides. Omitted options inherit the connector defaults.
 */
@Api.Preview
@Prototype.Blueprint(decorator = PulsarConnectorConfigSupport.OutgoingBuilderDecorator.class)
@Prototype.Sealed
@Prototype.Configured
interface PulsarOutgoingConfigBlueprint {
    /**
     * Name of the logical messaging channel.
     *
     * @return channel name
     */
    @Option.Required
    @Option.Configured
    String channelName();

    /**
     * Pulsar broker service URL override. Defaults to the connector service URL.
     *
     * @return service URL
     */
    @Option.Configured(PulsarConnectorConfigSupport.SERVICE_URL_PROPERTY)
    Optional<String> serviceUrl();

    /**
     * Additional Pulsar client properties. Values are confidential because authentication material may be present.
     *
     * @return client properties
     */
    @Option.Configured(PulsarConnectorConfigSupport.CLIENT_PROPERTIES_PROPERTY)
    @Option.Confidential
    @Option.Singular("clientProperty")
    Map<String, String> clientProperties();

    /**
     * Pulsar topic.
     *
     * @return topic
     */
    @Option.Configured(PulsarConnectorConfigSupport.TOPIC_PROPERTY)
    Optional<String> topic();

    /**
     * Built-in payload schema override. Defaults to the connector schema.
     *
     * @return payload schema
     */
    @Option.Configured(PulsarConnectorConfigSupport.SCHEMA_PROPERTY)
    Optional<PulsarSchemaType> schema();

    /**
     * Name of a custom {@link PulsarSchemaProvider} in the Helidon Service Registry. When present, the provider schema
     * overrides the built-in schema. Defaults to the connector's provider name.
     *
     * @return custom schema provider name
     */
    @Option.Configured(PulsarConnectorConfigSupport.SCHEMA_PROVIDER_PROPERTY)
    Optional<String> schemaProvider();

    /**
     * Maximum duration to await an outgoing broker persistence result.
     *
     * @return send timeout
     */
    @Option.Configured(PulsarConnectorConfigSupport.SEND_TIMEOUT_PROPERTY)
    Optional<Duration> sendTimeout();

    /**
     * Maximum duration for graceful connector shutdown.
     *
     * @return close timeout
     */
    @Option.Configured(PulsarConnectorConfigSupport.CLOSE_TIMEOUT_PROPERTY)
    Optional<Duration> closeTimeout();

    /**
     * Additional Pulsar producer properties. Typed connector options override conflicting keys.
     *
     * @return producer properties
     */
    @Option.Configured(PulsarConnectorConfigSupport.PRODUCER_PROPERTIES_PROPERTY)
    @Option.Confidential
    @Option.Singular("producerProperty")
    Map<String, String> producerProperties();
}
