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

package io.helidon.extensions.oci.v3.examples.imperative;

import java.util.Objects;

import io.helidon.webserver.http.HttpRules;
import io.helidon.webserver.http.HttpService;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest;

/**
 * REST API for the OCI Object Storage example.
 */
class ObjectStorageService implements HttpService {
    private final ObjectStorage objectStorage;

    ObjectStorageService(ObjectStorage objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage);
    }

    @Override
    public void routing(HttpRules rules) {
        rules.get("/namespace", this::namespace);
    }

    @Override
    public void afterStop() {
        try {
            objectStorage.close();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close OCI Object Storage client.", e);
        }
    }

    private void namespace(ServerRequest req, ServerResponse res) {
        String namespace = objectStorage.getNamespace(GetNamespaceRequest.builder().build())
                .getValue();
        res.send(namespace);
    }
}
