# Neo4j

## Contents

- [Overview](#overview)
- [Modules](#modules)
- [Maven Coordinates](#maven-coordinates)
- [Configuration](#configuration)
- [Minimal Usage](#minimal-usage)
- [Health Integration](#health-integration)
- [Examples](#examples)
- [Local Testing](#local-testing)
- [References](#references)

## Overview

The Neo4j extension integrates the Neo4j Java driver with Helidon SE.

The main module creates a configured `org.neo4j.driver.Driver` from the `neo4j` configuration subtree. In
service-registry based applications, that driver can then be injected into your own services.
A separate health module adds a Helidon health check backed by the same driver.

## Modules

| Artifact | Use it when | Notes |
| --- | --- | --- |
| `io.helidon.extensions.neo4j:helidon-extensions-neo4j-bom` | You want version alignment for the Neo4j extension artifacts. | Import it in `dependencyManagement`. |
| `io.helidon.extensions.neo4j:helidon-extensions-neo4j` | Your application needs a configured Neo4j `Driver`. | This is the main integration module. |
| `io.helidon.extensions.neo4j:helidon-extensions-neo4j-health` | Neo4j availability should participate in your health endpoint. | Adds `Neo4jHealthCheck`. |

## Maven Coordinates

If you use more than one Neo4j extension artifact, declare the version property in your POM and import the BOM:

```xml
<properties>
    <helidon.extensions.neo4j.version>27.0.0-SNAPSHOT</helidon.extensions.neo4j.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.neo4j</groupId>
            <artifactId>helidon-extensions-neo4j-bom</artifactId>
            <version>${helidon.extensions.neo4j.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add the main module and, if needed, the health module:

```xml
<dependencies>
    <dependency>
        <groupId>io.helidon.extensions.neo4j</groupId>
        <artifactId>helidon-extensions-neo4j</artifactId>
    </dependency>
    <dependency>
        <groupId>io.helidon.extensions.neo4j</groupId>
        <artifactId>helidon-extensions-neo4j-health</artifactId>
    </dependency>
</dependencies>
```

## Configuration

Minimal configuration looks like this:

```yaml
neo4j:
  encrypted: false
  uri: bolt://localhost:7687
  authentication:
    username: neo4j
    password: secret
```

The main settings are:

- `neo4j.uri`: required; connection URI for the Neo4j database.
- `neo4j.encrypted`: optional; defaults to `true`.
- `neo4j.authentication.enabled`: optional; defaults to `true`.
- `neo4j.authentication.username`: required when authentication is enabled.
- `neo4j.authentication.password`: required when authentication is enabled.
- `neo4j.pool.connection-acquisition-timeout`: optional; defaults to `PT1M`.
- `neo4j.pool.idle-time-before-connection-test`: optional; defaults to `PT-1S`.
- `neo4j.pool.max-connection-lifetime`: optional; defaults to `PT1H`.
- `neo4j.pool.max-connection-pool-size`: optional; defaults to `100`.
- `neo4j.pool.log-leaked-sessions`: optional.
- `neo4j.pool.metrics-enabled`: optional.
- `neo4j.trust.strategy`: optional; defaults to `TRUST_SYSTEM_CA_SIGNED_CERTIFICATES`.
- `neo4j.trust.hostname-verification`: optional; defaults to `true`.
- `neo4j.trust.certificate`: optional, but required if `neo4j.trust.strategy` is `TRUST_CUSTOM_CA_SIGNED_CERTIFICATES`.

Duration values use ISO-8601 syntax such as `PT1M` and `PT1H`.

## Minimal Usage

In an imperative SE application, create the driver from configuration and use it directly:

```java
Config config = Config.create();
Neo4j neo4j = Neo4j.create(config.get("neo4j"));
Driver driver = neo4j.driver();

try (var session = driver.session()) {
    var titles = session.executeRead(tx -> tx
            .run("MATCH (m:Movie) RETURN m.title AS title ORDER BY title")
            .list(record -> record.get("title").asString()));

    System.out.println(titles);
}
```

In service-registry based applications, `helidon-extensions-neo4j` also provides a `Driver` service when the
`neo4j` configuration subtree is present, so your own services can inject `Driver` directly:

```java
@Service.Singleton
class MovieRepository {
    private final Driver driver;

    @Service.Inject
    MovieRepository(Driver driver) {
        this.driver = driver;
    }
}
```

## Health Integration

If Neo4j availability should affect the application's health status, add
`io.helidon.extensions.neo4j:helidon-extensions-neo4j-health` and register the check with your health observer:

```java
Neo4jHealthCheck healthCheck = Neo4jHealthCheck.create(driver);

ObserveFeature observe = ObserveFeature.just(HealthObserver.builder()
        .addCheck(healthCheck)
        .build());
```

In the example applications, this check is exposed through `GET /observe/health`.

## Examples

Two SE examples are included:

- [`../examples/se-imperative/README.md`](../examples/se-imperative/README.md): explicit `Neo4j.create(...)`,
  explicit routing, and explicit `Neo4jHealthCheck` registration.
- [`../examples/se-declarative/README.md`](../examples/se-declarative/README.md): service-registry based wiring with
  injected `Driver`.

Both examples expose `GET /api/movies`, which queries the Neo4j Movies sample graph.

## Local Testing

The example tests use Neo4j harness, so automated test runs do not require an external Neo4j server. For manual
end-to-end testing:

1. Start a local Neo4j instance that listens on `bolt://localhost:7687` and accepts the `neo4j/secret` credentials.
   One option is:

   ```shell
   docker run --publish=7474:7474 --publish=7687:7687 -e 'NEO4J_AUTH=neo4j/secret' neo4j:<tag>
   ```

2. In the Neo4j browser, run `:play movies` to open the Movies guide, then execute the guide's sample-data Cypher
   statements to import the Movies graph.
3. Using JDK 26, build one of the example applications from the repository root, for example:

   ```shell
   mvn -Pexamples -pl extensions/neo4j/examples/se-imperative -am package
   ```

4. Run the packaged application:

   ```shell
   java -jar extensions/neo4j/examples/se-imperative/target/helidon-extensions-neo4j-examples-se-imperative.jar
   ```

5. Exercise the endpoints:

   ```shell
   curl http://localhost:8080/api/movies
   curl http://localhost:8080/observe/health
   ```

## References

- [Neo4j](https://neo4j.com/)
- [Neo4j Java Driver Manual](https://neo4j.com/docs/java-manual/current/)
