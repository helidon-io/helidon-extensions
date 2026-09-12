<!--
Copyright (c) 2026 Oracle and/or its affiliates.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Pulsar messaging examples

Both examples send an HTTP request body through the `persistent://public/default/http-messages` Pulsar topic
and expose the same received message at `GET /messages/latest`:

- [Imperative](se-imperative/README.md): typed connector, channel configuration,
  and graph builders with HTTP service lifecycle callbacks.
- [Declarative](se-declarative/README.md): annotated HTTP and messaging methods,
  YAML configuration, and a generated service-registry binding.

Each example includes a local Pulsar Compose configuration and a real-broker
integration test. Follow its README to build, run, and exercise both directions.
The repository's `examples` Maven profile includes these modules. Run tests
serially because they start Pulsar containers.

The examples run on the classpath because the Pulsar client artifacts contain
split packages that cannot be placed together on the JPMS module path.
