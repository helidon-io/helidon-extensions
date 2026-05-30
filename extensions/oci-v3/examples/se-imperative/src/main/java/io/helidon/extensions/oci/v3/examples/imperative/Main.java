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

import io.helidon.config.Config;
import io.helidon.http.Status;
import io.helidon.logging.common.LogConfig;
import io.helidon.service.registry.ServiceRegistry;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;

import com.oracle.bmc.Region;
import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;

/**
 * Main class of the example.
 */
public final class Main {
    private Main() {
    }

    /**
     * Application main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        LogConfig.configureRuntime();

        Config config = Config.create();
        ServiceRegistryManager registryManager = ServiceRegistryManager.create();
        ObjectStorage objectStorage = objectStorage(registryManager.registry());

        WebServer server = WebServer.builder()
                .config(config.get("server"))
                .routing(routing -> routing(routing, objectStorage))
                .build()
                .start();

        System.out.println("WEB server is up! http://localhost:" + server.port() + "/objectstorage/namespace");
    }

    static void routing(HttpRouting.Builder routing, ObjectStorage objectStorage) {
        routing.register("/objectstorage", new ObjectStorageService(objectStorage))
                .error(BmcException.class, (req, res, ex) -> res.status(status(ex))
                        .send(ex.getMessage()));
    }

    static Status status(BmcException exception) {
        if (exception.isTimeout()) {
            return Status.GATEWAY_TIMEOUT_504;
        }
        if (exception.isClientSide()) {
            return Status.BAD_GATEWAY_502;
        }
        int status = exception.getStatusCode();
        if (status >= 100 && status <= 599) {
            return Status.create(status);
        }
        return Status.BAD_GATEWAY_502;
    }

    static ObjectStorage objectStorage(ServiceRegistry registry) {
        BasicAuthenticationDetailsProvider authenticationDetailsProvider =
                registry.get(BasicAuthenticationDetailsProvider.class);
        ObjectStorageClient.Builder builder = ObjectStorageClient.builder();

        registry.first(Region.class).ifPresent(builder::region);

        return builder.build(authenticationDetailsProvider);
    }
}
