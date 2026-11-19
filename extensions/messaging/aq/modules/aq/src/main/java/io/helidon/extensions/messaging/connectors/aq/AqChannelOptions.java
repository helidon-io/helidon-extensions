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

package io.helidon.extensions.messaging.connectors.aq;

import java.time.Duration;
import java.util.Optional;

import javax.jms.ConnectionFactory;
import javax.sql.DataSource;

import io.helidon.builder.api.Option;

/**
 * Shared AQ options. Absent channel options inherit connector configuration.
 */
interface AqChannelOptions {
    /**
     * Application-owned data source. The connector closes borrowed connections, never the data source.
     *
     * @return shared data source
     */
    Optional<DataSource> dataSource();

    /**
     * Name of the application-owned data source in the Service Registry.
     *
     * @return data source name
     */
    @Option.Configured
    Optional<String> dataSourceName();

    /**
     * Oracle AQ connection factory supplied by the application.
     *
     * @return connection factory
     */
    Optional<ConnectionFactory> connectionFactory();

    /**
     * Name of the Oracle AQ connection factory in the Service Registry.
     *
     * @return connection factory name
     */
    @Option.Configured
    Optional<String> connectionFactoryName();

    /**
     * JDBC connection URL, used when no application resource is configured.
     *
     * @return JDBC URL
     */
    @Option.Configured
    @Option.Confidential
    Optional<String> jdbcUrl();

    /**
     * Name of an existing AQ queue.
     *
     * @return queue name
     */
    @Option.Configured
    Optional<String> destination();

    /**
     * Owner of the queue. Defaults to the connected database user.
     *
     * @return queue owner
     */
    @Option.Configured
    Optional<String> schema();

    /**
     * Look up the destination through JNDI instead of a direct queue name and schema.
     * Channel JNDI settings replace the complete connector JNDI configuration.
     * Configuring a direct channel destination or schema instead selects direct destination resolution.
     *
     * @return JNDI destination lookup settings
     */
    @Option.Configured
    Optional<AqJndiConfig> jndi();

    /**
     * Destination type. Defaults to a single-consumer queue.
     *
     * @return destination type
     */
    @Option.Configured
    Optional<AqDestinationType> destinationType();

    /**
     * Payload storage of the queue. Defaults to JMS.
     *
     * @return payload type
     */
    @Option.Configured
    Optional<AqPayloadType> payloadType();

    /**
     * Converter for detached incoming and outgoing payloads.
     *
     * @return payload converter
     */
    Optional<AqPayloadConverter> payloadConverter();

    /**
     * Database connection user name.
     *
     * @return user name
     */
    @Option.Configured
    Optional<String> username();

    /**
     * Database connection password.
     *
     * @return password
     */
    @Option.Configured
    @Option.Confidential
    Optional<String> password();

    /**
     * Connection client identifier, when not supplied by the connection factory.
     *
     * @return client identifier
     */
    @Option.Configured
    Optional<String> clientId();

    /**
     * Use a connector-owned local transaction. Defaults to true.
     *
     * @return whether sessions are transacted
     */
    @Option.Configured
    Optional<Boolean> transacted();

    /**
     * Allow Java object messages. Disabled by default.
     *
     * @return whether serialization and deserialization are enabled
     */
    @Option.Configured
    Optional<Boolean> allowObjectMessages();

    /**
     * Maximum wait for graceful cleanup. Defaults to ten seconds.
     *
     * @return close timeout
     */
    @Option.Configured
    Optional<Duration> closeTimeout();

    /**
     * Connection recovery settings. Absent channel settings inherit the complete connector policy.
     * Recovery is enabled by default.
     *
     * @return connection recovery settings
     */
    @Option.Configured
    Optional<AqReconnectConfig> reconnect();
}
