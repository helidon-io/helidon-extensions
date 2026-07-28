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

package io.helidon.extensions.messaging.codegen;

import io.helidon.common.types.TypeName;
import io.helidon.common.types.TypeNames;

final class MessagingTypes {
    static final TypeName CONSUMER_REGISTRATION =
            TypeName.create("io.helidon.extensions.messaging.ConsumerRegistration");
    static final TypeName EMITTER = TypeName.create("io.helidon.extensions.messaging.Emitter");
    static final TypeName ENTITY = TypeName.create("io.helidon.extensions.messaging.Messaging.Entity");
    static final TypeName GENERIC_TYPE = TypeName.create("io.helidon.common.GenericType");
    static final TypeName HEADER_PARAM =
            TypeName.create("io.helidon.extensions.messaging.Messaging.HeaderParam");
    static final TypeName LIST = TypeNames.LIST;
    static final TypeName MESSAGE = TypeName.create("io.helidon.extensions.messaging.Message");
    static final TypeName MESSAGING_RUNTIME = TypeName.create("io.helidon.extensions.messaging.MessagingRuntime");
    static final TypeName ON_MESSAGE = TypeName.create("io.helidon.extensions.messaging.Messaging.OnMessage");

    private MessagingTypes() {
    }
}
