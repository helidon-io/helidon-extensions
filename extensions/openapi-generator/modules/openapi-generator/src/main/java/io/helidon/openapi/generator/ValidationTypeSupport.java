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

package io.helidon.openapi.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Parses mapped Java types for supported cascading validation boundaries.
 */
final class ValidationTypeSupport {
    private static final Set<String> SUPPORTED_CONTAINERS = Set.of("Optional", "List", "Set", "Collection");

    private ValidationTypeSupport() {
    }

    static Set<String> referencedModels(String javaType, Set<String> modelNames) {
        Set<String> references = new LinkedHashSet<>();
        parsed(javaType).ifPresent(type -> collectReferences(type, modelNames, references));
        return references;
    }

    static boolean isJavaArray(String javaType) {
        return parsed(javaType).map(type -> type.arrayDimensions > 0).orElse(false);
    }

    static Optional<UnsupportedContainer> unsupportedContainer(String javaType,
                                                               Set<String> modelNames,
                                                               Set<String> participating) {
        return parsed(javaType)
                .map(type -> unsupportedContainer(type, modelNames, participating))
                .filter(type -> type != null)
                .map(type -> new UnsupportedContainer(type.rawType,
                                                       participatingReferences(type, modelNames, participating)));
    }

    static boolean isDirectParticipatingModel(String javaType,
                                              Set<String> modelNames,
                                              Set<String> participating) {
        return parsed(javaType)
                .map(type -> type.arrayDimensions == 0
                        && modelNames.contains(type.simpleName())
                        && participating.contains(type.simpleName()))
                .orElse(false);
    }

    static String annotatedType(String javaType,
                                Set<String> modelNames,
                                Set<String> participating,
                                String modelPackage) {
        Optional<ParsedJavaType> parsed = parsed(javaType);
        if (parsed.isEmpty()) {
            return javaType;
        }
        Map<Integer, String> insertions = new TreeMap<>();
        collectInsertions(parsed.get(), modelNames, participating, modelPackage, insertions);
        if (insertions.isEmpty()) {
            return javaType;
        }
        StringBuilder result = new StringBuilder(javaType);
        List<Map.Entry<Integer, String>> entries = new ArrayList<>(insertions.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<Integer, String> insertion = entries.get(i);
            result.insert(insertion.getKey(), insertion.getValue());
        }
        return result.toString();
    }

    private static Set<String> participatingReferences(ParsedJavaType type,
                                                       Set<String> modelNames,
                                                       Set<String> participating) {
        Set<String> nestedModels = new TreeSet<>();
        collectReferences(type, modelNames, nestedModels);
        nestedModels.retainAll(participating);
        return Collections.unmodifiableSet(nestedModels);
    }

    private static void collectReferences(ParsedJavaType type,
                                          Set<String> modelNames,
                                          Set<String> references) {
        if (modelNames.contains(type.simpleName())) {
            references.add(type.simpleName());
            return;
        }
        if ("Map".equals(type.simpleName())) {
            if (type.typeArguments.size() > 1) {
                collectReferences(type.typeArguments.get(1), modelNames, references);
            }
            return;
        }
        for (ParsedJavaType argument : type.typeArguments) {
            collectReferences(argument, modelNames, references);
        }
    }

    private static ParsedJavaType unsupportedContainer(ParsedJavaType type,
                                                       Set<String> modelNames,
                                                       Set<String> participating) {
        if (modelNames.contains(type.simpleName())) {
            return null;
        }
        if (type.arrayDimensions > 0) {
            return unsupportedContainer(type.withoutArray(), modelNames, participating);
        }
        String simpleName = type.simpleName();
        if ("Map".equals(simpleName)) {
            return type.typeArguments.size() > 1
                    ? unsupportedContainer(type.typeArguments.get(1), modelNames, participating)
                    : null;
        }
        if (SUPPORTED_CONTAINERS.contains(simpleName)) {
            return type.typeArguments.isEmpty()
                    ? null
                    : unsupportedContainer(type.typeArguments.get(0), modelNames, participating);
        }
        Set<String> references = new LinkedHashSet<>();
        collectReferences(type, modelNames, references);
        return references.stream().anyMatch(participating::contains) ? type : null;
    }

    private static void collectInsertions(ParsedJavaType type,
                                          Set<String> modelNames,
                                          Set<String> participating,
                                          String modelPackage,
                                          Map<Integer, String> insertions) {
        if (modelNames.contains(type.simpleName())) {
            if (participating.contains(type.simpleName())) {
                if (type.arrayDimensions > 0) {
                    int qualifier = type.rawType.lastIndexOf('.');
                    if (qualifier >= 0) {
                        insertions.put(type.rawStart + qualifier + 1, "@Validation.Valid ");
                    } else {
                        insertions.put(type.rawStart, modelPackage + ".@Validation.Valid ");
                    }
                } else {
                    insertions.put(type.rawStart, "@Validation.Valid ");
                }
            }
            return;
        }
        if ("Map".equals(type.simpleName())) {
            if (type.typeArguments.size() > 1) {
                collectInsertions(type.typeArguments.get(1), modelNames, participating, modelPackage, insertions);
            }
            return;
        }
        if (type.arrayDimensions > 0 || SUPPORTED_CONTAINERS.contains(type.simpleName())) {
            for (ParsedJavaType argument : type.typeArguments) {
                collectInsertions(argument, modelNames, participating, modelPackage, insertions);
            }
        }
    }

    private static Optional<ParsedJavaType> parsed(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new JavaTypeParser(javaType).parse());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot analyze mapped Java type '" + javaType
                                                       + "' for cascading validation: " + e.getMessage(), e);
        }
    }

    record UnsupportedContainer(String rawType, Set<String> participatingModels) {
    }

    private record ParsedJavaType(String rawType,
                                  List<ParsedJavaType> typeArguments,
                                  int arrayDimensions,
                                  int rawStart,
                                  int rawEnd) {
        String simpleName() {
            int separator = Math.max(rawType.lastIndexOf('.'), rawType.lastIndexOf('$'));
            return separator < 0 ? rawType : rawType.substring(separator + 1);
        }

        ParsedJavaType withoutArray() {
            return new ParsedJavaType(rawType, typeArguments, 0, rawStart, rawEnd);
        }
    }

    private static final class JavaTypeParser {
        private final String source;
        private int index;

        private JavaTypeParser(String source) {
            this.source = source;
        }

        private ParsedJavaType parse() {
            ParsedJavaType result = parseType();
            skipWhitespace();
            if (index != source.length()) {
                throw new IllegalArgumentException("Unexpected Java type suffix: " + source.substring(index));
            }
            return result;
        }

        private ParsedJavaType parseType() {
            skipWhitespace();
            if (peek('?')) {
                index++;
                skipWhitespace();
                if (consumeWord("extends") || consumeWord("super")) {
                    return parseType();
                }
                return new ParsedJavaType("Object", List.of(), 0, index - 1, index);
            }

            int rawStart = index;
            while (index < source.length()) {
                char ch = source.charAt(index);
                if (Character.isJavaIdentifierPart(ch) || ch == '.' || ch == '$') {
                    index++;
                } else {
                    break;
                }
            }
            if (rawStart == index) {
                throw new IllegalArgumentException("Expected Java type at offset " + index);
            }
            String rawType = source.substring(rawStart, index);
            int rawEnd = index;
            List<ParsedJavaType> arguments = new ArrayList<>();
            skipWhitespace();
            if (peek('<')) {
                index++;
                do {
                    arguments.add(parseType());
                    skipWhitespace();
                    if (peek(',')) {
                        index++;
                    } else {
                        break;
                    }
                } while (true);
                skipWhitespace();
                expect('>');
            }
            int dimensions = 0;
            skipWhitespace();
            while (peek('[')) {
                index++;
                expect(']');
                dimensions++;
                skipWhitespace();
            }
            return new ParsedJavaType(rawType, List.copyOf(arguments), dimensions, rawStart, rawEnd);
        }

        private boolean consumeWord(String word) {
            if (source.regionMatches(index, word, 0, word.length())
                    && (index + word.length() == source.length()
                    || !Character.isJavaIdentifierPart(source.charAt(index + word.length())))) {
                index += word.length();
                skipWhitespace();
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw new IllegalArgumentException("Expected '" + expected + "' at offset " + index);
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < source.length() && source.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }
    }
}
