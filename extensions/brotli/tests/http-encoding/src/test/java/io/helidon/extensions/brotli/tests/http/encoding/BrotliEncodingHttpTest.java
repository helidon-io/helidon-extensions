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

package io.helidon.extensions.brotli.tests.http.encoding;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import io.helidon.extensions.brotli.http.encoding.BrotliEncoding;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webserver.WebServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;

class BrotliEncodingHttpTest {
    private static final String ENTITY = """
            Helidon Brotli interoperability fixture.
            The quick brown fox jumps over the lazy dog.
            Compression and decompression must preserve every byte.
            Helidon Brotli interoperability fixture.
            The quick brown fox jumps over the lazy dog.
            Compression and decompression must preserve every byte.
            """;
    // Fixed independent fixture generated with Google's Brotli CLI, quality 11.
    private static final byte[] COMPRESSED_ENTITY = HexFormat.of().parseHex(
            "a1d808006e9c1c6bb7e3562a2051a9732a1ba16ac494db69f617ca3afa1e"
                    + "b6bbc1017b719c0740810fbcf9803caa63ce66dc94afa39bc644cb2837d8"
                    + "2c662c6697cdc3767d49f14370584c19b131d12f0047efff81c62d5e34b5"
                    + "4f85f268282c2d13a3e821d7290a03");

    private static WebServer server;
    private static Http1Client client;
    private static HttpClient jdkClient;
    private static URI baseUri;

    @BeforeAll
    static void startServer() {
        server = WebServer.builder()
                .port(0)
                .routing(routing -> routing
                        .put("/echo", (req, res) -> res.send(req.content().as(String.class)))
                        .get("/entity", (_, res) -> {
                            res.header(HeaderNames.CONTENT_LENGTH,
                                       String.valueOf(ENTITY.getBytes(StandardCharsets.UTF_8).length));
                            res.send(ENTITY);
                        })
                        .get("/fixture", (_, res) -> {
                            res.header(HeaderNames.CONTENT_ENCODING, "br");
                            res.send(COMPRESSED_ENTITY);
                        }))
                .build()
                .start();
        baseUri = URI.create("http://localhost:" + server.port());
        client = Http1Client.builder().baseUri(baseUri).build();
        jdkClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @AfterAll
    static void stopServer() {
        if (client != null) {
            client.closeResource();
        }
        if (jdkClient != null) {
            jdkClient.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void serverDecodesIndependentCompressedRequest() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(baseUri.resolve("/echo"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Encoding", "br")
                .header("Accept-Encoding", "identity")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(COMPRESSED_ENTITY))
                .build();

        var response = jdkClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode(), is(200));
        assertThat(response.body(), is(ENTITY));
        assertThat(response.headers().firstValue("Content-Encoding"), is(Optional.empty()));
    }

    @Test
    void serverAppliesPayloadLimitToDecodedBrotliBytes() throws IOException, InterruptedException {
        int decodedLimit = 128;
        assertThat(COMPRESSED_ENTITY.length, lessThan(decodedLimit));
        assertThat(ENTITY.getBytes(StandardCharsets.UTF_8).length, greaterThan(decodedLimit));

        var limitedServer = WebServer.builder()
                .port(0)
                .maxPayloadSize(decodedLimit)
                .routing(routing -> routing.put("/consume", (req, res) -> {
                    req.content().as(String.class);
                    res.send();
                }))
                .build()
                .start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + limitedServer.port() + "/consume"))
                    .header("Content-Encoding", "br")
                    .header("Content-Type", "text/plain")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(COMPRESSED_ENTITY))
                    .build();

            var response = jdkClient.send(request, HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode(), is(413));
        } finally {
            limitedServer.stop();
        }
    }

    @Test
    void serverRejectsTrailingBrotliBytesAndKeepsServing() throws IOException, InterruptedException {
        byte[] malformed = Arrays.copyOf(COMPRESSED_ENTITY, COMPRESSED_ENTITY.length + 5);
        System.arraycopy("ABCDE".getBytes(StandardCharsets.US_ASCII), 0,
                         malformed, COMPRESSED_ENTITY.length, 5);
        var request = HttpRequest.newBuilder(baseUri.resolve("/echo"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Encoding", "br")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(malformed))
                .build();

        var response = jdkClient.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode(), greaterThanOrEqualTo(400));

        var healthy = client.get("/entity").request(String.class);
        assertThat(healthy.status(), is(Status.OK_200));
        assertThat(healthy.entity(), is(ENTITY));
    }

    @Test
    void clientDecodesIndependentCompressedResponse() {
        var response = client.get("/fixture").request(String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is(ENTITY));
        assertThat(response.headers().get(HeaderNames.CONTENT_ENCODING).get(), is("br"));
    }

    @Test
    void serverNegotiatesBrotliWithCorrectContentLength() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(baseUri.resolve("/entity"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept-Encoding", "br")
                .build();

        var response = jdkClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("Content-Encoding"), is(Optional.of("br")));
        // Helidon can buffer the encoded entity and advertise its compressed length.
        response.headers().firstValueAsLong("Content-Length")
                .ifPresent(contentLength -> assertThat(contentLength, is((long) response.body().length)));
        try (var decoded = BrotliEncoding.create().decoder().apply(new ByteArrayInputStream(response.body()))) {
            assertThat(decoded.readAllBytes(), is(ENTITY.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void clientExplicitlyEncodesRequestAndAutomaticallyDecodesResponse() {
        var encoder = BrotliEncoding.create().encoder();
        var request = client.put("/echo").header(HeaderNames.ACCEPT_ENCODING, "br");
        encoder.headers(request.headers());

        var response = request.outputStream(out -> {
            try (var encoded = encoder.apply(out)) {
                encoded.write(ENTITY.getBytes(StandardCharsets.UTF_8));
            }
        }, String.class);

        assertThat(response.status(), is(Status.OK_200));
        assertThat(response.entity(), is(ENTITY));
        assertThat(response.headers().get(HeaderNames.CONTENT_ENCODING).get(), is("br"));
    }

    @Test
    void negotiationPreservesGzipWhenBrotliIsRejected() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(baseUri.resolve("/entity"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept-Encoding", "br;q=0, gzip")
                .build();

        var response = jdkClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("Content-Encoding"), is(Optional.of("gzip")));
        try (var decoded = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            assertThat(decoded.readAllBytes(), is(ENTITY.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void rejectedBrotliUsesIdentity() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(baseUri.resolve("/entity"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept-Encoding", "br;q=0")
                .build();

        var response = jdkClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode(), is(200));
        assertThat(response.headers().firstValue("Content-Encoding"), is(Optional.empty()));
        assertThat(response.body(), is(ENTITY));
    }
}
