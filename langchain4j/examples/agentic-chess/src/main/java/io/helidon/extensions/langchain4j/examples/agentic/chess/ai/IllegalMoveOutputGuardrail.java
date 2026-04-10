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

package io.helidon.extensions.langchain4j.examples.agentic.chess.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiCandidateLine;
import io.helidon.extensions.langchain4j.examples.agentic.chess.dto.AiMoveProposal;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessMove;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessPosition;
import io.helidon.extensions.langchain4j.examples.agentic.chess.engine.ChessRulesService;
import io.helidon.service.registry.Service;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;

@Service.Singleton
public final class IllegalMoveOutputGuardrail implements OutputGuardrail {
    private static final Jsonb JSONB = JsonbBuilder.create();

    private final ChessRulesService rules;

    public IllegalMoveOutputGuardrail(ChessRulesService rules) {
        this.rules = rules;
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        try {
            Map<String, Object> variables = request.requestParams().variables();
            ChessPosition position = ChessPosition.fromFen(String.valueOf(variables.get("fen")));
            int maxLines = numberValue(variables.get("candidateLineCount"), 3);
            int maxPlies = numberValue(variables.get("candidateLinePly"), 3);

            AiMoveProposal normalized = normalize(parseProposal(request.responseFromLLM().aiMessage().text()),
                                                  maxLines,
                                                  maxPlies);

            if (normalized.getMove() == null || normalized.getMove().isBlank()) {
                return invalidMove("Missing chosen move", position);
            }

            if (rules.findLegalMove(position, normalized.getMove()).isEmpty()) {
                return invalidMove("Chosen move is not legal: " + normalized.getMove(), position);
            }

            if (normalized.getCandidateLines().isEmpty()) {
                return invalidMove("At least one candidate line is required", position);
            }

            reorderLines(normalized);
            if (!lineStartsWith(normalized.getCandidateLines().getFirst(), normalized.getMove())) {
                return invalidMove("First candidate line must start with chosen move", position);
            }

            for (AiCandidateLine line : normalized.getCandidateLines()) {
                if (line.getMoves().isEmpty()) {
                    return invalidMove("Candidate line is empty", position);
                }
                ChessPosition cursor = position;
                for (String move : line.getMoves()) {
                    if (rules.findLegalMove(cursor, move).isEmpty()) {
                        return invalidMove("Illegal candidate-line move: " + move, position);
                    }
                    cursor = rules.applyMove(cursor, move);
                }
            }

            return successWith(JSONB.toJson(normalized), normalized);
        } catch (Exception e) {
            return reprompt("Model response was malformed", e,
                            """
                            Return the structured move proposal only.
                            Use legal UCI moves only.
                            """);
        }
    }

    private OutputGuardrailResult invalidMove(String message, ChessPosition position) {
        List<String> legalMoves = rules.legalMoves(position).stream()
                .map(ChessMove::uci)
                .sorted(Comparator.naturalOrder())
                .toList();
        return reprompt(message,
                        """
                        Return the structured move proposal only.
                        The chosen move and every candidate-line move must be legal from the provided position.
                        Legal moves are:
                        %s
                        """.formatted(String.join(", ", legalMoves)));
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return fallback;
    }

    private AiMoveProposal parseProposal(String rawText) {
        Objects.requireNonNull(rawText);
        // Guardrails receive raw model text, not a guaranteed JSON-only payload.
        String cleaned = cleanJson(rawText);
        AiMoveProposal proposal = JSONB.fromJson(cleaned, AiMoveProposal.class);
        if (proposal == null) {
            throw new IllegalArgumentException("Model response did not contain JSON");
        }
        return proposal;
    }

    private AiMoveProposal normalize(AiMoveProposal original, int maxLines, int maxPlies) {
        AiMoveProposal normalized = new AiMoveProposal();
        normalized.setMove(normalizeMove(original.getMove()));
        normalized.setSummary(trimmed(original.getSummary()));

        List<AiCandidateLine> lines = new ArrayList<>();
        for (AiCandidateLine originalLine : original.getCandidateLines()) {
            if (lines.size() >= maxLines) {
                break;
            }
            AiCandidateLine line = new AiCandidateLine();
            line.setSummary(trimmed(originalLine.getSummary()));
            List<String> moves = new ArrayList<>();
            for (String move : originalLine.getMoves()) {
                if (moves.size() >= maxPlies) {
                    break;
                }
                if (move != null && !move.isBlank()) {
                    moves.add(normalizeMove(move));
                }
            }
            line.setMoves(List.copyOf(moves));
            if (!line.getMoves().isEmpty()) {
                lines.add(line);
            }
        }
        normalized.setCandidateLines(lines);
        return normalized;
    }

    private void reorderLines(AiMoveProposal proposal) {
        int selectedIndex = -1;
        for (int index = 0; index < proposal.getCandidateLines().size(); index++) {
            if (lineStartsWith(proposal.getCandidateLines().get(index), proposal.getMove())) {
                selectedIndex = index;
                break;
            }
        }
        if (selectedIndex > 0) {
            AiCandidateLine selected = proposal.getCandidateLines().remove(selectedIndex);
            proposal.getCandidateLines().addFirst(selected);
        }
    }

    private boolean lineStartsWith(AiCandidateLine line, String move) {
        return !line.getMoves().isEmpty() && line.getMoves().getFirst().equals(move);
    }

    private String normalizeMove(String move) {
        return move == null ? "" : move.trim().toLowerCase(Locale.ROOT);
    }

    private String trimmed(String text) {
        return text == null ? "" : text.trim();
    }

    private String cleanJson(String rawText) {
        String trimmed = rawText.trim();
        // Models sometimes wrap the JSON in markdown fences or extra prose.
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closingFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && closingFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, closingFence).trim();
            }
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace >= firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        throw new IllegalArgumentException("Expected JSON object in model response");
    }
}
