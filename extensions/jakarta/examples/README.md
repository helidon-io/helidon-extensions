# Jakarta examples

Each example is an independent Maven application using the Helidon 28 application parent and Jakarta BOM.

- [JSON-P imperative client and server](jsonp/README.md)
- [JSON-B imperative client and server](jsonb/README.md)
- [DBClient JSON-P mapping](dbclient-jsonp/README.md)
- [JSON-P declarative endpoint](jsonp-declarative/README.md)

Every example terminates after a finite demonstration and needs no database or container.
Use JDK 27 with Maven: set `JAVA_HOME` to that JDK and put its `bin` directory on `PATH`.
After following the [development build prerequisites](../docs/README.md#development-build), run
`mvn clean install -Ptests,examples` from `extensions/jakarta` to build these applications
and independently clone/build them using Maven Invoker. The standalone `verify` phase launches each
application using a real `java --module-path` command after its classpath behavior tests.
