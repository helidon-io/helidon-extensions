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

import java.io.InputStreamReader;
import java.util.List;
import java.util.Set;

import io.helidon.common.Api;
import io.helidon.common.Weight;
import io.helidon.common.Weighted;
import io.helidon.common.media.type.MediaType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.config.Config;
import io.helidon.config.ConfigException;
import io.helidon.config.spi.ConfigNode.ListNode;
import io.helidon.config.spi.ConfigNode.ObjectNode;
import io.helidon.config.spi.ConfigParser;
import io.helidon.config.spi.ConfigParserException;
import io.helidon.extensions.toml.parser.TomlArray;
import io.helidon.extensions.toml.parser.TomlParseException;
import io.helidon.extensions.toml.parser.TomlParser;
import io.helidon.extensions.toml.parser.TomlScalar;
import io.helidon.extensions.toml.parser.TomlTable;
import io.helidon.extensions.toml.parser.TomlValue;

/**
 * TOML {@link ConfigParser} implementation that supports {@code application/toml}.
 * <p>
 * The parser implementation supports {@link java.util.ServiceLoader}, i.e. {@link io.helidon.config.Config.Builder}
 * can automatically load and register {@code TomlConfigParser} instance,
 * if not {@link io.helidon.config.Config.Builder#disableParserServices() disabled}.
 * And of course it can be {@link io.helidon.config.Config.Builder#addParser(ConfigParser) registered programmatically}.
 *
 * @see io.helidon.config.Config.Builder#addParser(ConfigParser)
 * @see io.helidon.config.Config.Builder#disableParserServices()
 */
@Weight(TomlConfigParser.WEIGHT)
public class TomlConfigParser implements ConfigParser {
    /**
     * Priority of the parser used if registered by {@link io.helidon.config.Config.Builder} automatically.
     */
    public static final double WEIGHT = Weighted.DEFAULT_WEIGHT - 10;

    private static final Set<MediaType> SUPPORTED_MEDIA_TYPES = Set.of(MediaTypes.APPLICATION_TOML);
    private static final List<String> SUPPORTED_SUFFIXES = List.of("toml");

    /**
     * Default constructor needed by Java Service loader.
     */
    @Api.Internal
    public TomlConfigParser() {
    }

    /**
     * Create a new TOML Config Parser.
     *
     * @return a new instance of parser for TOML
     */
    public static TomlConfigParser create() {
        return new TomlConfigParser();
    }

    @Override
    public Set<MediaType> supportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES;
    }

    @Override
    public List<String> supportedSuffixes() {
        return SUPPORTED_SUFFIXES;
    }

    @Override
    public ObjectNode parse(Content content) throws ConfigParserException {
        try (InputStreamReader reader = new InputStreamReader(content.data(), content.charset())) {
            return fromTable(TomlParser.create().parse(reader));
        } catch (ConfigException e) {
            throw e;
        } catch (TomlParseException e) {
            throw new ConfigParserException("Cannot parse TOML: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ConfigParserException("Cannot read from source: " + e.getLocalizedMessage(), e);
        }
    }

    @Override
    public String toString() {
        return "TOML(" + MediaTypes.APPLICATION_TOML.text() + ")";
    }

    private static ObjectNode fromTable(TomlTable table) {
        ObjectNode.Builder builder = ObjectNode.builder();
        table.values().forEach((key, value) -> addValue(builder, Config.Key.escapeName(key), value));
        return builder.build();
    }

    private static ListNode fromArray(TomlArray array) {
        ListNode.Builder builder = ListNode.builder();
        for (TomlValue value : array.values()) {
            switch (value) {
            case TomlArray arrayValue -> builder.addList(fromArray(arrayValue));
            case TomlTable tableValue -> builder.addObject(fromTable(tableValue));
            case TomlScalar<?> scalar -> builder.addValue(scalar.text());
            case null, default -> throw new ConfigParserException("Unsupported TOML value: " + value);
            }
        }
        return builder.build();
    }

    private static void addValue(ObjectNode.Builder builder, String key, TomlValue value) {
        switch (value) {
        case TomlArray arrayValue -> builder.addList(key, fromArray(arrayValue));
        case TomlTable tableValue -> builder.addObject(key, fromTable(tableValue));
        case TomlScalar<?> scalar -> builder.addValue(key, scalar.text());
        case null, default -> throw new ConfigParserException("Unsupported TOML value: " + value);
        }
    }
}
