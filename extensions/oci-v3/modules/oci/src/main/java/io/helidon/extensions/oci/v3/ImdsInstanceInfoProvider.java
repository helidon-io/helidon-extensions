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

import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.common.LazyValue;
import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.json.JsonObject;
import io.helidon.service.registry.Service;

// the type must be fully qualified, as it is code generated
@Service.Singleton
@Weight(Weighted.DEFAULT_WEIGHT - 100)
class ImdsInstanceInfoProvider implements Supplier<Optional<io.helidon.extensions.oci.v3.ImdsInstanceInfo>> {

    // Commonly used key names for instance data that will be used for the getter methods
    static final String DISPLAY_NAME = "displayName";
    static final String HOST_NAME = "hostname";
    static final String CANONICAL_REGION_NAME = "canonicalRegionName";
    static final String OCI_AD_NAME = "ociAdName";
    static final String FAULT_DOMAIN = "faultDomain";
    static final String REGION = "region";
    static final String COMPARTMENT_ID = "compartmentId";
    static final String TENANT_ID = "tenantId";

    private LazyValue<Optional<ImdsInstanceInfo>> instanceInfo;

    ImdsInstanceInfoProvider(Supplier<OciConfig> config) {
        this.instanceInfo = LazyValue.create(() -> Optional.ofNullable(loadInstanceMetadata(config.get())));
    }

    @Override
    public Optional<ImdsInstanceInfo> get() {
        return instanceInfo.get();
    }

    ImdsInstanceInfo loadInstanceMetadata(OciConfig ociConfig) {
        JsonObject metadataJson = HelidonOci.imdsContent(ociConfig, HelidonOci.imdsUri(ociConfig));
        if (metadataJson != null) {
            String displayName = metadataJson.stringValue(DISPLAY_NAME)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + DISPLAY_NAME));
            String hostName = metadataJson.stringValue(HOST_NAME)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + HOST_NAME));
            String canonicalRegionName = metadataJson.stringValue(CANONICAL_REGION_NAME)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + CANONICAL_REGION_NAME));
            String region = metadataJson.stringValue(REGION)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + REGION));
            String ociAdName = metadataJson.stringValue(OCI_AD_NAME)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + OCI_AD_NAME));
            String faultDomain = metadataJson.stringValue(FAULT_DOMAIN)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + FAULT_DOMAIN));
            String compartmentId = metadataJson.stringValue(COMPARTMENT_ID)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + COMPARTMENT_ID));
            String tenantId = metadataJson.stringValue(TENANT_ID)
                    .orElseThrow(() -> new IllegalStateException("Missing IMDS metadata field: " + TENANT_ID));

            return ImdsInstanceInfo.builder()
                    .displayName(displayName)
                    .hostName(hostName)
                    .canonicalRegionName(canonicalRegionName)
                    .region(region)
                    .ociAdName(ociAdName)
                    .faultDomain(faultDomain)
                    .compartmentId(compartmentId)
                    .tenantId(tenantId)
                    .json(metadataJson)
                    .build();
        }
        return null;
    }
}
