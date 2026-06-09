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

package io.helidon.extensions.toml.parser;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TomlParserTest {
    @Test
    void testScalarTypes() {
        TomlTable table = TomlParser.create().parse("""
                title = "TOML example"
                enabled = true
                retries = 1_000
                hex = 0xFF
                octal = 0o755
                binary = 0b1010
                ratio = 6.25e2
                positive-infinity = +inf
                not-a-number = nan
                local-date = 1979-05-27
                local-time = 07:32
                local-date-time = 1979-05-27T07:32
                offset-date-time = 1979-05-27 07:32Z
                """);

        assertThat(scalar(table, "title", TomlString.class).value(), is("TOML example"));
        assertThat(scalar(table, "enabled", TomlBoolean.class).value(), is(true));
        assertThat(scalar(table, "retries", TomlInteger.class).value(), is(1000L));
        assertThat(scalar(table, "hex", TomlInteger.class).value(), is(255L));
        assertThat(scalar(table, "octal", TomlInteger.class).value(), is(493L));
        assertThat(scalar(table, "binary", TomlInteger.class).value(), is(10L));
        assertThat(scalar(table, "ratio", TomlFloat.class).value(), closeTo(625.0, 0.0));
        assertThat(scalar(table, "positive-infinity", TomlFloat.class).value(), is(Double.POSITIVE_INFINITY));
        assertThat(scalar(table, "not-a-number", TomlFloat.class).value(), is(Double.NaN));
        assertThat(scalar(table, "local-date", TomlLocalDate.class).value(), is(LocalDate.parse("1979-05-27")));
        assertThat(scalar(table, "local-time", TomlLocalTime.class).value(), is(LocalTime.parse("07:32:00")));
        assertThat(scalar(table, "local-date-time", TomlLocalDateTime.class).value(),
                   is(LocalDateTime.parse("1979-05-27T07:32:00")));
        assertThat(scalar(table, "offset-date-time", TomlOffsetDateTime.class).value(),
                   is(OffsetDateTime.parse("1979-05-27T07:32:00Z")));
    }

    @Test
    void testScalarText() {
        TomlTable table = TomlParser.create().parse("""
                title = "TOML example"
                enabled = true
                retries = 1_000
                ratio = 6.25e2
                positive-infinity = +inf
                not-a-number = -nan
                local-date = 1979-05-27
                local-time = 07:32
                local-date-time = 1979-05-27T07:32
                offset-date-time = 1979-05-27 07:32Z
                fractional-time = 07:32:00.5
                fractional-date-time = 1979-05-27T07:32:00.5
                fractional-offset-date-time = 1979-05-27 07:32:00.5Z
                """);

        assertText(scalar(table, "title", TomlString.class), "TOML example");
        assertText(scalar(table, "enabled", TomlBoolean.class), "true");
        assertText(scalar(table, "retries", TomlInteger.class), "1000");
        assertText(scalar(table, "ratio", TomlFloat.class), "625.0");
        assertText(scalar(table, "positive-infinity", TomlFloat.class), "inf");
        assertText(scalar(table, "not-a-number", TomlFloat.class), "nan");
        assertText(scalar(table, "local-date", TomlLocalDate.class), "1979-05-27");
        assertText(scalar(table, "local-time", TomlLocalTime.class), "07:32:00");
        assertText(scalar(table, "local-date-time", TomlLocalDateTime.class), "1979-05-27T07:32:00");
        assertText(scalar(table, "offset-date-time", TomlOffsetDateTime.class), "1979-05-27T07:32:00Z");
        assertText(scalar(table, "fractional-time", TomlLocalTime.class), "07:32:00.5");
        assertText(scalar(table, "fractional-date-time", TomlLocalDateTime.class), "1979-05-27T07:32:00.5");
        assertText(scalar(table, "fractional-offset-date-time", TomlOffsetDateTime.class),
                   "1979-05-27T07:32:00.5Z");
    }

    @Test
    void testStrings() {
        TomlTable table = TomlParser.create().parse("""
                basic = "Jos\\xE9\\e"
                literal = 'C:\\Users\\node'
                multiline-basic = \"""
                first \\

                  second
                \"""
                multiline-literal = '''
                first
                second
                '''
                """);

        assertThat(scalar(table, "basic", TomlString.class).value(), is("José\u001B"));
        assertThat(scalar(table, "literal", TomlString.class).value(), is("C:\\Users\\node"));
        assertThat(scalar(table, "multiline-basic", TomlString.class).value(), is("first second\n"));
        assertThat(scalar(table, "multiline-literal", TomlString.class).value(), is("first\nsecond\n"));
    }

    @Test
    void testTablesArraysAndDottedKeys() {
        TomlTable table = TomlParser.create().parse("""
                owner.name = "Tom"

                [database]
                ports = [8000, 8001, 8002]
                enabled = true

                [database.credentials]
                user = "db-user"

                [[services]]
                name = "api"
                ports = [8080, 8081]

                [[services]]
                name = "admin"
                """);

        assertThat(scalar(table(table, "owner"), "name", TomlString.class).value(), is("Tom"));
        assertThat(array(table(table, "database"), "ports").values(), hasSize(3));
        assertThat(scalar(table(table(table, "database"), "credentials"), "user", TomlString.class).value(),
                   is("db-user"));

        TomlArray services = array(table, "services");
        assertThat(services.values(), hasSize(2));
        assertThat(scalar((TomlTable) services.values().get(0), "name", TomlString.class).value(), is("api"));
        assertThat(scalar((TomlTable) services.values().get(1), "name", TomlString.class).value(), is("admin"));
    }

    @Test
    void testV11InlineTables() {
        TomlTable table = TomlParser.create().parse("""
                contact = {
                    personal = {
                        name = "Donald Duck",
                        email = "donald@duckburg.com",
                    },
                    work = {
                        name = "Coin cleaner",
                        email = "donald@ScroogeCorp.com",
                    },
                }
                """);

        TomlTable contact = table(table, "contact");
        assertThat(scalar(table(contact, "personal"), "name", TomlString.class).value(), is("Donald Duck"));
        assertThat(scalar(table(contact, "work"), "email", TomlString.class).value(), is("donald@ScroogeCorp.com"));
    }

    @Test
    void testInvalidDocuments() {
        assertThrows(TomlParseException.class, () -> TomlParser.create().parse("""
                name = "Tom"
                name = "Pradyun"
                """));
        assertThrows(TomlParseException.class, () -> TomlParser.create().parse("""
                fruit.apple.color = "red"
                [fruit.apple]
                texture = "smooth"
                """));
        assertThrows(TomlParseException.class, () -> TomlParser.create().parse("""
                [product]
                type = { name = "Nail" }
                type.edible = false
                """));
        assertThrows(TomlParseException.class, () -> TomlParser.create().parse("""
                fruits = []
                [[fruits]]
                name = "apple"
                """));
    }

    @Test
    void testParserFactory() {
        TomlParser parser = TomlParser.create();

        TomlTable table = parser.parse("name = \"Tom\"");

        assertThat(scalar(table, "name", TomlString.class).value(), is("Tom"));
        assertThat(parser.prototype().maxNestingDepth(), is(TomlParser.DEFAULT_MAX_NESTING_DEPTH));
    }

    @Test
    void testMaxNestingDepth() {
        TomlParser parser = TomlParser.create(builder -> builder.maxNestingDepth(2));

        assertThrows(TomlParseException.class, () -> parser.parse("value = [[[1]]]"));
        assertThrows(TomlParseException.class, () -> parser.parse("a.b.c.d = 1"));
    }

    @Test
    void testInvalidMaxNestingDepth() {
        assertThrows(IllegalArgumentException.class, () -> TomlParser.create(builder -> builder.maxNestingDepth(0)));
    }

    private static <T extends TomlScalar<?>> T scalar(TomlTable table, String key, Class<T> type) {
        TomlValue value = table.get(key).orElseThrow();
        assertThat(value, instanceOf(type));
        return type.cast(value);
    }

    private static void assertText(TomlScalar<?> scalar, String expected) {
        assertThat(scalar.text(), is(expected));
        assertThat(scalar.toString(), is(expected));
    }

    private static TomlTable table(TomlTable table, String key) {
        TomlValue value = table.get(key).orElseThrow();
        assertThat(value, instanceOf(TomlTable.class));
        return (TomlTable) value;
    }

    private static TomlArray array(TomlTable table, String key) {
        TomlValue value = table.get(key).orElseThrow();
        assertThat(value, instanceOf(TomlArray.class));
        return (TomlArray) value;
    }
}
