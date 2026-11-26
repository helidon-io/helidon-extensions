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

package io.helidon.extensions.messaging.connectors.aq;

import java.util.Optional;

import io.helidon.builder.api.Option;
import io.helidon.builder.api.Prototype;
import io.helidon.common.Api;

/**
 * Direct Oracle AQ connection properties. Credentials belong to this connection selection and are not inherited separately.
 */
@Api.Incubating
@Prototype.Blueprint(decorator = AqConnectionSupport.BuilderDecorator.class, createEmptyPublic = false)
@Prototype.Sealed
@Prototype.Configured
interface AqConnectionConfigBlueprint {
    /**
     * Required nonblank Oracle JDBC connection URL.
     *
     * @return connection URL
     */
    @Option.Configured
    @Option.Confidential
    String url();

    /**
     * Database username. Configure username and password together, or omit both to use provider authentication.
     *
     * @return database username
     */
    @Option.Configured
    Optional<String> username();

    /**
     * Database password. Required when username is configured.
     *
     * @return database password
     */
    @Option.Configured
    @Option.Confidential
    Optional<String> password();
}
