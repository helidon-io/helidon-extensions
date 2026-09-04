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

package io.helidon.extensions.langchain4j.tests.agentica2a;

import io.helidon.extensions.langchain4j.Ai;

import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.service.V;

/**
 * Workflow containing a plain LangChain4j A2A subagent without {@link Ai.Agent} metadata.
 */
@Ai.Agent("unannotated-remote-writer-workflow")
public interface UnannotatedRemoteWriterWorkflow {

    /**
     * Runs the remote writer workflow.
     *
     * @param topic topic to write about
     * @return response and agentic scope
     */
    @SequenceAgent(outputKey = "fallback-output", subAgents = UnannotatedRemoteWriterAgent.class)
    ResultWithAgenticScope<String> write(@V("topic") String topic);
}
