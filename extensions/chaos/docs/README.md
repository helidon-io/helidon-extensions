# Helidon Chaos extension

The Helidon Chaos extension adds a bounded, process-local chaos run engine to Helidon WebServer. Operators create and stop runs through `/chaos/v1` on a dedicated control socket. Matching requests on explicitly selected application sockets can receive a synthetic HTTP error response or inbound latency, and matching Helidon WebClient calls can receive bounded outbound latency or a synthetic HTTP error response.

This first slice targets Helidon 4.5.3 and is disabled by default.

## Versioning

The Chaos extension deliberately declares its own `4.0.0-SNAPSHOT` project and BOM version so that it can track the
Helidon 4 compatibility line independently of the parent extensions repository version. This is a documented exception
to Helidon development guideline 4.2.2.3, which normally requires modules to inherit their version.

## Maven coordinates

Import the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.helidon.extensions.chaos</groupId>
            <artifactId>helidon-extensions-chaos-bom</artifactId>
            <version>4.0.0-SNAPSHOT</version>
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

Helidon discovers the server feature through `ServerFeatureProvider`; application code does not register `/chaos/v1` or install a filter. Separately, when the Chaos module and Helidon WebClient are present, WebClient service providers are discovered automatically by default, so application code does not add or register the `chaos` service. Disabling `services-discover-services` prevents automatic installation; such a client participates only when the `chaos` service is otherwise explicitly configured.

## Configuration

### Disabled default

```yaml
server:
  features:
    chaos:
      enabled: false
```

When disabled, the extension does not inspect sockets, create an engine, register routes, or add filters.

### Local-only mode

Anonymous mode is available only when the control listener is bound to loopback or to a Unix-domain socket.

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
        allow-unauthenticated-local: true
```

Startup fails if anonymous mode resolves to a wildcard, non-loopback, unresolved, or unsupported binding.
For a Unix-domain control socket, restrict the socket path and its parent directories to trusted principals because
filesystem permissions are the control API's access boundary.

### Authenticated mode

The Chaos extension is authentication-provider-neutral. Configure the normal Helidon `SecurityFeature` with an appropriate authentication provider, and map authorized operators to `chaos-operator`.

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
        allow-unauthenticated-local: false
```

HTTP Basic authentication is shown only as a self-contained test example. Use an appropriate Helidon authentication provider for your environment. Do not put plaintext credentials in configuration.

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

For bounded latency on an outbound Helidon WebClient call, create a run with an `outbound-http` scope:

```json
{
  "name": "inventory-latency",
  "maximumDuration": "PT30S",
  "seed": 148894,
  "stages": [
    {
      "name": "slow-inventory",
      "duration": "PT10S",
      "disruptions": [
        {
          "name": "inventory-latency",
          "scope": {
            "type": "outbound-http",
            "methods": ["GET"],
            "scheme": "https",
            "host": "inventory.example.com",
            "port": 443,
            "path": {"match": "prefix", "value": "/v1/items"}
          },
          "activation": {"type": "always"},
          "effect": {"type": "latency", "delay": "PT0.25S", "jitter": "PT0.05S"},
          "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
        }
      ]
    }
  ]
}
```

The same outbound scope can return a complete synthetic response without contacting the destination:

```json
{
  "name": "inventory-unavailable",
  "maximumDuration": "PT30S",
  "seed": 148894,
  "stages": [
    {
      "name": "reject-inventory",
      "duration": "PT10S",
      "disruptions": [
        {
          "name": "inventory-503",
          "scope": {
            "type": "outbound-http",
            "methods": ["GET"],
            "scheme": "https",
            "host": "inventory.example.com",
            "port": 443,
            "path": {"match": "prefix", "value": "/v1/items"}
          },
          "activation": {"type": "always"},
          "effect": {
            "type": "synthetic-http-response",
            "status": 503,
            "headers": {"Retry-After": "3"},
            "mediaType": "application/problem+json",
            "body": "{\"title\":\"Inventory unavailable\",\"status\":503}"
          },
          "budget": {"maximumActivations": 20, "maximumConcurrent": 2}
        }
      ]
    }
  ]
}
```

Stages execute in declared order. A stage may contain one disruption or may use an empty `disruptions` array as a
passive interval for observing recovery:

```json
{
  "name": "orders-degradation-sequence",
  "maximumDuration": "PT30S",
  "seed": 148894,
  "stages": [
    {
      "name": "slow-orders",
      "duration": "PT5S",
      "disruptions": [{
        "name": "orders-latency",
        "scope": {
          "type": "inbound-http",
          "methods": ["GET"],
          "path": {"match": "prefix", "value": "/orders"}
        },
        "activation": {"type": "always"},
        "effect": {"type": "latency", "delay": "PT0.25S"},
        "budget": {"maximumActivations": 100, "maximumConcurrent": 10}
      }]
    },
    {
      "name": "orders-outage",
      "duration": "PT10S",
      "disruptions": [{
        "name": "orders-503",
        "scope": {
          "type": "inbound-http",
          "methods": ["GET"],
          "path": {"match": "prefix", "value": "/orders"}
        },
        "activation": {"type": "always"},
        "effect": {"type": "synthetic-http-response", "status": 503},
        "budget": {"maximumActivations": 100, "maximumConcurrent": 10}
      }]
    },
    {
      "name": "recovery",
      "duration": "PT5S",
      "disruptions": []
    }
  ]
}
```

Stage intervals are contiguous and use `[startedAt, endsAt)` boundaries, so a request arriving exactly at a boundary
uses the next stage. Stage names must be unique, every duration must be positive, and the duration total must not exceed
`maximumDuration`. Activation sequences and budgets are independent per disruptive stage; response counters are run-wide
aggregates. Scopes from every disruptive stage are reserved against other nonterminal runs for the run's lifetime,
including while in-flight work drains. Passive stages reserve no scope.

While a run is running, its representation identifies the stage selected for the current time:

```json
"currentStage": {
  "index": 2,
  "name": "recovery",
  "startedAt": "2026-09-12T22:10:15Z",
  "endsAt": "2026-09-12T22:10:20Z"
}
```

`index` is zero-based and addresses the normalized `plan.stages` array. `currentStage` is omitted after the sequence ends
or while the run is stopping. A request already reserved by an earlier stage finishes normally after a transition; the
transition does not wait for in-flight work.

An exact path matches only that path. A prefix is segment-aware: `/orders` matches `/orders` and `/orders/42`, but not `/orders-old`. The filter does not invoke application code after it reserves a synthetic response. Non-matching or budget-skipped traffic continues normally.

Only Helidon WebClient-backed outbound calls participate. An outbound scope matches the logical method, scheme, host, effective logical port, and decoded path before discovery or transport; resolved endpoints and addresses are ignored. Methods are compared in uppercase, scheme and host in lowercase, and host, scheme, and port match exactly. Paths use the same exact and segment-aware prefix matching described above. Query parameters, fragments, and headers are ignored. Each redirect is evaluated independently for its logical attempt.

For a fixed delay, use the `latency` effect:

```json
{
  "type": "latency",
  "delay": "PT0.25S"
}
```

Add `jitter` for a uniformly selected delay below or above the base delay:

```json
{
  "type": "latency",
  "delay": "PT0.25S",
  "jitter": "PT0.05S"
}
```

The second example delays a selected request by 200 through 300 milliseconds. `delay` must be positive. `jitter`
defaults to zero, must not be negative, and must not exceed `delay`. The worst-case value of `delay + jitter` must not
exceed the server's `maximum-latency` limit. The run seed and matching invocation number determine the selected delay;
effect sampling uses a separate deterministic stream from activation sampling. For inbound traffic, a latency reservation
is released before the application handler runs, so application processing time does not consume chaos concurrency
budget. For outbound traffic, the delay occurs in the application WebClient service layer before network I/O, so it is
visible to wrappers around the whole invocation and can fall outside WebClient transport connect and read timeout clocks;
it is not a transport fault. Its reservation closes before the downstream chain and network call, so
`maximumConcurrent` bounds simultaneous artificial delays, not calls to the dependency. If an artificial delay is
interrupted, the thread interrupt status is restored and the request proceeds.

To select one of several effects for each accepted activation, use `weighted-choice`:

```json
{
  "type": "weighted-choice",
  "outcomes": [
    {
      "weight": 60,
      "effect": {"type": "synthetic-http-response", "status": 503}
    },
    {
      "weight": 25,
      "effect": {"type": "synthetic-http-response", "status": 429}
    },
    {
      "weight": 15,
      "effect": {"type": "latency", "delay": "PT0.5S", "jitter": "PT0.1S"}
    }
  ]
}
```

Weights are positive integers and do not need to total 100. At least one outcome is required, and the total weight must
not exceed `Long.MAX_VALUE`. Inbound and outbound scopes accept `synthetic-http-response`, `latency`, and weighted choices
with valid leaf effects; nested weighted choices are rejected. Selection is deterministic for the run seed and matching
invocation number, uses a separate random stream from activation and latency jitter, and preserves declared outcome
order in normalized responses. The disruption's cumulative and concurrent budgets apply across all selected outcomes.

Activation can also select a deterministic fraction of matching requests:

```json
{"type": "probability", "probability": 0.25}
```

`probability` must be between `2^-53` (approximately `1.1102230246251565e-16`) and one, matching the sampler's explicit 53-bit resolution. Values below that resolution, or values below one that would round to one, are rejected rather than silently changing their meaning. The extension derives an independent pseudo-random stream from the run `seed` and unambiguously encoded stage/disruption identity. The same normalized plan, seed, and matched-request order therefore produce the same decisions. Probability misses increment `skippedActivation` and do not consume cumulative or concurrency budget. The implementation uses extension-owned deterministic mixing rather than a JDK random-generator implementation.

For a repeating count-based burst, use `periodic-burst` activation:

```json
{
  "type": "periodic-burst",
  "initialSkip": 20,
  "cycleSize": 10,
  "burstSize": 3
}
```

Only requests matching the disruption scope advance the activation count. `initialSkip` is optional and defaults to zero.
After those initial requests, the first `burstSize` requests in every `cycleSize` requests activate the disruption. The
example therefore does not activate chaos for requests 1 through 20, activates requests 21 through 23, does not activate
chaos for requests 24 through 30, and repeats that ten-request cycle. `cycleSize` and `burstSize` must be positive, and
`burstSize` must not exceed `cycleSize`. The run seed does not affect this deterministic schedule. Periodic-burst
misses increment `skippedActivation` and do not consume cumulative or concurrency budget.

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
| `maximum-stages-per-run` | `16` |
| `maximum-activations-per-disruption` | `10000` |
| `maximum-concurrent-activations-per-disruption` | `64` |
| `maximum-latency` | `PT30S` |
| `maximum-synthetic-body-bytes` | `65536` |
| `maximum-control-request-bytes` | `65536` |
| `maximum-concurrent-control-requests` | `16` |
| `maximum-retained-runs` | `32` |
| `terminal-run-retention` | `PT15M` |

Request budgets may be lower than these ceilings but never higher. Concurrent and cumulative reservations are atomic,
and stopping a run prevents new reservations while in-flight work drains. `maximum-latency` applies to both inbound and
outbound latency effects. `maximum-synthetic-body-bytes` applies to both inbound and outbound synthetic responses.

## Runtime model and first-slice boundary

Runs are local to one Helidon server process, in memory, bounded, and not reconstructed after restart. A caller must create a run on each selected instance. Restart is an unconditional cleanup boundary.

The current slice intentionally supports bounded ordered stages with zero or one HTTP disruption per stage, inbound or
outbound as implemented; `always`, deterministic `probability`, or `periodic-burst` activation; exact or segment-aware
prefix paths; inbound synthetic 4xx/5xx HTTP responses and latency; outbound Helidon WebClient synthetic 4xx/5xx HTTP
responses and latency; and deterministic weighted selection between valid effects. Its public vocabulary includes
`runs`, `stages`, `disruptions`, `scope`,
`activation`, `effect`, and `budget` so later additions can introduce other bounded local effects without adopting
another project's API.

Out of scope for this slice are non-Helidon clients, connection resets and other transport faults, timeout or
connection-stall effects, bytecode injection, exception injection inside arbitrary methods, CPU or memory pressure,
network faults outside the process, distributed orchestration, persistent run recovery, and automatic enablement.
