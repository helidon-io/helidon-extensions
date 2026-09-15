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

package io.helidon.openapi.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonVersionResolverTest {

    @Test
    void defaultHelidonVersionOptionIsShorthandV4() {
        HelidonDeclarativeCodegen codegen = new HelidonDeclarativeCodegen();

        String defaultVersion = codegen.cliOptions().stream()
                .filter(option -> "helidonVersion".equals(option.getOpt()))
                .findFirst()
                .orElseThrow()
                .getDefault();

        assertThat(defaultVersion, is("v4"));
    }

    @Test
    void processOptsExposesConfiguredHelidonVersion() {
        HelidonDeclarativeCodegen codegen = new HelidonDeclarativeCodegen();
        codegen.additionalProperties().put("helidonVersion", "4.5.0");

        codegen.processOpts();

        assertThat(codegen.additionalProperties().get("helidonVersion"), is("4.5.0"));
    }

    @Test
    void resolveHelidonVersionLeavesSpecificVersionAlone() {
        URI unusedEndpoint = URI.create("http://127.0.0.1:1/api/versions/");

        assertThat(HelidonVersionResolver.resolve("4.5.0", unusedEndpoint), is("4.5.0"));
    }

    @Test
    void resolveHelidonVersionFetchesVersionShorthand() throws Exception {
        try (TestHttpEndpoint endpoint = TestHttpEndpoint.responding(200, "4.5.9\n")) {
            String resolved = HelidonVersionResolver.resolve("v4", endpoint.baseUri());

            assertThat(resolved, is("4.5.9"));
            assertThat(endpoint.requestLine(), containsString("GET /api/versions/v4 HTTP/1.1"));
        }
    }

    @Test
    void resolveHelidonVersionFetchesLiveVersionShorthand() {
        String resolved = HelidonVersionResolver.resolve(
                "v4",
                HelidonVersionResolver.RESOLUTION_URI);

        assertThat("Resolved Helidon version: " + resolved, resolved.matches("4\\.\\d+\\.\\d+"), is(true));
    }

    @Test
    void resolveHelidonVersionRejectsBlankResponse() throws Exception {
        try (TestHttpEndpoint endpoint = TestHttpEndpoint.responding(200, "\n")) {
            RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    () -> HelidonVersionResolver.resolve("v4", endpoint.baseUri()));

            assertThat(exception.getMessage(), containsString("Failed to resolve Helidon version 'v4'"));
            assertThat(exception.getMessage(), containsString("Set helidonVersion to a specific Helidon release"));
            assertThat(exception.getCause(), is(notNullValue()));
        }
    }

    @Test
    void resolveHelidonVersionFailsClearlyOnHttpError() throws Exception {
        try (TestHttpEndpoint endpoint = TestHttpEndpoint.responding(404, "not found")) {
            RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    () -> HelidonVersionResolver.resolve("v4", endpoint.baseUri()));

            assertThat(exception.getMessage(), containsString("Failed to resolve Helidon version 'v4'"));
            assertThat(exception.getMessage(), containsString("Set helidonVersion to a specific Helidon release"));
            assertThat(exception.getCause(), is(notNullValue()));
        }
    }

    private static final class TestHttpEndpoint implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Thread thread;
        private final AtomicReference<String> requestLine = new AtomicReference<>();

        private TestHttpEndpoint(ServerSocket serverSocket, int statusCode, String body) {
            this.serverSocket = serverSocket;
            this.thread = new Thread(() -> serve(statusCode, body), "helidon-version-test-server");
            this.thread.setDaemon(true);
        }

        static TestHttpEndpoint responding(int statusCode, String body) throws IOException {
            ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            TestHttpEndpoint endpoint = new TestHttpEndpoint(serverSocket, statusCode, body);
            endpoint.thread.start();
            return endpoint;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + serverSocket.getLocalPort() + "/api/versions/");
        }

        String requestLine() throws InterruptedException {
            thread.join(2_000);
            return requestLine.get();
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            thread.join(2_000);
        }

        private void serve(int statusCode, String body) {
            try (serverSocket; Socket socket = serverSocket.accept()) {
                readRequest(socket);
                byte[] responseBody = body.getBytes(StandardCharsets.UTF_8);
                String statusText = statusCode >= 200 && statusCode < 300 ? "OK" : "ERROR";
                String headers = "HTTP/1.1 "
                        + statusCode
                        + " "
                        + statusText
                        + "\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Length: "
                        + responseBody.length
                        + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(responseBody);
            } catch (IOException ignored) {
                // The test may close the server socket while the server thread is waiting for a connection.
            }
        }

        private void readRequest(Socket socket) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            requestLine.set(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Read headers before writing the response.
            }
        }
    }
}
