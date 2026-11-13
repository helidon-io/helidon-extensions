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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.common.Errors;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AqJndiConfigTest {
    @Test
    void destinationIsRequiredAndNonblank() {
        assertThrows(Errors.ErrorMessagesException.class, () -> AqJndiConfig.builder().build());
        assertThrows(NullPointerException.class, () -> AqJndiConfig.builder().destination(null));
        for (String destination : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> AqJndiConfig.builder().destination(destination).build());
        }
    }

    @Test
    void configuredEnvironmentPreservesDottedNamesAndRedactsSecrets() {
        Config config = Config.just(ConfigSources.create(Map.of(
                "jndi.destination", "queues/Orders",
                "jndi.env-properties.java.naming.factory.initial", "example.ContextFactory",
                "jndi.env-properties.java.naming.provider.url", "ldap://directory.example",
                "jndi.env-properties.java.naming.security.credentials", "test-secret")));
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq").config(config).buildPrototype();
        AqRuntimeConfig runtime = AqRuntimeConfig.incoming(connector, incoming());
        AqJndiConfig jndi = runtime.jndi().orElseThrow();

        assertThat(runtime.destination(), is("queues/Orders"));
        assertThat(runtime.schema(), is(Optional.empty()));
        assertThat(jndi.envProperties(), is(Map.of(
                "java.naming.factory.initial", "example.ContextFactory",
                "java.naming.provider.url", "ldap://directory.example",
                "java.naming.security.credentials", "test-secret")));
        assertThat(jndi.toString(), not(containsString("test-secret")));
        assertThat(AqJndiConfig.builder(jndi).toString(), not(containsString("test-secret")));
        assertThat(connector.toString(), not(containsString("test-secret")));
        assertThat(runtime.toString(), not(containsString("test-secret")));
    }

    @Test
    void environmentIsCopiedAndRequiresValidEntries() {
        Map<String, String> values = new HashMap<>();
        values.put("java.naming.provider.url", "ldap://first.example");
        AqJndiConfig jndi = AqJndiConfig.builder().destination("queues/Orders").envProperties(values).build();
        values.put("java.naming.provider.url", "ldap://changed.example");
        assertThat(jndi.envProperties(), is(Map.of("java.naming.provider.url", "ldap://first.example")));
        assertThrows(UnsupportedOperationException.class, () -> jndi.envProperties().put("changed", "value"));

        assertThrows(NullPointerException.class, () -> AqJndiConfig.builder().destination("queues/Orders")
                .envProperties((Map<String, String>) null));
        assertThrows(NullPointerException.class, () -> AqJndiConfig.builder().destination("queues/Orders")
                .putEnvProperty(null, "value"));
        assertThrows(NullPointerException.class, () -> AqJndiConfig.builder().destination("queues/Orders")
                .putEnvProperty("property", null));
        for (String key : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class, () -> AqJndiConfig.builder().destination("queues/Orders")
                    .putEnvProperty(key, "value").build());
        }
        values.put(null, "value");
        assertThrows(NullPointerException.class,
                     () -> AqJndiConfig.builder().destination("queues/Orders").envProperties(values).build());
        values.remove(null);
        values.put("property", null);
        assertThrows(NullPointerException.class,
                     () -> AqJndiConfig.builder().destination("queues/Orders").envProperties(values).build());
    }

    @Test
    void jndiDefaultsSupportIncomingAndOutgoingChannelsWithoutDirectNames() {
        AqJndiConfig jndi = AqJndiConfig.builder().destination("topics/Orders").build();
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq")
                .jndi(jndi).destinationType(AqDestinationType.TOPIC).buildPrototype();

        AqRuntimeConfig incoming = AqRuntimeConfig.incoming(connector, incoming());
        AqRuntimeConfig outgoing = AqRuntimeConfig.outgoing(connector, outgoing());
        assertThat(incoming.jndi().orElseThrow(), sameInstance(jndi));
        assertThat(incoming.destination(), is("topics/Orders"));
        assertThat(incoming.destinationType(), is(AqDestinationType.TOPIC));
        assertThat(outgoing.jndi().orElseThrow(), sameInstance(jndi));
        assertThat(outgoing.destination(), is("topics/Orders"));
    }

    @Test
    void channelJndiReplacesDirectConnectorDestinationAndSchema() {
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq")
                .destination("ORDERS").schema("APP").buildPrototype();
        AqIncomingConfig channel = AqIncomingConfig.builder(incoming())
                .jndi(jndi -> jndi.destination("queues/Orders")).build();
        AqRuntimeConfig runtime = AqRuntimeConfig.incoming(connector, channel);

        assertThat(runtime.destination(), is("queues/Orders"));
        assertThat(runtime.schema(), is(Optional.empty()));
        assertThat(runtime.jndi().orElseThrow().destination(), is("queues/Orders"));
    }

    @Test
    void directChannelSelectionReplacesJndiDefaults() {
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq")
                .jndi(jndi -> jndi.destination("queues/Orders")).buildPrototype();
        AqOutgoingConfig channel = AqOutgoingConfig.builder(outgoing()).destination("PHYSICAL_ORDERS").build();
        AqRuntimeConfig runtime = AqRuntimeConfig.outgoing(connector, channel);

        assertThat(runtime.destination(), is("PHYSICAL_ORDERS"));
        assertThat(runtime.schema(), is(Optional.empty()));
        assertThat(runtime.jndi(), is(Optional.empty()));

        runtime = AqRuntimeConfig.outgoing(connector, AqOutgoingConfig.builder(channel).schema("APP").build());
        assertThat(runtime.destination(), is("PHYSICAL_ORDERS"));
        assertThat(runtime.schema(), is(Optional.of("APP")));
        assertThat(runtime.jndi(), is(Optional.empty()));
    }

    @Test
    void directDestinationsAndSchemasStillInheritIndependently() {
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq")
                .destination("ORDERS").schema("APP").buildPrototype();
        AqRuntimeConfig nameOverride = AqRuntimeConfig.incoming(connector,
                AqIncomingConfig.builder(incoming()).destination("OTHER_ORDERS").build());
        AqRuntimeConfig schemaOverride = AqRuntimeConfig.outgoing(connector,
                AqOutgoingConfig.builder(outgoing()).schema("OTHER_APP").build());

        assertThat(nameOverride.destination(), is("OTHER_ORDERS"));
        assertThat(nameOverride.schema(), is(Optional.of("APP")));
        assertThat(schemaOverride.destination(), is("ORDERS"));
        assertThat(schemaOverride.schema(), is(Optional.of("OTHER_APP")));
        assertThat(nameOverride.jndi(), is(Optional.empty()));
        assertThat(schemaOverride.jndi(), is(Optional.empty()));
    }

    @Test
    void connectorSchemaCanSupplyChannelsWithDifferentDirectDestinations() {
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq").schema("APP").buildPrototype();
        AqRuntimeConfig incoming = AqRuntimeConfig.incoming(connector,
                AqIncomingConfig.builder(incoming()).destination("INCOMING_ORDERS").build());
        AqRuntimeConfig outgoing = AqRuntimeConfig.outgoing(connector,
                AqOutgoingConfig.builder(outgoing()).destination("OUTGOING_ORDERS").build());

        assertThat(incoming.destination(), is("INCOMING_ORDERS"));
        assertThat(outgoing.destination(), is("OUTGOING_ORDERS"));
        assertThat(incoming.schema(), is(Optional.of("APP")));
        assertThat(outgoing.schema(), is(Optional.of("APP")));
    }

    @Test
    void channelJndiReplacesTheWholeEnvironment() {
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq")
                .jndi(jndi -> jndi.destination("queues/Default")
                        .putEnvProperty("java.naming.provider.url", "ldap://default.example")
                        .putEnvProperty("java.naming.security.credentials", "parent-secret"))
                .buildPrototype();
        AqIncomingConfig channel = AqIncomingConfig.builder(incoming())
                .jndi(jndi -> jndi.destination("queues/Orders")
                        .putEnvProperty("java.naming.provider.url", "ldap://channel.example"))
                .build();
        AqJndiConfig selected = AqRuntimeConfig.incoming(connector, channel).jndi().orElseThrow();

        assertThat(selected.destination(), is("queues/Orders"));
        assertThat(selected.envProperties(), is(Map.of("java.naming.provider.url", "ldap://channel.example")));
    }

    @Test
    void schemaOnlyOverrideCannotReuseTheInheritedJndiNameAsADirectQueue() {
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq")
                .jndi(jndi -> jndi.destination("queues/Orders")).buildPrototype();
        var failure = assertThrows(IllegalArgumentException.class, () -> AqRuntimeConfig.incoming(connector,
                AqIncomingConfig.builder(incoming()).schema("APP").build()));
        assertThat(failure.getMessage(), containsString("destination is required"));
    }

    @Test
    void sameScopeJndiAndDirectOptionsConflict() {
        AqJndiConfig jndi = AqJndiConfig.builder().destination("queues/Orders").build();
        for (AqConnectorConfig connector : List.of(
                AqConnectorConfig.builder().name("aq").jndi(jndi).destination("ORDERS").buildPrototype(),
                AqConnectorConfig.builder().name("aq").jndi(jndi).schema("APP").buildPrototype())) {
            var failure = assertThrows(IllegalArgumentException.class,
                                       () -> AqRuntimeConfig.incoming(connector, incoming()));
            assertThat(failure.getMessage(), containsString("mutually exclusive"));
        }
        AqConnectorConfig connector = AqConnectorConfig.builder().name("aq").buildPrototype();
        for (AqOutgoingConfig channel : List.of(
                AqOutgoingConfig.builder(outgoing()).jndi(jndi).destination("ORDERS").build(),
                AqOutgoingConfig.builder(outgoing()).jndi(jndi).schema("APP").build())) {
            var failure = assertThrows(IllegalArgumentException.class,
                                       () -> AqRuntimeConfig.outgoing(connector, channel));
            assertThat(failure.getMessage(), containsString("mutually exclusive"));
        }
    }

    private static AqIncomingConfig incoming() {
        return AqIncomingConfig.builder().connector("aq").channelName("orders").build();
    }

    private static AqOutgoingConfig outgoing() {
        return AqOutgoingConfig.builder().connector("aq").channelName("orders").build();
    }
}
