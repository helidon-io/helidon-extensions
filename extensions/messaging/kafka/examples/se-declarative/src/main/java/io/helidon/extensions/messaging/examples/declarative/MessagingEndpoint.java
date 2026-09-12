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

package io.helidon.extensions.messaging.examples.declarative;

import java.util.concurrent.atomic.AtomicReference;

import io.helidon.common.Api;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.Http;
import io.helidon.http.Status;
import io.helidon.messaging.Emitter;
import io.helidon.messaging.Messaging;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

@SuppressWarnings(Api.SUPPRESS_PREVIEW)
@RestServer.Endpoint
@Http.Path("/")
@Service.Singleton
class MessagingEndpoint {
    private final AtomicReference<String> latestMessage = new AtomicReference<>("No messages received");
    private final Emitter<String> messages;

    @Service.Inject
    MessagingEndpoint(@Service.Named("messages-to-kafka") Emitter<String> messages) {
        this.messages = messages;
    }

    @Http.POST
    @Http.Path("/messages")
    @Http.Consumes(MediaTypes.TEXT_PLAIN_VALUE)
    @RestServer.Status(Status.NO_CONTENT_204_CODE)
    void send(@Http.Entity String message) {
        messages.emit(message);
    }

    @Messaging.ReceiveFrom("messages-from-kafka")
    void receive(String message) {
        latestMessage.set(message);
        System.out.println("Received message: " + message);
    }

    @Http.GET
    @Http.Path("/messages/latest")
    @Http.Produces(MediaTypes.TEXT_PLAIN_VALUE)
    String latestMessage() {
        return latestMessage.get();
    }
}
