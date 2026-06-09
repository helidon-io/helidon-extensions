/*
 * Copyright (c) 2026 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.extensions.toml.config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.config.ConfigValues;
import io.helidon.config.spi.ConfigNode;
import io.helidon.config.spi.ConfigNode.ListNode;
import io.helidon.config.spi.ConfigNode.ObjectNode;
import io.helidon.config.spi.ConfigParser;
import io.helidon.config.spi.ConfigParser.Content;
import io.helidon.config.spi.ConfigParserException;

import org.junit.jupiter.api.Test;

import static io.helidon.config.testing.ValueNodeMatcher.valueNode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TomlConfigParserTest {
    @Test
    void testEmpty() {
        ConfigParser parser = TomlConfigParser.create();
        ObjectNode node = parser.parse(toContent(""));

        assertThat(node.entrySet(), hasSize(0));
    }

    @Test
    void testSingleValue() {
        ConfigParser parser = TomlConfigParser.create();
        ObjectNode node = parser.parse(toContent("aaa = \"bbb\""));

        assertThat(node.entrySet(), hasSize(1));
        assertThat(node.get("aaa"), valueNode("bbb"));
    }

    @Test
    void testComplexValue() {
        ConfigParser parser = TomlConfigParser.create();
        ObjectNode node = parser.parse(toContent("""
                name = "Just for test"
                ports = [8080, 8081]
                flags = [true, false]
                inline = { enabled = true, ratio = 3.14159 }
                "literal.key" = "escaped"

                [server]
                host = "localhost"
                port = 8080

                [[services]]
                name = "first"
                tags = ["a", "b"]

                [[services]]
                name = "second"
                """));

        assertThat(node.entrySet(), hasSize(7));
        assertThat(node.get("name"), valueNode("Just for test"));
        assertThat(((ObjectNode) node.get("server")).get("host"), valueNode("localhost"));
        assertThat(((ObjectNode) node.get("server")).get("port"), valueNode("8080"));
        assertThat(((ObjectNode) node.get("inline")).get("enabled"), valueNode("true"));
        assertThat(((ObjectNode) node.get("inline")).get("ratio"), valueNode("3.14159"));
        assertThat(node.get("literal~1key"), valueNode("escaped"));

        List<ConfigNode> ports = ((ListNode) node.get("ports"));
        assertThat(ports, hasSize(2));
        assertThat(ports.get(0), valueNode("8080"));
        assertThat(ports.get(1), valueNode("8081"));

        List<ConfigNode> flags = ((ListNode) node.get("flags"));
        assertThat(flags, hasSize(2));
        assertThat(flags.get(0), valueNode("true"));
        assertThat(flags.get(1), valueNode("false"));

        List<ConfigNode> services = ((ListNode) node.get("services"));
        assertThat(services, hasSize(2));
        assertThat(((ObjectNode) services.get(0)).get("name"), valueNode("first"));
        assertThat(((ObjectNode) services.get(1)).get("name"), valueNode("second"));
    }

    @Test
    void testV11ScalarTypes() {
        Config config = toConfig("""
                title = "TOML example"
                escaped = "Jos\\xE9\\e"
                enabled = true
                retries = 1_000
                hex = 0xFF
                octal = 0o755
                binary = 0b1010
                ratio = 6.25e2
                local-date = 1979-05-27
                local-time = 07:32
                local-date-time = 1979-05-27T07:32
                offset-date-time = 1979-05-27 07:32Z
                """);

        assertThat(config.get("title").asString(), is(ConfigValues.simpleValue("TOML example")));
        assertThat(config.get("escaped").asString(), is(ConfigValues.simpleValue("Jos\u00E9\u001B")));
        assertThat(config.get("enabled").asBoolean(), is(ConfigValues.simpleValue(true)));
        assertThat(config.get("retries").asInt(), is(ConfigValues.simpleValue(1000)));
        assertThat(config.get("hex").asInt(), is(ConfigValues.simpleValue(255)));
        assertThat(config.get("octal").asInt(), is(ConfigValues.simpleValue(493)));
        assertThat(config.get("binary").asInt(), is(ConfigValues.simpleValue(10)));
        assertThat(config.get("ratio").asDouble(), is(ConfigValues.simpleValue(625.0)));
        assertThat(config.get("local-date").asString(), is(ConfigValues.simpleValue("1979-05-27")));
        assertThat(config.get("local-time").asString(), is(ConfigValues.simpleValue("07:32")));
        assertThat(config.get("local-date-time").asString(), is(ConfigValues.simpleValue("1979-05-27T07:32")));
        assertThat(config.get("offset-date-time").asString(), is(ConfigValues.simpleValue("1979-05-27T07:32Z")));
    }

    @Test
    void testDottedKeysTablesAndNestedLists() {
        Config config = toConfig("""
                owner.name = "Tom"

                [database]
                ports = [8000, 8001, 8002]
                enabled = true

                [database.credentials]
                user = "db-user"

                [database.settings]
                matrix = [[1, 2], [3, 4]]
                inline = { timeout = "PT5S", flags = [true, false] }
                """);

        assertThat(config.get("owner.name").asString(), is(ConfigValues.simpleValue("Tom")));
        assertThat(config.get("database.ports").asList(Integer.class),
                   is(ConfigValues.simpleValue(List.of(8000, 8001, 8002))));
        assertThat(config.get("database.enabled").asBoolean(), is(ConfigValues.simpleValue(true)));
        assertThat(config.get("database.credentials.user").asString(), is(ConfigValues.simpleValue("db-user")));
        assertThat(config.get("database.settings.matrix.1.0").asInt(), is(ConfigValues.simpleValue(3)));
        assertThat(config.get("database.settings.inline.timeout").asString(), is(ConfigValues.simpleValue("PT5S")));
        assertThat(config.get("database.settings.inline.flags").asList(Boolean.class),
                   is(ConfigValues.simpleValue(List.of(true, false))));
    }

    @Test
    void testInlineTablesAndArraysOfTables() {
        Config config = toConfig("""
                products = [
                  { name = "hammer", tags = ["tool", "steel"] },
                  { name = "nail", tags = ["fastener"] }
                ]

                [[services]]
                name = "api"
                enabled = true
                ports = [8080, 8081]
                [services.metadata]
                version = 1

                [[services]]
                name = "admin"
                enabled = false
                [services.metadata]
                version = 2
                """);

        assertThat(config.get("products").asNodeList().get(), hasSize(2));
        assertThat(config.get("products.0.name").asString(), is(ConfigValues.simpleValue("hammer")));
        assertThat(config.get("products.0.tags").asList(String.class),
                   is(ConfigValues.simpleValue(List.of("tool", "steel"))));
        assertThat(config.get("products.1.name").asString(), is(ConfigValues.simpleValue("nail")));

        assertThat(config.get("services").asNodeList().get(), hasSize(2));
        assertThat(config.get("services.0.name").asString(), is(ConfigValues.simpleValue("api")));
        assertThat(config.get("services.0.enabled").asBoolean(), is(ConfigValues.simpleValue(true)));
        assertThat(config.get("services.0.ports").asList(Integer.class),
                   is(ConfigValues.simpleValue(List.of(8080, 8081))));
        assertThat(config.get("services.0.metadata.version").asInt(), is(ConfigValues.simpleValue(1)));
        assertThat(config.get("services.1.name").asString(), is(ConfigValues.simpleValue("admin")));
        assertThat(config.get("services.1.enabled").asBoolean(), is(ConfigValues.simpleValue(false)));
        assertThat(config.get("services.1.metadata.version").asInt(), is(ConfigValues.simpleValue(2)));
    }

    @Test
    void testRelativeImportsAreNotResolved() {
        AtomicInteger resolverCalls = new AtomicInteger();
        ConfigParser parser = TomlConfigParser.create();
        ObjectNode node = parser.parse(toContent("""
                include = "extra.toml"
                imports = ["base.toml", "override.toml"]
                """),
                                       ignored -> {
                                           resolverCalls.incrementAndGet();
                                           return Optional.empty();
                                       });

        assertThat(resolverCalls.get(), is(0));
        assertThat(node.get("include"), valueNode("extra.toml"));

        List<ConfigNode> imports = ((ListNode) node.get("imports"));
        assertThat(imports, hasSize(2));
        assertThat(imports.get(0), valueNode("base.toml"));
        assertThat(imports.get(1), valueNode("override.toml"));
    }

    @Test
    void testConfigFromTomlServiceLoader() {
        Config config = Config.builder(ConfigSources.create("""
                greeting = "Hello"

                [app]
                name = "Demo"
                """, TomlConfigParser.APPLICATION_TOML))
                .disableEnvironmentVariablesSource()
                .disableSystemPropertiesSource()
                .disableMapperServices()
                .disableFilterServices()
                .build();

        assertThat(config.get("greeting").asString(), is(ConfigValues.simpleValue("Hello")));
        assertThat(config.get("app.name").asString(), is(ConfigValues.simpleValue("Demo")));
    }

    @Test
    void testParserErrors() {
        ConfigParser parser = TomlConfigParser.create();

        ConfigParserException exception = assertThrows(ConfigParserException.class,
                                                       () -> parser.parse(toContent("broken = [")));
        assertThat(exception.getMessage(), is(not("")));
    }

    @Test
    void testGetSupportedMediaTypes() {
        TomlConfigParser parser = TomlConfigParser.create();

        assertThat(parser.supportedMediaTypes(), is(not(empty())));
        assertThat(parser.supportedSuffixes(), contains("toml"));
        assertThat(parser.supportedMediaTypes(), contains(TomlConfigParser.APPLICATION_TOML));
    }

    private Config toConfig(String toml) {
        return Config.builder(ConfigSources.create(toml, TomlConfigParser.APPLICATION_TOML))
                .addParser(TomlConfigParser.create())
                .disableEnvironmentVariablesSource()
                .disableSystemPropertiesSource()
                .disableParserServices()
                .disableMapperServices()
                .disableFilterServices()
                .build();
    }

    private Content toContent(String toml) {
        return Content.builder()
                .data(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8)))
                .mediaType(TomlConfigParser.APPLICATION_TOML)
                .build();
    }
}
