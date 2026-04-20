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

import java.net.UnknownHostException;

import io.helidon.config.Config;
import io.helidon.json.JsonObject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_YAML;
import static java.net.InetAddress.getLocalHost;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

final class TestJsonSerializationFromConfig {

    private Config config;

    TestJsonSerializationFromConfig() {
        super();
    }

    @BeforeEach
    void setup() {
        this.config = Config.just("""
                                          eureka:
                                            instance:
                                              name: "My Application"
                                          """,
                                  APPLICATION_YAML);
    }

    @Test
    void testSerialization() throws UnknownHostException {
        int actualPort = 8080;
        InstanceInfoConfig iic = InstanceInfoConfig.builder()
                .config(this.config.get("eureka.instance"))
                .build();
        JsonObject json = EurekaRegistrationHttpFeature.json(iic, actualPort, false);
        json = json.objectValue("instance", null);
        assertThat(json, not(nullValue()));
        assertThat(json.stringValue("instanceId", null), is(getLocalHost().getHostName() + ":" + actualPort));
        assertThat(json.stringValue("app", null), is("MY APPLICATION")); // native Eureka uppercases app names
        assertThat(json.stringValue("appGroupName", null), is("UNKNOWN"));
        assertThat(json.objectValue("dataCenterInfo")
                           .flatMap(it -> it.stringValue("name"))
                           .orElse(null), is("MyOwn"));
        assertThat(json.objectValue("dataCenterInfo")
                           .flatMap(it -> it.stringValue("@class"))
                           .orElse(null), is("com.netflix.appinfo.MyDataCenterInfo"));
        assertThat(json.stringValue("ipAddr", null), is(getLocalHost().getHostAddress()));
        assertThat(json.stringValue("hostName", null), is(getLocalHost().getHostName()));

        var portObject = json.objectValue("port");
        assertThat(portObject.flatMap(it -> it.intValue("$"))
                           .orElse(-42), is(actualPort));
        assertThat(portObject.flatMap(it -> it.booleanValue("@enabled"))
                           .orElse(false), is(true));

        var securePortObject = json.objectValue("securePort");
        assertThat(securePortObject.flatMap(it -> it.intValue("$"))
                           .orElse(-42), is(443));
        assertThat(securePortObject.flatMap(it -> it.booleanValue("@enabled"))
                           .orElse(true), is(false));

        assertThat(json.stringValue("status", null), is("UP"));
        assertThat(json.objectValue("metadata", null), not(nullValue()));
        assertThat(json.stringValue("sid", null), is("na"));
        assertThat(json.intValue("countryId", -1), is(1));

        var leaseInfo = json.objectValue("leaseInfo");
        assertThat(leaseInfo.flatMap(it -> it.intValue("renewalIntervalInSecs"))
                           .orElse(-1),
                   is(30));
        assertThat(leaseInfo.flatMap(it -> it.intValue("durationInSecs"))
                           .orElse(-1),
                   is(90));
    }
}
