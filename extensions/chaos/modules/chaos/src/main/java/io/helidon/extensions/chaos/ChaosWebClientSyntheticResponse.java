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

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.helidon.common.buffers.DataReader;
import io.helidon.common.buffers.DataWriter;
import io.helidon.common.socket.HelidonSocket;
import io.helidon.http.ClientResponseHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.http.WritableHeaders;
import io.helidon.webclient.api.ClientConnection;
import io.helidon.webclient.api.WebClientServiceRequest;
import io.helidon.webclient.api.WebClientServiceResponse;

/**
 * Creates a WebClient response without using a network transport.
 */
final class ChaosWebClientSyntheticResponse {
    private ChaosWebClientSyntheticResponse() {
    }

    static WebClientServiceResponse create(WebClientServiceRequest request, ChaosSyntheticResponse effect) {
        Objects.requireNonNull(request, "request is null");
        Objects.requireNonNull(effect, "effect is null");

        byte[] body = effect.body();
        WritableHeaders<?> headers = WritableHeaders.create();
        effect.headers().forEach((name, value) -> headers.set(HeaderNames.create(name), value));
        effect.mediaType().ifPresent(headers::contentType);
        headers.contentLength(body.length);

        WebClientServiceResponse.Builder response = WebClientServiceResponse.builder()
                .serviceRequest(request)
                .whenComplete(new CompletableFuture<>())
                .connection(new SyntheticConnection())
                .status(Status.create(effect.status()))
                .headers(ClientResponseHeaders.create(headers));
        if (body.length > 0) {
            response.inputStream(new ByteArrayInputStream(body));
        }
        return response.build();
    }

    /**
     * HTTP/1 response lifecycle support for a response that owns no transport resources.
     */
    private static final class SyntheticConnection implements ClientConnection {
        private final DataReader reader = DataReader.create(() -> null);

        @Override
        public DataReader reader() {
            return reader;
        }

        @Override
        public DataWriter writer() {
            throw new UnsupportedOperationException("Synthetic response has no data writer");
        }

        @Override
        public String channelId() {
            return "chaos-synthetic-response";
        }

        @Override
        public HelidonSocket helidonSocket() {
            throw new UnsupportedOperationException("Synthetic response has no socket");
        }

        @Override
        public void readTimeout(Duration readTimeout) {
            Objects.requireNonNull(readTimeout, "readTimeout is null");
        }

        @Override
        public boolean isConnected() {
            return false;
        }

        @Override
        public void releaseResource() {
        }

        @Override
        public void closeResource() {
        }
    }
}
