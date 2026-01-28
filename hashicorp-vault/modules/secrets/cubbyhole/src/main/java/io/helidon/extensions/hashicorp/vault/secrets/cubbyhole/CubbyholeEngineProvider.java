/*
 * Copyright (c) 2021, 2025 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.secrets.cubbyhole;

import io.helidon.config.Config;
import io.helidon.extensions.hashicorp.vault.Engine;
import io.helidon.extensions.hashicorp.vault.rest.RestApi;
import io.helidon.extensions.hashicorp.vault.spi.SecretsEngineProvider;

/**
 * Provider supporting the {@code Cubbyhole} secrets engine API.
 */
public class CubbyholeEngineProvider implements SecretsEngineProvider<CubbyholeSecrets> {

    /**
     * @deprecated Do not use this constructor, this is a service loader service!
     */
    @Deprecated
    public CubbyholeEngineProvider() {
    }

    @Override
    public Engine<CubbyholeSecrets> supportedEngine() {
        return CubbyholeSecrets.ENGINE;
    }

    @Override
    public CubbyholeSecrets createSecrets(Config config, RestApi restApi, String mount) {
        return new CubbyholeSecretsImpl(restApi, mount);
    }
}
