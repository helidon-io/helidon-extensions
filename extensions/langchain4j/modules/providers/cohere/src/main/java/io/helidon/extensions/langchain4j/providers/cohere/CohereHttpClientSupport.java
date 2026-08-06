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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;

final class CohereHttpClientSupport {
    private static final ProxySelector NO_PROXY_SELECTOR = new FixedProxySelector(Proxy.NO_PROXY);

    private CohereHttpClientSupport() {
    }

    static HttpClientBuilder create(Proxy proxy) {
        var proxySelector = switch (proxy.type()) {
            case DIRECT -> NO_PROXY_SELECTOR;
            case HTTP -> httpProxySelector(proxy);
            case SOCKS -> throw new IllegalArgumentException("SOCKS proxies are not supported by the JDK HTTP client");
        };

        var clientBuilder = java.net.http.HttpClient.newBuilder()
                .proxy(proxySelector);
        return JdkHttpClient.builder()
                .httpClientBuilder(clientBuilder);
    }

    private static ProxySelector httpProxySelector(Proxy proxy) {
        if (proxy.address() instanceof InetSocketAddress address) {
            return ProxySelector.of(address);
        }
        throw new IllegalArgumentException("HTTP proxy address must be an InetSocketAddress");
    }

    private static final class FixedProxySelector extends ProxySelector {
        private final List<Proxy> proxies;

        private FixedProxySelector(Proxy proxy) {
            this.proxies = List.of(proxy);
        }

        @Override
        public List<Proxy> select(URI uri) {
            return proxies;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress socketAddress, IOException failure) {
            // A fixed selector has no proxy state to update.
        }
    }
}
