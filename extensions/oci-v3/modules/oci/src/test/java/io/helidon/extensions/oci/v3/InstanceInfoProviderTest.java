/*
 * Copyright (c) 2024, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.oci.v3;

import java.net.URI;
import java.util.Map;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.json.JsonNull;
import io.helidon.json.JsonObject;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class InstanceInfoProviderTest {

    private ServiceRegistryManager registryManager;
    private ServiceRegistry registry;

    private final int port;

    InstanceInfoProviderTest(URI uri) {
        this.port = uri.getPort();
    }

    @SetUpRoute
    static void routing(HttpRules rules) {
        rules.get("/opc/v2/instance", ImdsEmulator::emulateImds);
    }

    void setUp(Config config) {
        OciConfigProvider.config(OciConfig.create(config));
        registryManager = ServiceRegistryManager.create();
        registry = registryManager.registry();
    }

    @AfterEach
    void tearDown() {
        registry = null;
        if (registryManager != null) {
            registryManager.shutdown();
        }
    }

    @Test
    void testInstanceInfoFromImds() {
        Config config = Config.just(ConfigSources.create(Map.of("imds-base-uri",
                                                                "http://localhost:%d/opc/v2/".formatted(port))));
        setUp(config);

        ImdsInstanceInfo instanceInfo = registry.get(ImdsInstanceInfo.class);
        assertInstanceInfoValues(instanceInfo);
        assertMetadataJson(instanceInfo.json(), ImdsEmulator.DISPLAY_NAME);
        assertThat(instanceInfo.json()
                           .objectValue("agentConfig")
                           .flatMap(it -> it.booleanValue("allPluginsDisabled"))
                           .orElseThrow(),
                   is(false));
        assertThat(instanceInfo.json()
                           .objectValue("shapeConfig")
                           .flatMap(it -> it.numberValue("memoryInGBs"))
                           .orElseThrow()
                           .doubleValue(),
                   is(12.0));
    }

    @Test
    void testHelidonJsonBuilderRetainsConfiguredJson() {
        JsonObject metadataJson = metadataJson("helidon-json");

        ImdsInstanceInfo instanceInfo = instanceInfoBuilder()
                .json(metadataJson)
                .build();

        assertThat(instanceInfo.json(), is(metadataJson));
        assertMetadataJson(instanceInfo.json(), "helidon-json");
        assertStructuredJson(instanceInfo.json());
    }

    private static ImdsInstanceInfo.Builder instanceInfoBuilder() {
        return ImdsInstanceInfo.builder()
                .canonicalRegionName(ImdsEmulator.CANONICAL_REGION_NAME)
                .displayName(ImdsEmulator.DISPLAY_NAME)
                .hostName(ImdsEmulator.HOST_NAME)
                .region(ImdsEmulator.REGION)
                .ociAdName(ImdsEmulator.OCI_AD_NAME)
                .faultDomain(ImdsEmulator.FAULT_DOMAIN)
                .compartmentId(ImdsEmulator.COMPARTMENT_ID)
                .tenantId(ImdsEmulator.TENANT_ID);
    }

    private static JsonObject metadataJson(String displayName) {
        return JsonObject.builder()
                .set(ImdsInstanceInfoProvider.CANONICAL_REGION_NAME, ImdsEmulator.CANONICAL_REGION_NAME)
                .set(ImdsInstanceInfoProvider.DISPLAY_NAME, displayName)
                .set(ImdsInstanceInfoProvider.HOST_NAME, ImdsEmulator.HOST_NAME)
                .set(ImdsInstanceInfoProvider.REGION, ImdsEmulator.REGION)
                .set(ImdsInstanceInfoProvider.OCI_AD_NAME, ImdsEmulator.OCI_AD_NAME)
                .set(ImdsInstanceInfoProvider.FAULT_DOMAIN, ImdsEmulator.FAULT_DOMAIN)
                .set(ImdsInstanceInfoProvider.COMPARTMENT_ID, ImdsEmulator.COMPARTMENT_ID)
                .set(ImdsInstanceInfoProvider.TENANT_ID, ImdsEmulator.TENANT_ID)
                .set("agentConfig", JsonObject.builder()
                        .set("allPluginsDisabled", false)
                        .set("managementDisabled", false)
                        .build())
                .set("shapeConfig", JsonObject.builder()
                        .set("maxVnicAttachments", 2)
                        .set("memoryInGBs", 12.0)
                        .build())
                .set("metadata", JsonObject.builder()
                        .set("nullable", JsonNull.instance())
                        .build())
                .build();
    }

    private static void assertInstanceInfoValues(ImdsInstanceInfo instanceInfo) {
        assertThat(instanceInfo.canonicalRegionName(), is(ImdsEmulator.CANONICAL_REGION_NAME));
        assertThat(instanceInfo.displayName(), is(ImdsEmulator.DISPLAY_NAME));
        assertThat(instanceInfo.hostName(), is(ImdsEmulator.HOST_NAME));
        assertThat(instanceInfo.region(), is(ImdsEmulator.REGION));
        assertThat(instanceInfo.ociAdName(), is(ImdsEmulator.OCI_AD_NAME));
        assertThat(instanceInfo.faultDomain(), is(ImdsEmulator.FAULT_DOMAIN));
        assertThat(instanceInfo.compartmentId(), is(ImdsEmulator.COMPARTMENT_ID));
        assertThat(instanceInfo.tenantId(), is(ImdsEmulator.TENANT_ID));
    }

    private static void assertMetadataJson(JsonObject metadataJson, String displayName) {
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.CANONICAL_REGION_NAME).orElseThrow(),
                   is(ImdsEmulator.CANONICAL_REGION_NAME));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.DISPLAY_NAME).orElseThrow(), is(displayName));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.HOST_NAME).orElseThrow(), is(ImdsEmulator.HOST_NAME));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.REGION).orElseThrow(), is(ImdsEmulator.REGION));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.OCI_AD_NAME).orElseThrow(),
                   is(ImdsEmulator.OCI_AD_NAME));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.FAULT_DOMAIN).orElseThrow(),
                   is(ImdsEmulator.FAULT_DOMAIN));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.COMPARTMENT_ID).orElseThrow(),
                   is(ImdsEmulator.COMPARTMENT_ID));
        assertThat(metadataJson.stringValue(ImdsInstanceInfoProvider.TENANT_ID).orElseThrow(),
                   is(ImdsEmulator.TENANT_ID));
    }

    private static void assertStructuredJson(JsonObject metadataJson) {
        assertThat(metadataJson.objectValue("agentConfig")
                           .flatMap(it -> it.booleanValue("allPluginsDisabled"))
                           .orElseThrow(),
                   is(false));
        assertThat(metadataJson.objectValue("shapeConfig")
                           .flatMap(it -> it.numberValue("maxVnicAttachments"))
                           .orElseThrow()
                           .intValueExact(),
                   is(2));
        assertThat(metadataJson.objectValue("shapeConfig")
                           .flatMap(it -> it.numberValue("memoryInGBs"))
                           .orElseThrow()
                           .doubleValue(),
                   is(12.0));
        assertThat(metadataJson.objectValue("metadata")
                           .flatMap(it -> it.value("nullable"))
                           .orElseThrow(),
                   is(JsonNull.instance()));
    }

    static class ImdsEmulator {
        static String CANONICAL_REGION_NAME = "us-helidon-1";
        static String DISPLAY_NAME = "helidon-server";
        static String HOST_NAME = DISPLAY_NAME;
        static String REGION = "hel";
        static String OCI_AD_NAME = "hel-ad-1";
        static String FAULT_DOMAIN = "FAULT-DOMAIN-3";
        static String COMPARTMENT_ID = "ocid1.compartment.oc1..dummyCompartment";
        static String TENANT_ID = "ocid1.tenancy.oc1..dummyTenancy";
        private static final String IMDS_RESPONSE = """
                {
                  "agentConfig": {
                    "allPluginsDisabled": false,
                    "managementDisabled": false,
                    "monitoringDisabled": false
                  },
                  "availabilityDomain": "RPOG:US-ASHBURN-AD-2",
                  "canonicalRegionName": "%s",
                  "compartmentId": "%s",
                  "definedTags": {
                    "Oracle-Tags": {
                      "CreatedBy": "ocid1.flock.oc1..aaaaaaaafiugcy22eekoer3usi7rlyjatcftll2gsakgi42bzsl5zpfbshbq",
                      "CreatedOn": "2024-02-07T23:31:16.518Z"
                    }
                  },
                  "displayName": "%s",
                  "faultDomain": "%s",
                  "hostname": "%s",
                  "id": "ocid1.instance.oc1.iad.dummy",
                  "image": "ocid1.image.oc1.iad.dummy",
                  "instancePoolId": "ocid1.instancepool.oc1.iad.dummy",
                  "metadata": {
                    "compute_management": {
                      "instance_configuration": {
                        "state": "SUCCEEDED"
                      }
                    },
                    "hostclass": "helidon-overlay"
                  },
                  "ociAdName": "%s",
                  "region": "%s",
                  "regionInfo": {
                    "realmDomainComponent": "oraclecloud.com",
                    "realmKey": "oc1",
                    "regionIdentifier": "us-ashburn-1",
                    "regionKey": "IAD"
                  },
                  "shape": "VM.Standard.A1.Flex",
                  "shapeConfig": {
                    "maxVnicAttachments": 2,
                    "memoryInGBs": 12.0,
                    "networkingBandwidthInGbps": 2.0,
                    "ocpus": 2.0
                  },
                  "state": "Running",
                  "tenantId": "%s",
                  "timeCreated": 1707348698696
                }
                """.formatted(CANONICAL_REGION_NAME,
                              COMPARTMENT_ID,
                              DISPLAY_NAME,
                              FAULT_DOMAIN,
                              HOST_NAME,
                              OCI_AD_NAME,
                              REGION,
                              TENANT_ID);

        private static void emulateImds(ServerRequest req, ServerResponse res) {
            res.headers().contentType(MediaTypes.APPLICATION_JSON);
            res.send(IMDS_RESPONSE);
        }
    }
}
