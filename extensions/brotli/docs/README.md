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

Configure the streams through the encoding's `inputStreamConfig(...)` and
`outputStreamConfig(...)` options. For example, use
`BrotliInputStreamConfig.builder().maxWindowBits(22).build()` as the input
configuration to reject streams declaring a window larger than 4 MiB. The
maximum window exponent defaults to `24` and accepts values from `10` through
`24`. HTTP decoding always verifies the end of the encoded entity, even if
the input configuration sets `verifyEndOfInput(false)`.

Standard HTTP `br` uses Brotli's built-in dictionary. Attaching dictionary chunks
through the input configuration causes `BrotliEncoding` construction to throw
`IllegalArgumentException`. Attached dictionaries are supported only by the
standalone decoder.

The service-loaded provider accepts the same options under `input-stream` and
`output-stream` in a `content-encodings` entry with `type: br`; for example,
`input-stream.max-window-bits: 22` and `output-stream.window: 18`. The
builder's `config(...)` method accepts those properties in the component node.
Use `name(...)` to set the instance name, which defaults to `br`; the content
coding identifier remains `br`. For service-loaded instances, the name
supplied by the content-encoding context takes precedence over the node's
`name`.

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
try (var encoded = new BrotliOutputStream(output)) {
    encoded.write(data);
}

var decoderConfig = BrotliInputStreamConfig.builder().verifyEndOfInput(true).build();
try (var decoded = new BrotliInputStream(input, decoderConfig)) {
    int decodedLimit = 1_048_576; // Choose a limit for the application.
    byte[] data = decoded.readNBytes(decodedLimit + 1);
    if (data.length > decodedLimit) {
        throw new IOException("Decoded Brotli data exceeds the limit");
    }
}
```

Closing either wrapper closes its underlying stream. Close the output wrapper
to finish the Brotli stream before consuming its compressed bytes.

The one-argument constructors use `BrotliInputStream.DEFAULT_CONFIG` and
`BrotliOutputStream.DEFAULT_CONFIG`. Configuration contains codec options, not
the underlying stream, and can be reused for multiple wrappers.

### Resource limits

Small Brotli inputs can expand into large entities. `BrotliInputStream` does not
impose a decoded-byte limit; callers that materialize untrusted input should
enforce one, as in the example above. The decoder starts with 1 KiB of history
and grows it as output is decoded, up to the configured window limit. Set
`max-window-bits` when a smaller per-stream maximum is required, and bound the
number of concurrent decoders for untrusted inputs.

When the source contains exactly one Brotli entity, enable
`verify-end-of-input` to require EOF and reject trailing bytes. HTTP
`br` decoding enables this check. The standalone default allows a progressive
decoder to finish before an open source stream reaches EOF.

For HTTP server requests, configure WebServer's `maxPayloadSize` to limit the
decoded request size. Its default does not impose a payload-size limit.
`maxBufferedEntitySize` applies when an entity is explicitly buffered; it does
not limit direct streaming or all materializing readers. Client responses and
standalone streams likewise need an application-owned decoded-byte limit.

### Stream configuration

Pass a Helidon `Config` node to the configuration builder's `config(...)` method
to set stream options. Supply the underlying stream to the constructor.

| Stream | Key | Default | Supported values |
| --- | --- | --- | --- |
| Output | `quality` | `0` | `0` |
| Output | `window` | `18` | `10` through `24` |
| Output | `cache-input-size` | `64000` bytes | A `Size` from `4` through `536870911` bytes (less than 512 MiB) |
| Input | `cache-buffer-size` | `256` bytes | A `Size` from `1` through `2147483647` bytes |
| Input | `max-window-bits` | `24` | `10` through `24` |
| Input | `verify-end-of-input` | `false` | `true` or `false` |
| Input | `dictionary-chunks` | Empty list | Ordered Base64 strings, at most 15 chunks; standalone decoding only |

The cache options use `io.helidon.common.Size`. Configuration accepts a byte count,
such as `64000`, or a value with a unit, such as `64 kB` or `4 KiB`.
For programmatic configuration, use `cacheInputSize(Size.parse("64 kB"))` or
`cacheBufferSize(Size.create(256))`. The stream constructors reject sizes outside
the supported ranges before allocating a cache.

All keys are optional. For example, using `io.helidon.config.Config`,
`io.helidon.config.ConfigSources`, and `java.util.Map`:

```java
var config = Config.just(ConfigSources.create(Map.of(
        "encoder.quality", "0",
        "encoder.window", "16",
        "encoder.cache-input-size", "64 kB",
        "decoder.cache-buffer-size", "4 KiB",
        "decoder.max-window-bits", "22",
        "decoder.verify-end-of-input", "true")));

var encoderConfig = BrotliOutputStreamConfig.builder().config(config.get("encoder")).build();
try (var encoded = new BrotliOutputStream(output, encoderConfig)) {
    encoded.write(data);
}

var decoderConfig = BrotliInputStreamConfig.builder().config(config.get("decoder")).build();
try (var decoded = new BrotliInputStream(input, decoderConfig)) {
    byte[] restored = decoded.readAllBytes();
}
```

The `encoder` and `decoder` node names are application choices. Each configuration builder
reads the keys directly from the supplied node; the stream constructors reject unsupported values.

For standalone streams that use an attached dictionary, configure each chunk as
a standard Base64 string. For example, `dictionary-chunks.0: YWJj` and
`dictionary-chunks.1: ZGVmZ2g=` attach the bytes `abc` followed by `defgh`.
The stream treats the chunks as one dictionary in that order. References must
remain within the concatenated dictionary contents.

A configured list replaces chunks previously supplied through the builder.
An empty list clears them; an absent key preserves them. Malformed Base64 is
rejected when `config(...)` is called, with the failing configuration key.
The stream constructor enforces the 15-chunk limit. HTTP `BrotliEncoding`
rejects attached dictionaries, including those supplied through configuration.

The maximum input window is checked from the compressed header before its ring
buffer is allocated. The default accepts the full standard Brotli window range.

## Supported compression

The imported encoder implements compression quality `0`, which is the default.
Other quality values are rejected. The decoder accepts Brotli data produced
at other compression qualities.

## Source and licenses

The codec adapts Thibault Vallin's Java implementation to the
`io.helidon.extensions.brotli` package, with Helidon configuration and stream contract
adaptations.

Oracle source is licensed under Apache License 2.0. Portions derived from
Google Brotli retain their MIT notices. The complete MIT license is included in
[THIRD_PARTY_LICENSES.txt](../THIRD_PARTY_LICENSES.txt) and in the codec JAR.
