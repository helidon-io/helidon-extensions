
# Helidon Extensions

These are extensions for the [Helidon Java microservices toolkit](https://github.com/helidon-io/helidon).

## Installation

These extensions are used by adding a dependency on the extension to your Helidon project.
Each extension is versioned independently. Please see the documentation for each extension.

## Documentation

See README for individual extensions.

* [neo4j](extensions/neo4j/docs/README.md)
* [HashiCorp Vault](extensions/hashicorp-vault/docs/README.md)
* [Eureka](extensions/eureka/docs/README.md)
* [Langchain4j](extensions/langchain4j/docs/README.md)
* [OpenAPI Generator](extensions/openapi-generator/docs/README.md)
* [OCI SDK v3](extensions/oci-v3/docs/README.md)
* [OpenAPI UI](extensions/openapi-ui/docs/README.md)
* [gson](extensions/gson/docs/README.md)
* [toml](extensions/toml/docs/README.md)
* [Chaos](extensions/chaos/docs/README.md)

## Examples

See examples in individual extensions.

## Help

* GitHub Discussions
* GitHub Issues

## Contributing

This project welcomes contributions from the community. Before submitting a pull request, please [review our contribution guide](./CONTRIBUTING.md)

When adding a new extension, the following things must be done:

1. Create the extension directory under [`extensions/`](extensions/), optionally inside a grouping directory such as `messaging/`. Follow the existing `bom/modules/tests/examples` structure and declare `helidon.version` in the extension root POM.
2. Add the extension module to [`extensions/pom.xml`](extensions/pom.xml), or to its grouping POM.
3. Validation and releases discover extensions by `bom/pom.xml`; see the [release directory convention](etc/scripts/RELEASE.md#directory-convention).

## Security

Please consult the [security guide](./SECURITY.md) for our responsible security vulnerability disclosure process

## License

Copyright (c) 2017, 2026 Oracle and/or its affiliates.
Licensed under the Apache License, Version 2.0
