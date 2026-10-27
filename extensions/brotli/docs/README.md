# Brotli

The Brotli extension provides compression and decompression implemented in Java,
plus HTTP `br` content encoding for Helidon WebClient and WebServer. The runtime
does not require JNI, native libraries, or platform-specific artifacts.

## Artifacts

| Artifact | Purpose |
| --- | --- |
| `io.helidon.extensions.brotli:helidon-extensions-brotli-bom` | Aligns the versions of the Brotli extension artifacts. |
| `io.helidon.extensions.brotli:helidon-extensions-brotli` | Standalone compression and decompression streams. |
| `io.helidon.extensions.brotli:helidon-extensions-brotli-http-encoding` | HTTP content encoding and decoding for WebClient and WebServer. Includes the codec. |

This extension targets Helidon 27.0.0. Its development version is
`27.0.0-SNAPSHOT`.

## Maven dependencies

Import the extension BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.brotli</groupId>
            <artifactId>helidon-extensions-brotli-bom</artifactId>
            <version>27.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

For HTTP support, add:

```xml
<dependency>
    <groupId>io.helidon.extensions.brotli</groupId>
    <artifactId>helidon-extensions-brotli-http-encoding</artifactId>
</dependency>
```

For standalone streams, add `helidon-extensions-brotli` instead.

## HTTP content encoding

The HTTP artifact registers a `ContentEncodingProvider` for `br` through
`ServiceLoader`, using the same discovery mechanism as Helidon's gzip support.
Keep content-encoding service discovery enabled to use this provider automatically.

| Direction | Behavior |
| --- | --- |
| Server request | Decodes a request with `Content-Encoding: br`. |
| Server response | Negotiates `br` using the request's `Accept-Encoding` header. |
| Client request | Applications explicitly wrap the request output with the Brotli encoder. |
| Client response | Decodes a response with `Content-Encoding: br`. |

The provider's configuration key and content coding identifier are both `br`.
For explicit content-encoding registration, use
`io.helidon.extensions.brotli.http.encoding.BrotliEncoding.create()` wherever a
`ContentEncoding` is accepted. It uses the same fixed codec defaults as the
service-loaded provider.

```java
ContentEncodingContext encodings = ContentEncodingContext.builder()
        .addContentEncoding(BrotliEncoding.create())
        .build();
```

WebClient automatically decodes Brotli responses. Request compression is
explicit. For example, using an `Http1Client` connected to an echo endpoint:

```java
var encoder = BrotliEncoding.create().encoder();
var request = client.put("/echo").header(HeaderNames.ACCEPT_ENCODING, "br");
encoder.headers(request.headers());
var response = request.outputStream(output -> {
    try (var encoded = encoder.apply(output)) {
        encoded.write(bytes);
    }
}, String.class);
```

The encoder sets `Content-Encoding: br`. Closing the wrapper finishes the
encoded request, and the client decodes the response according to its
`Content-Encoding` header.

When gzip and Brotli are both available with the same accepted quality, gzip
retains its higher default provider priority. To request Brotli specifically,
send `Accept-Encoding: br`.

Encoding replaces an existing uncompressed `Content-Length` with the framing
appropriate for the encoded entity.

## Standalone streams

The stream types are in `io.helidon.extensions.brotli`.

```java
try (var encoded = BrotliOutputStream.builder().outputStream(output).build()) {
    encoded.write(data);
}

try (var decoded = BrotliInputStream.builder().inputStream(input).build()) {
    byte[] data = decoded.readAllBytes();
}
```

Closing either wrapper closes its underlying stream. Close the output wrapper
to finish the Brotli stream before consuming its compressed bytes.

## Supported compression

The imported encoder implements compression quality `0`, which is the default.
Other quality values are rejected. The decoder accepts Brotli data produced
at other compression qualities.

## Source and licenses

The codec adapts Thibault Vallin's Java implementation to the
`io.helidon.extensions.brotli` package, with Helidon builders and stream contract
adaptations.

Oracle source is licensed under Apache License 2.0. Portions derived from
Google Brotli retain their MIT notices. The complete MIT license is included in
[THIRD_PARTY_LICENSES.txt](../THIRD_PARTY_LICENSES.txt) and in the codec JAR.
