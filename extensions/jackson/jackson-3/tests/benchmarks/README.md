# Jackson media benchmarks

These opt-in benchmarks compare the deprecated core Jackson provider, the
Jackson 2 extension, and the Jackson 3 extension. They are test tooling under
`tests/benchmarks` and are not deployed as an extension artifact. As with the
Brotli benchmarks, enable both `tests` and `jmh` to include this module in the
reactor. JMH 1.37 is pinned in this module. The original core integration is
only a benchmark comparator; neither extension runtime nor processor depends
on it.

Use JDK 27 for Maven and the benchmark JVM. From the repository root, prepare
the benchmark and both extensions:

```shell
JACKSON_2_VERSION=$(awk -F '[<>]' '$2 == "jackson.jackson-2.extension.version" {print $3; exit}' \
    extensions/jackson/jackson-2/bom/pom.xml)
mvn clean install -f extensions/jackson/pom.xml -Ptests,jmh -DskipTests \
    "-Djackson.jackson-2.test.version=${JACKSON_2_VERSION}"
mvn dependency:copy-dependencies -Ptests,jmh -pl :helidon-extensions-jackson-jackson-3-benchmarks \
    -DincludeScope=runtime "-Djackson.jackson-2.test.version=${JACKSON_2_VERSION}"
```

This selects the current Jackson 2 release-unit version independently of Jackson 3.
To compare another installed or published Jackson 2 extension version, set `JACKSON_2_VERSION`
to that version instead. Run an explicit method selection from the repository
root, using JDK 27:

```shell
java -cp 'extensions/jackson/jackson-3/tests/benchmarks/target/classes:extensions/jackson/jackson-3/tests/benchmarks/target/dependency/*' org.openjdk.jmh.Main 'io\.helidon\.extensions\.jackson\.benchmarks\.JacksonBenchmark\.(writeInstance|writeServerInstance)$' -f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc
```

The classpath separator above is for Unix shells; use `;` on Windows.
Select `read` or `write` explicitly to measure the other paths.

| Operation | Included work |
| --- | --- |
| `read` | Fresh input stream and headers, complete entity deserialization. |
| `write` | Fresh output stream and headers, complete direct serialization, byte-array extraction. |
| `writeInstance` | Fresh client headers and per-entity instance writer, complete serialization, returned entity bytes. |
| `writeServerInstance` | Fresh response headers and per-entity instance writer, complete serialization, returned entity bytes. Existing request headers are prepared outside timing. |

Each trial selects one provider and a 128- or 4096-character JSON string payload.
Setup verifies the reader/writer round trip and complete byte equality for both
instance-writer overloads before measurement. It also verifies the providers'
in-memory instance-writer capability. Each timed instance-writer invocation
creates a fresh writer rather than reusing serialized bytes from setup. These
workloads exercise the paths normally selected by HTTP clients and servers;
mapper construction and provider selection are outside timing. They do not
measure network transport or every Jackson data shape.

Use independent forks and equal JVM/CPU settings for comparisons. Preserve
complete dependency/JAR inventories for equal SNAPSHOT versions and report
uncertainty rather than assuming a small score difference is a regression or
improvement. `-prof gc` reports heap allocation per operation, including entity
arrays and temporary serialization objects; it does not report native memory.
