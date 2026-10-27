# Jakarta

Jakarta JSON integrations for Helidon 28, released as one independently versioned extension with individually selectable
modules.

- [Documentation and migration mappings](docs/README.md)
- [JSON-P HTTP media](modules/http-media-jsonp/)
- [JSON-B HTTP media](modules/http-media-jsonb/)
- [DBClient JSON-P mapping](modules/dbclient-jsonp/)
- [Runnable examples](examples/)
- [Independent consumer tests](tests/)

From this directory, build the release unit, its tests, examples, and configuration reference with:

```shell
mvn clean install -Ptests,examples,docs
```

The repository parents and Helidon 28 dependencies must be available first; see the
[development build instructions](docs/README.md#development-build).
