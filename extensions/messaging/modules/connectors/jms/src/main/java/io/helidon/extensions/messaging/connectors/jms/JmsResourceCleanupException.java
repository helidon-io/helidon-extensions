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

package io.helidon.extensions.messaging.connectors.jms;

import io.helidon.messaging.MessagingException;

/**
 * Failure to clean up a JMS connector resource.
 *
 * <p>This exception is distinct from other messaging failures because retrying after incomplete cleanup could leave
 * overlapping provider resources.</p>
 */
final class JmsResourceCleanupException extends MessagingException {
    JmsResourceCleanupException(String message, Throwable cause) {
        super(message, cause);
    }
}
