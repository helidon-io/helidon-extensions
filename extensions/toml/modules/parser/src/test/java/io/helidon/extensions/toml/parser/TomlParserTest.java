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
        TomlTable table = TomlParser.parse("""
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

        assertThat(scalar(table, "title").value(), is("TOML example"));
        assertThat(scalar(table, "enabled").value(), is(true));
        assertThat(scalar(table, "retries").value(), is(1000L));
        assertThat(scalar(table, "hex").value(), is(255L));
        assertThat(scalar(table, "octal").value(), is(493L));
        assertThat(scalar(table, "binary").value(), is(10L));
        assertThat((double) scalar(table, "ratio").value(), closeTo(625.0, 0.0));
        assertThat(scalar(table, "positive-infinity").value(), is(Double.POSITIVE_INFINITY));
        assertThat((double) scalar(table, "not-a-number").value(), is(Double.NaN));
        assertThat(scalar(table, "local-date").value(), is(LocalDate.parse("1979-05-27")));
        assertThat(scalar(table, "local-time").value(), is(LocalTime.parse("07:32:00")));
        assertThat(scalar(table, "local-date-time").value(), is(LocalDateTime.parse("1979-05-27T07:32:00")));
        assertThat(scalar(table, "offset-date-time").value(), is(OffsetDateTime.parse("1979-05-27T07:32:00Z")));
    }

    @Test
    void testStrings() {
        TomlTable table = TomlParser.parse("""
                basic = "Jos\\xE9\\e"
                literal = 'C:\\Users\\node'
                multiline-basic = \"\"\"
                first \\

                  second
                \"\"\"
                multiline-literal = '''
                first
                second
                '''
                """);

        assertThat(scalar(table, "basic").value(), is("Jos\u00E9\u001B"));
        assertThat(scalar(table, "literal").value(), is("C:\\Users\\node"));
        assertThat(scalar(table, "multiline-basic").value(), is("first second\n"));
        assertThat(scalar(table, "multiline-literal").value(), is("first\nsecond\n"));
    }

    @Test
    void testTablesArraysAndDottedKeys() {
        TomlTable table = TomlParser.parse("""
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

        assertThat(scalar(table(table, "owner"), "name").value(), is("Tom"));
        assertThat(array(table(table, "database"), "ports").values(), hasSize(3));
        assertThat(scalar(table(table(table, "database"), "credentials"), "user").value(), is("db-user"));

        TomlArray services = array(table, "services");
        assertThat(services.values(), hasSize(2));
        assertThat(scalar((TomlTable) services.values().get(0), "name").value(), is("api"));
        assertThat(scalar((TomlTable) services.values().get(1), "name").value(), is("admin"));
    }

    @Test
    void testV11InlineTables() {
        TomlTable table = TomlParser.parse("""
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
        assertThat(scalar(table(contact, "personal"), "name").value(), is("Donald Duck"));
        assertThat(scalar(table(contact, "work"), "email").value(), is("donald@ScroogeCorp.com"));
    }

    @Test
    void testInvalidDocuments() {
        assertThrows(TomlParseException.class, () -> TomlParser.parse("""
                name = "Tom"
                name = "Pradyun"
                """));
        assertThrows(TomlParseException.class, () -> TomlParser.parse("""
                fruit.apple.color = "red"
                [fruit.apple]
                texture = "smooth"
                """));
        assertThrows(TomlParseException.class, () -> TomlParser.parse("""
                [product]
                type = { name = "Nail" }
                type.edible = false
                """));
        assertThrows(TomlParseException.class, () -> TomlParser.parse("""
                fruits = []
                [[fruits]]
                name = "apple"
                """));
    }

    private static TomlScalar scalar(TomlTable table, String key) {
        TomlValue value = table.get(key).orElseThrow();
        assertThat(value, instanceOf(TomlScalar.class));
        return (TomlScalar) value;
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
