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

package io.helidon.extensions.langchain4j.examples.agentic.chess.dto;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.model.output.structured.Description;

public final class AiCandidateLine {
    @Description("Short explanation of the candidate line")
    private String summary = "";
    @Description("Continuation moves in UCI coordinate notation from the current position")
    private List<String> moves = new ArrayList<>();

    public AiCandidateLine() {
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public List<String> getMoves() {
        return moves;
    }

    public void setMoves(List<String> moves) {
        this.moves = moves == null ? new ArrayList<>() : new ArrayList<>(moves);
    }
}
