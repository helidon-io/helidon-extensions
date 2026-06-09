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

import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;

/**
 * Declarative REST API for the OCI Object Storage example.
 */
@Service.Singleton
@RestServer.Endpoint
@Http.Path("/objectstorage")
class ObjectStorageEndpoint {
    private final ObjectStorage objectStorage;

    @Service.Inject
    ObjectStorageEndpoint(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Http.GET
    @Http.Path("/namespace")
    String namespace() {
        return objectStorage.getNamespace(GetNamespaceRequest.builder().build())
                .getValue();
    }
}
