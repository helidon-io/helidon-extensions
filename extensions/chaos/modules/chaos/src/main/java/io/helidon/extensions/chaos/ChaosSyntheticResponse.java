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
package io.helidon.extensions.chaos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.helidon.common.media.type.MediaType;

/**
 * Synthetic HTTP response applied to a matched request.
 *
 * @param status HTTP status code
 * @param headers response headers
 * @param mediaType optional response media type
 * @param body response body bytes
 */
record ChaosSyntheticResponse(int status,
                              Map<String, String> headers,
                              Optional<MediaType> mediaType,
                              byte[] body) implements ChaosEffect {

    ChaosSyntheticResponse {
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers is null")));
        Objects.requireNonNull(mediaType, "mediaType is null");
        body = Objects.requireNonNull(body, "body is null").clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
