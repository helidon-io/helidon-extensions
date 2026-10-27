# Jackson integrations

Jackson 2 and Jackson 3 are independently versioned integrations for **Helidon 28**.
The grouping POM aggregates them; it is neither their parent nor a release unit.

| Integration | Documentation | Release ID | Provider ID |
| --- | --- | --- | --- |
| Jackson 2 | [Jackson 2](jackson-2/docs/README.md) | `jackson/jackson-2` | `jackson-2` |
| Jackson 3 | [Jackson 3](jackson-3/docs/README.md) | `jackson/jackson-3` | `jackson-3` |

Each integration includes an HTTP media module, a builder annotation-processor extension,
its own BOM, configuration reference, tests, and standalone examples. Their extension
versions are independent of the Helidon version. Development uses Helidon `28.0.0-SNAPSHOT`;
these extensions do not support Helidon 27 or older.

Discovery prefers Jackson 3, then Jackson 2, then the deprecated core `jackson` provider.
Use the distinct provider IDs for explicit selection. The two extensions do not register
the `jackson` alias. Jackson 2 generated annotations remain Jackson 2-specific; migrating
to Jackson 3 requires regenerating those models with the Jackson 3 processor.

The source baseline is Helidon core
[`ffa7ebba4a39edbb757a10b8e41962f8a606cce5`](https://github.com/helidon-io/helidon/commit/ffa7ebba4a39edbb757a10b8e41962f8a606cce5).
The copied source inventory consists of `http/media/jackson`, `builder/codegen-jackson`,
`http/tests/media/jackson`, and `builder/tests/jackson`. Jackson 2 preserves their behavior
while relocating the integration and its provider ID. Jackson 3 adapts the APIs and
defaults described in its migration documentation.

Build each release unit independently with JDK 27:

```shell
mvn -f extensions/jackson/jackson-2/pom.xml clean install -Ptests,examples
mvn -f extensions/jackson/jackson-3/pom.xml clean install -Ptests,examples
```

Run the additional provider coexistence and default-comparison suite with both units:

```shell
mvn -f extensions/jackson/pom.xml clean install -Ptests,coexistence
```

The normal extension discovery scripts find both release IDs from their `bom/pom.xml`
files. See the repository [release conventions](../../etc/scripts/RELEASE.md#directory-convention).
