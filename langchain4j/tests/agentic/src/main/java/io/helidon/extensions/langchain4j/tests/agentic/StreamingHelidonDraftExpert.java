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

package io.helidon.extensions.langchain4j.tests.agentic;

import io.helidon.extensions.langchain4j.Ai;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

@Ai.Agent("streaming-helidon-draft-expert")
@Ai.StreamingChatModel("streaming-mock-model")
public interface StreamingHelidonDraftExpert {

    @UserMessage("""
            Create a short streaming Helidon draft for the request.
            Return only the draft and nothing else.
            The user request is {{request}}.
            """)
    @Agent(value = "A streaming Helidon draft expert", outputKey = "draft")
    TokenStream createDraft(@V("request") String request);
}
