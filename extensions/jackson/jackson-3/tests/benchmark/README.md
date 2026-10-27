# Jackson media benchmarks

This opt-in test module compares the deprecated core Jackson provider, the
Jackson 2 extension, and the Jackson 3 extension. The original core integration
is a test-only comparator and is not a dependency of either extension runtime
or processor.

From the repository root, prepare the benchmark and both extensions:

```shell
mvn clean install -f extensions/jackson/pom.xml -Ptests,jmh -DskipTests
```

Run only the Jackson media benchmark:

```shell
mvn exec:exec -Ptests,jmh -pl :helidon-extensions-jackson-jackson-3-tests-benchmark \
    -Dexec.executable=java -Dexec.classpathScope=test \
    '-Dexec.args=-cp %classpath org.openjdk.jmh.Main io.helidon.extensions.jackson.benchmark.JacksonBenchmark -f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc'
```

Each trial selects one provider and a 128- or 4096-character JSON string payload.
Setup checks the matching reader/writer round trip before measurement. The timed
methods include entity reading or writing, stream/header allocation, and output
byte extraction; mapper construction and provider discovery are outside timing.
The benchmark does not measure network transport or every Jackson data shape.
