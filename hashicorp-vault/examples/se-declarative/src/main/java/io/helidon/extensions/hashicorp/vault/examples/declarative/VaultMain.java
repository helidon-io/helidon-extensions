/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.examples.declarative;

import io.helidon.logging.common.LogConfig;
import io.helidon.service.registry.Service;
import io.helidon.service.registry.ServiceRegistryManager;
import io.helidon.service.registry.Services;
import io.helidon.webserver.WebServer;

@Service.GenerateBinding
public class VaultMain {
    static {
        LogConfig.initClass();
    }

    public static void main(String[] args) {
        ServiceRegistryManager.start(ApplicationBinding.create());

        String baseAddress = "http://localhost:" + Services.get(WebServer.class).port() + "/";
        System.out.println("Server started on " + baseAddress);
        System.out.println();
        System.out.println("Key/Value Version 1 Secrets Engine");
        System.out.println("\t" + baseAddress + "kv1/enable");
        System.out.println("\t" + baseAddress + "kv1/create");
        System.out.println("\t" + baseAddress + "kv1/secrets/first/secret");
        System.out.println("\tcurl -i -X DELETE " + baseAddress + "kv1/secrets/first/secret");
        System.out.println("\t" + baseAddress + "kv1/disable");
        System.out.println();
        System.out.println("Key/Value Version 2 Secrets Engine");
        System.out.println("\t" + baseAddress + "kv2/create");
        System.out.println("\t" + baseAddress + "kv2/secrets/first/secret");
        System.out.println("\tcurl -i -X DELETE " + baseAddress + "kv2/secrets/first/secret");
        System.out.println();
        System.out.println("Transit Secrets Engine");
        System.out.println("\t" + baseAddress + "transit/enable");
        System.out.println("\t" + baseAddress + "transit/keys");
        System.out.println("\t" + baseAddress + "transit/encrypt/secret_text");
        System.out.println("\t" + baseAddress + "transit/decrypt/cipher_text");
        System.out.println("\t" + baseAddress + "transit/sign");
        System.out.println("\t" + baseAddress + "transit/verify/sign/signature_text");
        System.out.println("\t" + baseAddress + "transit/hmac");
        System.out.println("\t" + baseAddress + "transit/verify/hmac/hmac_text");
        System.out.println("\tcurl -i -X DELETE " + baseAddress + "transit/keys");
        System.out.println("\t" + baseAddress + "transit/disable");
    }
}
