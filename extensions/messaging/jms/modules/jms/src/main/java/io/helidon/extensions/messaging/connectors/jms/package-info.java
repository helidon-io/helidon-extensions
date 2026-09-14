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
 * Jakarta Messaging (JMS) connector for declarative messaging.
 * <p>
 * Incoming delivery uses client acknowledgement or a local JMS transaction so transport settlement follows runtime
 * delivery completion. After a broker outage, connections are recreated while the messaging graph remains active and
 * only after the prior JMS resources close successfully.
 */
package io.helidon.extensions.messaging.connectors.jms;
