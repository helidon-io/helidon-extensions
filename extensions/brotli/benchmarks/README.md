# Brotli JMH benchmarks

These opt-in benchmarks exercise public stream APIs and the in-memory HTTP
content-encoding wrappers. They are test tooling and are not deployed as an
extension artifact. JMH 1.37 is pinned in this isolated benchmark module.

Use JDK 27 for Maven and the benchmark JVM. After building the repository's
prerequisite artifacts, build only this module:

```shell
mvn clean install -Pjmh -pl :helidon-extensions-brotli-benchmarks -DskipTests
mvn dependency:copy-dependencies -Pjmh -pl :helidon-extensions-brotli-benchmarks -DincludeScope=runtime
```

Run an explicit method selection from the repository root, using JDK 27:

```shell
java -cp 'extensions/brotli/benchmarks/target/classes:extensions/brotli/benchmarks/target/dependency/*' org.openjdk.jmh.Main 'io\.helidon\.extensions\.brotli\.benchmarks\.BrotliBenchmark\.(encode|decode)$' -p shape=TINY,CACHE_TEXT,CACHE_RANDOM,LARGE_TEXT,INTEROP -prof gc
```

The classpath separator above is for Unix shells; use `;` on Windows.

| Operation | Included work |
| --- | --- |
| `encode`, `decode` | Fresh stream, whole entity, close, and returned byte array. |
| `decodeByteByByte` | Fresh stream and public single-byte cache path; returns a checksum. |
| `httpEncode`, `httpDecode` | Cached content-encoding strategy, fresh entity wrappers, HTTP encoder headers, whole entity. No sockets. |
| `inputWrapper` | Construct/close an unread wrapper. The decoder is lazy and is not initialized. |
| `firstRead` | Construct, initialize the decoder with the first read, and close early. |
| `outputWrapper` | Construct, flush, and close an empty encoder. |
| `dictionaryDecode` | Nine independent short entities with guaranteed RFC7932 static dictionary references. Results are normalized per entity with `OperationsPerInvocation(9)`. |

Payload shapes are a 7-byte entity, 64,001-byte repetitive/random entities that
cross the default encoder cache boundary, a 256 KiB repetitive entity, and an
external Google Brotli quality 11 interoperability entity. Trial setup checks
full byte equality for codec and HTTP paths, outside timed operations. The
dictionary fixtures were constructed from RFC 7932 Appendices A/B and independently
decoded with Google Brotli 1.2.1; they include identity, first/all-uppercase,
space-suffix and UTF-8 cases. No native dependency is used by the benchmarks.

Use independent forks and equal JVM/CPU settings for comparisons. Preserve
complete dependency/JAR inventories for equal SNAPSHOT versions. Compare
baseline and candidate in alternating order and report uncertainty rather than
assuming a small score difference is a regression or improvement.

`-prof gc` reports heap allocation per operation, not direct/native buffer
payloads. Use JFR for separate CPU/allocation-stack diagnostics, for example
`-prof 'jfr:dir=<output-directory>;configName=profile'` with an explicit method
selection. JFR allocation samples are approximate and profiling affects timing;
keep profiled scores separate from the unprofiled comparison.
