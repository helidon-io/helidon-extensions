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

package io.helidon.extensions.langchain4j.providers.cohere;

import java.time.Duration;
import java.util.List;

import io.helidon.common.Weight;
import io.helidon.service.registry.Qualifier;
import io.helidon.service.registry.Service;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

@Service.Singleton
@Service.Named("*")
@Weight(85.0D)
public class MockHttpClientFactory implements Service.ServicesFactory<HttpClientBuilder> {
    @Override
    public List<Service.QualifiedInstance<HttpClientBuilder>> services() {
        return List.of(Service.QualifiedInstance.create(new TrackingHttpClientBuilder(),
                                                        Qualifier.createNamed("customHttpClient")));
    }

    static final class TrackingHttpClientBuilder implements HttpClientBuilder {
        private Duration connectTimeout;
        private Duration readTimeout;
        private int buildCount;

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        @Override
        public Duration readTimeout() {
            return readTimeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
            return this;
        }

        @Override
        public HttpClient build() {
            buildCount++;
            return new HttpClient() {
                @Override
                public SuccessfulHttpResponse execute(HttpRequest request) {
                    throw new UnsupportedOperationException("Not used by configuration tests");
                }

                @Override
                public void execute(HttpRequest request,
                                    ServerSentEventParser parser,
                                    ServerSentEventListener listener) {
                    throw new UnsupportedOperationException("Not used by configuration tests");
                }
            };
        }

        int buildCount() {
            return buildCount;
        }
    }
}
