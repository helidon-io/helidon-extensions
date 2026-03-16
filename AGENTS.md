# Repository Guidelines

## Project Structure & Module Organization
Helidon Extensions is a multi-module Maven repository rooted at `pom.xml`.
The root reactor currently includes `hashicorp-vault/` and `neo4j/` as top-level integration projects, plus shared support directories such as `etc/` and `licensing/`.
Top level directory `helidon/` contains extensions for [Helidon Core project](https://github.com/helidon-io/helidon).

Each integration project uses a similar layout:
- `bom/` for the BOM POM
- `docs/` for markdown documentation
- `modules/` for production modules
- `examples/` for optional example applications, typically enabled by a profile
- `tests/` for optional test modules, present today under `hashicorp-vault/`

## Build, Test, and Development Commands
- `mvn -DskipTests validate`: validate the default root reactor
- `mvn -pl hashicorp-vault -Ptests -DskipTests validate`: include HashiCorp Vault test modules in validation
- `mvn -pl neo4j -Pexamples -DskipTests validate`: include Neo4j examples in the build
- `mvn -pl hashicorp-vault/modules/vault -DskipTests test-compile`: compile one production module and its tests
- `mvn -pl hashicorp-vault/tests/vault-test test`: run the Vault test module
- `mvn -Pspotbugs spotbugs:check`: run SpotBugs using the repository profile
- `./etc/scripts/checkstyle.sh`: run aggregated Checkstyle

Use `-pl <module>` and `-am` to scope builds to the area you changed.

## Coding Style & Naming Conventions
Target Java 21. Use 4-space indentation, keep Maven versions inherited unless there is a clear repository pattern requiring otherwise, and follow existing package and artifact naming in the
surrounding module.
If `DEV-GUIDELINES.md` is intended to be authoritative, populate it and keep this section consistent with it.

## Testing Guidelines
The repository uses JUnit 5 and Hamcrest. Prefer `assertThat(...)` with Hamcrest matchers; use `assertThrows` and `assertAll` where they are the clearer choice.
Most current tests are named `*Test`.

## Commit & Pull Request Guidelines
Recent commits use short, imperative subjects such as `Update Helidon version to 4.3.4` or `Re-add neo4j with new structure`.
For pull requests, follow `CONTRIBUTING.md`: link an issue, describe the change, include validation steps, and update docs or examples when behavior changes.

## Security & Configuration Tips
Do not commit secrets, generated credentials, or local environment overrides. Shared style and analysis configuration lives in `etc/checkstyle.xml`, `etc/checkstyle-suppressions.xml`, and module-local `etc/spotbugs/exclude.xml`.
In case a password or a passphrase is needed for testing, use `changeit` as its value if possible.

## Files requiring approval

Never update these files without explicit approval:

- CHANGELOG.md
- LICENSE.txt
- NOTICE.txt
- SECURITY.md
