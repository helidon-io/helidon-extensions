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

public final class AiMoveProposal {
    @Description("Chosen legal move in UCI coordinate notation, for example e7e5")
    private String move = "";
    @Description("One short sentence summarizing why the move was chosen")
    private String summary = "";
    @Description("Top candidate continuation lines from the current position; the first line must start with the chosen move")
    private List<AiCandidateLine> candidateLines = new ArrayList<>();

    public AiMoveProposal() {
    }

    public String getMove() {
        return move;
    }

    public void setMove(String move) {
        this.move = move == null ? "" : move;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
    }

    public List<AiCandidateLine> getCandidateLines() {
        return candidateLines;
    }

    public void setCandidateLines(List<AiCandidateLine> candidateLines) {
        this.candidateLines = candidateLines == null ? new ArrayList<>() : new ArrayList<>(candidateLines);
    }
}
