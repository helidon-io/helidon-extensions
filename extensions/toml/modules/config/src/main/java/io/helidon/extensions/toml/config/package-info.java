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

/**
 * TOML format ConfigParser implementation.
 * <p>
 * It supports {@code application/toml} media type and TOML v1.1.0 syntax.
 * <p>
 * The parser implementation supports {@link java.util.ServiceLoader}, i.e.
 * {@link io.helidon.config.Config.Builder} can automatically load and register TOML ConfigParser instance,
 * if not {@link io.helidon.config.Config.Builder#disableParserServices() disabled}.
 * It can also be {@link io.helidon.config.Config.Builder#addParser(io.helidon.config.spi.ConfigParser)
 * registered programmatically} using {@link io.helidon.extensions.toml.config.TomlConfigParser#create()}.
 *
 * @see io.helidon.config Configuration API
 * @see io.helidon.config.spi Configuration SPI
 */
package io.helidon.extensions.toml.config;
