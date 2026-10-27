# Jackson media benchmarks

This opt-in test module compares the deprecated core Jackson provider, the
Jackson 2 extension, and the Jackson 3 extension. The original core integration
is a test-only comparator and is not a dependency of either extension runtime
or processor.

From the repository root, prepare the benchmark and both extensions:

```shell
JACKSON_2_VERSION=$(awk -F '[<>]' '$2 == "jackson.jackson-2.extension.version" {print $3; exit}' \
    extensions/jackson/jackson-2/bom/pom.xml)
mvn clean install -f extensions/jackson/pom.xml -Ptests,jmh -DskipTests \
    "-Djackson.jackson-2.test.version=${JACKSON_2_VERSION}"
```

This selects the current Jackson 2 release-unit version independently of Jackson 3.
To compare another installed or published Jackson 2 extension version, set `JACKSON_2_VERSION`
to that version instead. Run only the Jackson media benchmark in the same shell:

```shell
mvn exec:exec -Ptests,jmh -pl :helidon-extensions-jackson-jackson-3-tests-benchmark \
    "-Djackson.jackson-2.test.version=${JACKSON_2_VERSION}" \
    -Dexec.executable=java -Dexec.classpathScope=test \
    '-Dexec.args=-cp %classpath org.openjdk.jmh.Main io.helidon.extensions.jackson.benchmark.JacksonBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc'
```

Each trial selects one provider and a 128- or 4096-character JSON string payload.
Setup checks the matching reader/writer round trip before measurement. The timed
methods include entity reading or writing, stream/header allocation, and output
byte extraction; mapper construction and provider discovery are outside timing.
The benchmark does not measure network transport or every Jackson data shape.
