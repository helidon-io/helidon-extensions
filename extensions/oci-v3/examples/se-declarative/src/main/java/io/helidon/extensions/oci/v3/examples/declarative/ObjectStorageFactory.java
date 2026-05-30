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

package io.helidon.extensions.oci.v3.examples.declarative;

import java.util.Optional;
import java.util.function.Supplier;

import io.helidon.service.registry.Service;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;

/**
 * Provides the OCI Object Storage client as an application service.
 */
@Service.Singleton
@Service.ExternalContracts(ObjectStorage.class)
class ObjectStorageFactory implements Supplier<ObjectStorage> {
    private final ObjectStorage objectStorage;

    @Service.Inject
    ObjectStorageFactory(BasicAuthenticationDetailsProvider authenticationDetailsProvider, Optional<Region> region) {
        ObjectStorageClient.Builder builder = ObjectStorageClient.builder();

        region.ifPresent(builder::region);

        this.objectStorage = builder.build(authenticationDetailsProvider);
    }

    @Override
    public ObjectStorage get() {
        return objectStorage;
    }

    @Service.PreDestroy
    void shutdown() {
        try {
            objectStorage.close();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close OCI Object Storage client.", e);
        }
    }
}
