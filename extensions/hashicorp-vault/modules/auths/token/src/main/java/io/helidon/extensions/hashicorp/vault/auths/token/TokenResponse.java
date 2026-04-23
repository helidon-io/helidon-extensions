/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.hashicorp.vault.auths.token;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.helidon.extensions.hashicorp.vault.VaultResponse;
import io.helidon.extensions.hashicorp.vault.VaultToken;
import io.helidon.extensions.hashicorp.vault.rest.ApiEntityResponse;

import io.helidon.json.JsonObject;

import static io.helidon.extensions.hashicorp.vault.VaultUtil.arrayToList;
import static io.helidon.extensions.hashicorp.vault.VaultUtil.toMap;

/**
 * Response returning a token.
 */
public abstract class TokenResponse extends VaultResponse {
    private final VaultToken token;
    private final String accessor;
    private final List<String> policies;
    private final List<String> tokenPolicies;
    private final Map<String, String> metadata;
    private final String entityId;
    private final String tokenType;
    private final boolean orphan;

    protected TokenResponse(ApiEntityResponse.Builder<?, ? extends VaultResponse, JsonObject> builder) {
        super(builder);

        JsonObject auth = builder.entity().objectValue("auth").orElseThrow();

        this.accessor = auth.stringValue("accessor").orElseThrow();
        this.policies = arrayToList(auth.arrayValue("policies").orElse(null));
        this.tokenPolicies = arrayToList(auth.arrayValue("token_policies").orElse(null));
        this.metadata = toMap(auth, "metadata");
        this.entityId = auth.stringValue("entity_id").orElseThrow();
        this.tokenType = auth.stringValue("token_type").orElseThrow();
        this.orphan = auth.booleanValue("orphan").orElseThrow();
        this.token = VaultToken.builder()
                .token(auth.stringValue("client_token").orElseThrow())
                .leaseDuration(Duration.ofSeconds(auth.intValue("lease_duration").orElseThrow()))
                .renewable(auth.booleanValue("renewable").orElseThrow())
                .build();
    }

    /**
     * Token that was received.
     *
     * @return the token
     */
    public VaultToken token() {
        return token;
    }

    /**
     * Accessor.
     *
     * @return accessor
     */
    public String accessor() {
        return accessor;
    }

    /**
     * List of policy names.
     *
     * @return policies
     */
    public List<String> policies() {
        return policies;
    }

    /**
     * List of token policy names.
     *
     * @return token policies
     */
    public List<String> tokenPolicies() {
        return tokenPolicies;
    }

    /**
     * Metadata. When a token is created with metadata attached, it is available through this method.
     *
     * @return key/values of metadata
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Entity id.
     *
     * @return entity id
     */
    public String entityId() {
        return entityId;
    }

    /**
     * Type of the token.
     *
     * @return token type
     * @see TokenAuth#TYPE_SERVICE
     * @see TokenAuth#TYPE_BATCH
     */
    public String tokenType() {
        return tokenType;
    }

    /**
     * Whether the token is orphan (no parent).
     *
     * @return {@code true} if orphan
     */
    public boolean orphan() {
        return orphan;
    }
}
