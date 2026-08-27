# Helidon Chaos extension

The Helidon Chaos extension adds a bounded, process-local chaos run engine to Helidon WebServer. Operators create and stop runs through `/chaos/v1` on a dedicated control socket. Matching requests on explicitly selected application sockets can receive a synthetic HTTP error response.

This first slice targets Helidon 27 and is disabled by default. It does not use a Java agent, bytecode modification, a sidecar, or another chaos library.

## Production safety model

Use defense in depth:

1. Do not put the Chaos jar in the production runtime classpath.
2. Keep `chaos.enabled: false` as the configuration default.
3. Require a dedicated control socket that is separate from application ingress.
4. Require Helidon authentication and the `chaos-operator` role unless anonymous loopback mode is explicitly selected.
5. Apply server-enforced duration, activation, concurrency, body-size, and retention ceilings.
6. Restrict the control listener with workload identity, network policy, and test/pre-production deployment policy.

The strongest production control is physical absence: configuration cannot activate a class that is not in the running JVM. Production admission policy should reject the Chaos artifact, Chaos configuration, Chaos volumes, and Chaos init containers.

Never expose the control socket through normal application ingress. A distinct socket is necessary but is not, by itself, an authorization boundary.

## One application build, environment-specific runtime assembly

The application artifact and application container image can remain identical in test, pre-production, and production. A test/pre-production pod can use an init container to copy the extension and its runtime dependencies into an `emptyDir` shared with the application container. The main JVM starts only after init containers finish and includes that directory in its ordinary classpath.

```yaml
volumes:
  - name: helidon-chaos
    emptyDir: {}

initContainers:
  - name: install-helidon-chaos
    image: example/helidon-chaos-bundle:27
    command: ["/bin/sh", "-c", "cp /bundle/*.jar /chaos-libs/"]
    volumeMounts:
      - name: helidon-chaos
        mountPath: /chaos-libs

containers:
  - name: application
    image: example/orders-service:unchanged
    command: ["java", "-cp", "/app/libs/*:/chaos-libs/*", "com.example.orders.Main"]
    volumeMounts:
      - name: helidon-chaos
        mountPath: /chaos-libs
```

The production pod omits the init container, volume mount, Chaos configuration, and preferably the `/chaos-libs/*` classpath entry. A platform-controlled launcher can keep the application image unchanged while selecting the classpath at deployment time.

A sidecar is not equivalent. A sidecar starts as a different process and cannot add a jar to an already-running JVM's classpath. An init container works because it finishes populating the shared filesystem before the application JVM starts. No Java agent is involved.

With platform injection, service developers do not need a `pom.xml` change. They need only use Helidon WebServer and allow the platform launcher to extend the runtime classpath. Teams that choose direct application packaging can instead add the dependency below, but that puts the jar in every resulting deployment unless packaging excludes it.

## Maven coordinates

Import the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.chaos</groupId>
            <artifactId>helidon-extensions-chaos-bom</artifactId>
            <version>27.0.0-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add the extension when direct packaging is appropriate:

```xml
<dependency>
    <groupId>io.helidon.extensions.chaos</groupId>
    <artifactId>helidon-extensions-chaos</artifactId>
</dependency>
```

Helidon discovers the extension through `ServerFeatureProvider`; application code does not register `/chaos/v1` or install a filter.

## Configuration

### Disabled default

```yaml
server:
  features:
    chaos:
      enabled: false
```

When disabled, the extension does not inspect sockets, create an engine, register routes, or add filters.

### Explicit loopback-only development mode

Anonymous mode is intended only for a local developer machine or an isolated test pod whose control listener cannot be reached outside the network namespace.

```yaml
server:
  host: 127.0.0.1
  port: 8080
  sockets:
    chaos-control:
      host: 127.0.0.1
      port: 9080
      max-payload-size: 65536
  features:
    chaos:
      enabled: true
      control-socket: chaos-control
      application-sockets: ["@default"]
      security:
        allow-unauthenticated-loopback: true
```

Startup fails if anonymous mode resolves to a wildcard or non-loopback binding.

### Authenticated test or pre-production mode

The Chaos extension is authentication-provider-neutral. Configure the normal Helidon `SecurityFeature` with the environment's supported provider, and map authorized operators to `chaos-operator`.

```yaml
server:
  host: 0.0.0.0
  port: 8080
  sockets:
    chaos-control:
      host: 0.0.0.0
      port: 9080
      max-payload-size: 65536
  features:
    security:
      security:
        providers:
          - http-basic-auth:
              realm: chaos-test
              users:
                - login: operator
                  password: test-only-password
                  roles: ["chaos-operator"]
    chaos:
      enabled: true
      control-socket: chaos-control
      application-sockets: ["@default"]
      security:
        required-role: chaos-operator
        allow-unauthenticated-loopback: false
```

HTTP Basic authentication is shown only as a self-contained test example. Use the organization's workload identity, OIDC, mTLS, or other approved Helidon authentication provider in a shared environment. Do not put plaintext credentials in deployment configuration.

Enabled mode requires the control listener to have a finite `max-payload-size` no larger than `maximum-control-request-bytes`. Secured mode also fails during server construction unless Helidon Security is enabled and has an authentication provider. Missing sockets and control/application socket overlap fail before traffic is accepted.

## Control API

All resources are under the dedicated control listener:

| Method | Resource | Result |
|---|---|---|
| `POST` | `/chaos/v1/runs` | Validate and start one local run; returns `201` and `Location` |
| `GET` | `/chaos/v1/runs` | List retained runs, newest first |
| `GET` | `/chaos/v1/runs/{runId}` | Read normalized plan, lifecycle, actor, timestamps, and counters |
| `DELETE` | `/chaos/v1/runs/{runId}` | Stop a run; returns `200`, or `202` while in-flight work drains |

Validation uses strict JSON. Request-shape errors return `400`; policy and limit violations return `422`; active-run or scope conflicts return `409`. Errors use `application/problem+json` without stack traces or internal configuration data.

### Create a run

```json
{
  "name": "orders-unavailable",
  "maximumDuration": "PT30S",
  "seed": 148894,
  "stages": [
    {
      "name": "reject-orders",
      "duration": "PT10S",
      "disruptions": [
        {
          "name": "orders-503",
          "scope": {
            "type": "inbound-http",
            "methods": ["GET"],
            "path": {"match": "prefix", "value": "/orders"}
          },
          "activation": {"type": "always"},
          "effect": {
            "type": "synthetic-http-response",
            "status": 503,
            "headers": {"Retry-After": "1"},
            "mediaType": "application/problem+json",
            "body": "{\"title\":\"Synthetic service failure\",\"status\":503}"
          },
          "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
        }
      ]
    }
  ]
}
```

An exact path matches only that path. A prefix is segment-aware: `/orders` matches `/orders` and `/orders/42`, but not `/orders-old`. The filter does not invoke application code after it reserves a synthetic response. Non-matching or budget-skipped traffic continues normally.

Activation can also select a deterministic fraction of matching requests:

```json
{"type": "probability", "probability": 0.25}
```

`probability` must be between `2^-53` (approximately `1.1102230246251565e-16`) and one, matching the sampler's explicit 53-bit resolution. Values below that resolution, or values below one that would round to one, are rejected rather than silently changing their meaning. The extension derives an independent pseudo-random stream from the run `seed` and unambiguously encoded stage/disruption identity. The same normalized plan, seed, and matched-request order therefore produce the same decisions. Probability misses increment `skippedActivation` and do not consume cumulative or concurrency budget. The implementation uses extension-owned deterministic mixing rather than a JDK random-generator implementation.

```bash
curl --fail-with-body \
  --user operator:test-only-password \
  --header 'Content-Type: application/json' \
  --data @run.json \
  http://127.0.0.1:9080/chaos/v1/runs

curl --fail-with-body --user operator:test-only-password \
  http://127.0.0.1:9080/chaos/v1/runs

curl --fail-with-body --user operator:test-only-password \
  http://127.0.0.1:9080/chaos/v1/runs/7bb7b42d-5056-4b88-a734-e309909d1721

# DELETE /chaos/v1/runs/{runId}
curl --fail-with-body --request DELETE --user operator:test-only-password \
  http://127.0.0.1:9080/chaos/v1/runs/7bb7b42d-5056-4b88-a734-e309909d1721
```

## Server-enforced limits

| Configuration key under `server.features.chaos.limits` | Default |
|---|---:|
| `maximum-active-runs` | `1` |
| `maximum-run-duration` | `PT15M` |
| `maximum-activations-per-disruption` | `10000` |
| `maximum-concurrent-activations-per-disruption` | `64` |
| `maximum-synthetic-body-bytes` | `65536` |
| `maximum-control-request-bytes` | `65536` |
| `maximum-concurrent-control-requests` | `16` |
| `maximum-retained-runs` | `32` |
| `terminal-run-retention` | `PT15M` |

Request budgets may be lower than these ceilings but never higher. Concurrent and cumulative reservations are atomic,
and stopping a run prevents new reservations while in-flight work drains.

## Runtime model and first-slice boundary

Runs are local to one Helidon server process, in memory, bounded, and not reconstructed after restart. A caller must create a run on each selected instance. Restart is an unconditional cleanup boundary.

The current slice intentionally supports one stage, one inbound HTTP disruption, `always` or deterministic
`probability` activation, exact or segment-aware prefix paths, and a synthetic 4xx/5xx HTTP response. Its public vocabulary includes `runs`, `stages`,
`disruptions`, `scope`, `activation`, `effect`, and `budget` so later additions can introduce invocation cycles,
repetitions, and other bounded local effects without adopting another project's API.

Out of scope for this slice are timeout or connection-stall effects, bytecode injection, exception injection inside
arbitrary methods, outbound client failures, CPU or memory pressure, network faults outside the process, distributed
orchestration, persistent run recovery, and automatic production enablement.
