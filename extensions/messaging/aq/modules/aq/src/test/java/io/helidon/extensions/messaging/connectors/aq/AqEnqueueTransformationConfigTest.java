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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AqEnqueueTransformationConfigTest {
    @Test
    void enabledPolicyRequiresANameAndHasNoEmptyFactory() {
        var failure = assertThrows(IllegalArgumentException.class,
                                   () -> AqEnqueueTransformationConfig.builder().build());
        assertThat(failure.getMessage(), containsString("name is required when enabled"));
        assertThrows(NoSuchMethodException.class, () -> AqEnqueueTransformationConfig.class.getMethod("create"));

        AqEnqueueTransformationConfig policy = AqEnqueueTransformationConfig.builder().name("APP.TRANSFORM").build();
        assertThat(policy.enabled(), is(true));
        assertThat(policy.name(), is(Optional.of("APP.TRANSFORM")));
    }

    @Test
    void disabledPolicyCanOmitTheName() {
        AqEnqueueTransformationConfig policy = AqEnqueueTransformationConfig.builder().enabled(false).build();
        assertThat(policy.enabled(), is(false));
        assertThat(policy.name(), is(Optional.empty()));
    }

    @Test
    void blankNamesAreRejectedEvenWhenDisabled() {
        for (String name : List.of("", " ", "\t\n")) {
            for (boolean enabled : List.of(true, false)) {
                var failure = assertThrows(IllegalArgumentException.class, () -> AqEnqueueTransformationConfig.builder()
                        .name(name).enabled(enabled).build());
                assertThat(failure.getMessage(), containsString("name must not be blank"));
            }
        }
        assertThrows(NullPointerException.class, () -> AqEnqueueTransformationConfig.builder().name((String) null));
        assertThrows(NullPointerException.class,
                     () -> AqConnectorConfig.builder().enqueueTransformation((AqEnqueueTransformationConfig) null));
        assertThrows(NullPointerException.class,
                     () -> AqOutgoingConfig.builder().enqueueTransformation((AqEnqueueTransformationConfig) null));
    }

    @Test
    void absentPolicyDoesNotApplyATransformation() {
        assertThat(AqRuntimeConfig.outgoing(defaults(), outgoing()).enqueueTransformation(), is(Optional.empty()));
        assertThat(AqRuntimeConfig.incoming(defaults(), incoming()).enqueueTransformation(), is(Optional.empty()));
    }

    @Test
    void connectorPolicyIsInheritedOnlyByOutgoingChannels() {
        AqConnectorConfig connector = AqConnectorConfig.builder(defaults())
                .enqueueTransformation(transformation -> transformation.name("APP.DEFAULT_TRANSFORM"))
                .buildPrototype();

        assertThat(AqRuntimeConfig.outgoing(connector, outgoing()).enqueueTransformation(),
                   is(Optional.of("APP.DEFAULT_TRANSFORM")));
        assertThat(AqRuntimeConfig.incoming(connector, incoming()).enqueueTransformation(), is(Optional.empty()));
    }

    @Test
    void channelPolicyReplacesConnectorPolicy() {
        AqConnectorConfig connector = AqConnectorConfig.builder(defaults())
                .enqueueTransformation(transformation -> transformation.name("APP.DEFAULT_TRANSFORM"))
                .buildPrototype();
        AqOutgoingConfig channel = AqOutgoingConfig.builder(outgoing())
                .enqueueTransformation(transformation -> transformation.name("APP.CHANNEL_TRANSFORM"))
                .build();

        assertThat(AqRuntimeConfig.outgoing(connector, channel).enqueueTransformation(),
                   is(Optional.of("APP.CHANNEL_TRANSFORM")));
    }

    @Test
    void disabledChannelPolicySuppressesTheInheritedName() {
        AqConnectorConfig connector = AqConnectorConfig.builder(defaults())
                .enqueueTransformation(transformation -> transformation.name("APP.DEFAULT_TRANSFORM"))
                .buildPrototype();
        AqOutgoingConfig channel = AqOutgoingConfig.builder(outgoing())
                .enqueueTransformation(transformation -> transformation.enabled(false))
                .build();

        assertThat(channel.enqueueTransformation().orElseThrow().name(), is(Optional.empty()));
        assertThat(AqRuntimeConfig.outgoing(connector, channel).enqueueTransformation(), is(Optional.empty()));
    }

    @Test
    void channelPolicyDoesNotInheritDisabledState() {
        AqConnectorConfig connector = AqConnectorConfig.builder(defaults())
                .enqueueTransformation(transformation -> transformation.name("APP.DEFAULT_TRANSFORM").enabled(false))
                .buildPrototype();
        AqOutgoingConfig channel = AqOutgoingConfig.builder(outgoing())
                .enqueueTransformation(transformation -> transformation.name("APP.CHANNEL_TRANSFORM"))
                .build();

        assertThat(AqRuntimeConfig.outgoing(connector, outgoing()).enqueueTransformation(), is(Optional.empty()));
        assertThat(AqRuntimeConfig.outgoing(connector, channel).enqueueTransformation(),
                   is(Optional.of("APP.CHANNEL_TRANSFORM")));
    }

    @Test
    void configurationLoadsNamesAndAllowsExplicitChannelDisable() {
        AqConnectorConfig connector = AqConnectorConfig.builder(defaults())
                .config(Config.just(ConfigSources.create(Map.of("enqueue-transformation.name", "APP.CONFIGURED"))))
                .buildPrototype();
        AqOutgoingConfig channel = AqOutgoingConfig.builder(outgoing())
                .config(Config.just(ConfigSources.create(Map.of("enqueue-transformation.enabled", "false"))))
                .build();
        AqEnqueueTransformationConfig disabled = AqEnqueueTransformationConfig.create(
                Config.just(ConfigSources.create(Map.of("enabled", "false"))));

        assertThat(AqRuntimeConfig.outgoing(connector, outgoing()).enqueueTransformation(),
                   is(Optional.of("APP.CONFIGURED")));
        assertThat(AqRuntimeConfig.outgoing(connector, channel).enqueueTransformation(), is(Optional.empty()));
        assertThat(disabled.enabled(), is(false));
        assertThat(disabled.name(), is(Optional.empty()));
    }

    private static AqConnectorConfig defaults() {
        return AqConnectorConfig.builder().name("aq").destination("ORDERS").buildPrototype();
    }

    private static AqIncomingConfig incoming() {
        return AqIncomingConfig.builder().connector("aq").channelName("orders").build();
    }

    private static AqOutgoingConfig outgoing() {
        return AqOutgoingConfig.builder().connector("aq").channelName("orders").build();
    }
}
