# Java and native Brotli codec comparison

This deployment-skipped tooling module is enabled only by `jmh-brotli4j`. It adds
no native dependency to the codec, HTTP adapter, their BOM, or the existing
portable `jmh` module.

The comparator pins [Brotli4j 1.24.0](https://github.com/hyperxpro/Brotli4j/releases/tag/v1.24.0),
whose bundled C implementation is Brotli 1.2.1, as documented by the
[maintainer advisory](https://github.com/hyperxpro/Brotli4j/security/advisories/GHSA-x524-jrqf-vwq6).
Brotli4j declares [Apache License 2.0](https://github.com/hyperxpro/Brotli4j/blob/v1.24.0/pom.xml);
its upstream Java wrappers and [Google Brotli](https://github.com/google/brotli/blob/v1.2.1/LICENSE)
include MIT-licensed code. These libraries are dependencies, not copied sources.
The runtime artifact defaults to `native-linux-x86_64`; for another supported
platform override `-Dbrotli4j.native.artifactId=native-osx-aarch64`, for example.
Keep the Java and native artifact versions aligned.

## Operations and fairness

The primary methods are `javaEncode`, `nativeEncode`, `javaDecode` and
`nativeDecodeDirect`. Encoding uses fresh entity streams; Java decoding uses a
fresh input stream; native direct decoding uses Brotli4j's public one-shot array
API. The separate `nativeDecode` method measures its input-stream API where it
passes the fixture checks. The direct and streaming APIs perform different work
and must be labeled separately in results. Every invocation creates its own
codec state, destination and mutable encoder parameters. Only trial fixture byte
arrays are shared, read-only, across workers.

Fixtures are 256 bytes (`SHORT`) or 1 MiB (`LONG`), with structured JSON text
(`TEXT`) or deterministic bytes (`RANDOM`, SplittableRandom seed 7932). Both
encoders explicitly use quality 0, window exponent 18 and a 64,000-byte input
buffer. Brotli4j uses generic mode. The algorithms can produce different output
sizes at the same settings; the fixture report records those sizes and ratios.
Each encoder receives one `write(byte[])` call for the whole entity, then close,
with no intermediate flush. Both use a default-capacity `ByteArrayOutputStream`
and return its final copy, so compressible long entities do not pay an artificial
1 MiB destination preallocation. Both stream decoders use the JDK's bulk `readAllBytes`.

All decoders consume exactly the same native-produced compressed bytes. Trial
setup loads JNI, creates fixtures and verifies each encoder's output with Java
decoding and the native direct API. It separately captures native streaming
success or failure for both encoders' output in the standalone CSV. These checks
and native-library extraction/loading are outside timed operations. The decoder
APIs have different internal buffering models: native streaming
input buffering is set to 64,000 bytes; Helidon's single-byte read cache retains
its default because the measured operation uses bulk `readAllBytes`. Returned
arrays are consumed by JMH. No shared stream, custom dictionary, network I/O,
one-time startup, or malformed native input is measured.

### Native streaming limitation

Brotli4j 1.24.0's `BrotliInputStream` throws `IOException: unexpected end of input`
for this module's valid `LONG/TEXT` fixture, including its own encoder's output.
Both encoders' output passes Google C Brotli 1.2.1 and native direct decoding.
The result also occurs with the Java encoder before the buffer optimizations.
The tagged [stream decoder](https://github.com/hyperxpro/Brotli4j/blob/v1.24.0/brotli4j/src/main/java/com/aayushatharva/brotli4j/decoder/Decoder.java)
throws on source EOF in `NEEDS_MORE_INPUT`; its direct decompressor instead
allows a zero-input push to process remaining buffered bits before declaring
truncation. Public input/read buffer sizes, output caps and eager-output settings
did not make this fixture pass streaming decode.

Keep those streaming cells **N/A with the observed error**, never throughput or
silently truncated successes. The primary full-fixture matrix uses
`nativeDecodeDirect` and prominently labels the array-versus-stream difference.
Its public `Decoder.decompress` API returns a heap result and destroys its fresh
native decoder in `finally`; the benchmark checks `DONE`, non-null output and
expected length. It does not use the optional Netty `ByteBuf` API. No workaround
or native-library patch is applied. The valid additional native streaming matrix
is `SHORT/TEXT`, `SHORT/RANDOM`, and `LONG/RANDOM` only.

## Build and fixture report

Use JDK 27 and run from the repository root. Build the normal repository artifact
baseline before the opt-in leaf on a fresh checkout:

```shell
mvn -T 1C clean install -Ptests -DskipTests
mvn -Pjmh-brotli4j -pl :helidon-extensions-brotli,:helidon-extensions-brotli-benchmarks-brotli4j -DskipTests install
mvn -Pjmh-brotli4j -pl :helidon-extensions-brotli-benchmarks-brotli4j dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
cd extensions/brotli/benchmarks-brotli4j
java --enable-native-access=ALL-UNNAMED -cp 'target/classes:target/dependency/*' io.helidon.extensions.brotli.benchmarks.brotli4j.BrotliComparisonBenchmark
```

The standalone main prints CSV with four fixture cases, both compressed lengths,
compressed/input ratios, SHA-256 hashes of the shared raw and compressed input,
and the native streaming result/error for each encoder's output.
Keep that report alongside source revision, dependency/native binary hashes,
JDK, host topology and benchmark settings. For controlled campaigns set
`-Dcom.aayushatharva.brotli4j.native.workdir` to the campaign-owned scratch
directory and verify the loaded binary rather than an ambient system library.
Alternatively extract the pinned native JAR's library into that directory and
set `-Dbrotli4j.library.path` to its absolute path, retaining the binary hash.

## Targeted throughput and allocation

Run the primary include separately with `-t 1`, `-t 4` and `-t 8`. The example is
one independent fork; alternate Java/native method order across two repetitions
to produce two independent forks per implementation and case. The full matrix
is four methods x two sizes x two kinds x three thread counts = 48 cases.

```shell
java --enable-native-access=ALL-UNNAMED -cp 'target/classes:target/dependency/*' org.openjdk.jmh.Main 'io\.helidon\.extensions\.brotli\.benchmarks\.brotli4j\.BrotliComparisonBenchmark\.(javaEncode|nativeEncode|javaDecode|nativeDecodeDirect)$' -p size=SHORT,LONG -p kind=TEXT,RANDOM -t 1 -f 1 -wi 3 -i 4 -w 1s -r 1s -prof gc -foe true -jvmArgsAppend '--enable-native-access=ALL-UNNAMED -Xms1g -Xmx1g -XX:+UseG1GC -XX:ActiveProcessorCount=8' -rf json -rff comparison-1.json
```

For the additional valid streaming cells, use the exact method selector
`io\.helidon\.extensions\.brotli\.benchmarks\.brotli4j\.BrotliComparisonBenchmark\.nativeDecode$`
with `-p size=SHORT -p kind=TEXT,RANDOM`, then separately
`-p size=LONG -p kind=RANDOM`, at each thread count. Keep all other settings
identical. This adds nine cells and 18 independent forks. Do not include
`LONG/TEXT` or merge native direct and native streaming columns.

Use an explicitly recorded affinity of eight allowed, distinct physical cores
for all thread counts, avoiding SMT siblings and keeping both implementations
on the same CPUs. Check the live workload's allowed CPU set before choosing it;
do not infer eligibility from the machine-wide topology alone. Keep one host,
JDK, heap/GC, fixtures, settings and complete dependency set for the comparison.
Report aggregate operations/second and payload MiB/second, heap B/op, independent
fork ranges and scaling relative to one thread. Do not treat iteration samples
as additional independent forks or infer equivalence from overlapping intervals.

The GC profiler measures Java heap allocation. JNI buffers, C codec allocations,
native resident memory and library extraction are not included, and a 1 GiB
Java heap does not cap process/native memory. State those limits with any memory
comparison. JFR CPU/allocation stacks primarily describe Java and JNI boundaries;
they do not attribute C execution or native allocation precisely. Keep diagnostic
JFR timing separate from unprofiled scores. Select only relevant methods/fixtures
for profiles; never run the whole repository benchmark suite.

For static checks, enable the opt-in leaf explicitly with `-Pjmh-brotli4j` along
with the repository's `checkstyle`, `spotbugs` and `javadoc` profiles. Only the
generated JMH package is excluded from SpotBugs and Javadoc. Run the repository
copyright check after committing source. Cleanup should include `jmh-brotli4j`
alongside every other profile used by the checkout.
