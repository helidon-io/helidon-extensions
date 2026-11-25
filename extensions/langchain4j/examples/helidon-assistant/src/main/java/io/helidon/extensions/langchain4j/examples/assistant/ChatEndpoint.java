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

package io.helidon.extensions.langchain4j.examples.assistant;

import java.io.StringReader;
import java.util.Objects;

import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.BadRequestException;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

@RestServer.Endpoint
@Http.Path("/chat")
@Service.Singleton
class ChatEndpoint {
    private static final int MAX_MESSAGE_LENGTH = 8_000;
    private static final int MAX_SUMMARY_LENGTH = 16_000;

    private final HelidonAssistant assistant;

    @Service.Inject
    ChatEndpoint(HelidonAssistant assistant, DocsIngestor docs) {
        this.assistant = Objects.requireNonNull(assistant);
        // Resolving this dependency completes ingestion before the endpoint is available.
        Objects.requireNonNull(docs);
    }

    @Http.POST
    @Http.Consumes(MediaTypes.APPLICATION_JSON_VALUE)
    @Http.Produces(MediaTypes.APPLICATION_JSON_VALUE)
    JsonObject chat(@Http.Entity String body) {
        JsonObject request = parseRequest(body);
        String message = stringValue(request, "message", "", MAX_MESSAGE_LENGTH);
        if (message.isBlank()) {
            throw new BadRequestException("message must be a non-blank string");
        }
        String summary = stringValue(request, "summary", "", MAX_SUMMARY_LENGTH);
        return assistant.chat(message, summary);
    }

    private static JsonObject parseRequest(String body) {
        try (var reader = Json.createReader(new StringReader(body))) {
            JsonValue value = reader.readValue();
            if (value instanceof JsonObject object) {
                return object;
            }
            throw new BadRequestException("Request body must be a JSON object");
        } catch (JsonException e) {
            throw new BadRequestException("Request body must be a JSON object", e);
        }
    }

    private static String stringValue(JsonObject request, String name, String defaultValue, int maxLength) {
        JsonValue value = request.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof JsonString text) || text.getString().length() > maxLength) {
            throw new BadRequestException(name + " must be a string of at most " + maxLength + " characters");
        }
        return text.getString();
    }
}
