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

package io.helidon.extensions.toml;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import io.helidon.builder.api.RuntimeType;
import io.helidon.common.Api;

/**
 * TOML parser and document model entry point.
 */
@Api.Incubating
public final class TomlParser implements RuntimeType.Api<TomlParserConfig> {
    static final int DEFAULT_MAX_NESTING_DEPTH = 64;

    private static final Pattern LOCAL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DECIMAL_INTEGER = Pattern.compile("[+-]?(0|[1-9](?:_?[0-9])*)");
    private static final Pattern HEX_INTEGER = Pattern.compile("0[xX][0-9A-Fa-f](?:_?[0-9A-Fa-f])*");
    private static final Pattern OCTAL_INTEGER = Pattern.compile("0[oO][0-7](?:_?[0-7])*");
    private static final Pattern BINARY_INTEGER = Pattern.compile("0[bB][01](?:_?[01])*");
    private static final Pattern SPECIAL_FLOAT = Pattern.compile("[+-]?(inf|nan)");
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final TomlParserConfig config;

    private TomlParser(TomlParserConfig config) {
        this.config = Objects.requireNonNull(config);
        if (config.maxNestingDepth() < 1) {
            throw new IllegalArgumentException("Maximum TOML nesting depth must be at least 1");
        }
        Objects.requireNonNull(config.versionBehavior());
    }

    /**
     * Create a TOML parser and document model with default configuration.
     *
     * @return a new TOML parser and document model
     */
    public static TomlParser create() {
        return builder().build();
    }

    /**
     * Create a TOML parser and document model from configuration.
     *
     * @param config parser configuration
     * @return a new TOML parser and document model
     */
    public static TomlParser create(TomlParserConfig config) {
        return new TomlParser(config);
    }

    /**
     * Create a TOML parser and document model customizing its configuration.
     *
     * @param consumer configuration consumer
     * @return a new TOML parser and document model
     */
    public static TomlParser create(Consumer<TomlParserConfig.Builder> consumer) {
        return builder().update(Objects.requireNonNull(consumer)).build();
    }

    /**
     * Create a fluent API builder to configure a TOML parser and document model.
     *
     * @return a new builder
     */
    public static TomlParserConfig.Builder builder() {
        return TomlParserConfig.builder();
    }

    @Override
    public TomlParserConfig prototype() {
        return config;
    }

    /**
     * Parse TOML from a string.
     *
     * @param text TOML text
     * @return parsed table
     */
    public TomlTable parse(String text) {
        return new Parser(Objects.requireNonNull(text), config).parse();
    }

    /**
     * Parse TOML from a reader.
     *
     * @param reader reader
     * @return parsed table
     * @throws IOException if the reader cannot be read
     */
    public TomlTable parse(Reader reader) throws IOException {
        Objects.requireNonNull(reader);
        char[] buffer = new char[4096];
        StringBuilder text = new StringBuilder();
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            text.append(buffer, 0, read);
        }
        return parse(text.toString());
    }

    private static Long parseBaseInteger(String token, int radix) {
        BigInteger value = new BigInteger(token.substring(2).replace("_", ""), radix);
        if (value.compareTo(LONG_MAX) > 0) {
            throw new TomlParseException("Integer out of 64-bit signed range: " + token);
        }
        return value.longValue();
    }

    private static TomlScalar<?> parseDateTime(String token, TomlVersionBehavior versionBehavior) {
        if (token.length() < 16 || !isDate(token) || (token.charAt(10) != 'T' && token.charAt(10) != ' ')) {
            return null;
        }

        String date = token.substring(0, 10);
        String rest = token.substring(11);
        int offsetIndex = offsetIndex(rest);
        if (offsetIndex >= 0) {
            String time = timeText(rest.substring(0, offsetIndex), versionBehavior);
            if (time == null) {
                return null;
            }
            String offset = rest.substring(offsetIndex);
            if (!isOffset(offset)) {
                return null;
            }
            return TomlOffsetDateTime.create(OffsetDateTime.parse(date + "T" + time + offset));
        }

        String time = timeText(rest, versionBehavior);
        return time == null ? null : TomlLocalDateTime.create(LocalDateTime.parse(date + "T" + time));
    }

    private static Long parseDecimalInteger(String token) {
        try {
            return Long.parseLong(token.replace("_", ""));
        } catch (NumberFormatException e) {
            throw new TomlParseException("Integer out of 64-bit signed range: " + token, e);
        }
    }

    private static Double specialFloat(String token) {
        return switch (token) {
        case "inf", "+inf" -> Double.POSITIVE_INFINITY;
        case "-inf" -> Double.NEGATIVE_INFINITY;
        case "nan", "+nan", "-nan" -> Double.NaN;
        default -> throw new TomlParseException("Invalid float: " + token);
        };
    }

    private static boolean isDate(String token) {
        return token.length() >= 10
                && isDigit(token.charAt(0))
                && isDigit(token.charAt(1))
                && isDigit(token.charAt(2))
                && isDigit(token.charAt(3))
                && token.charAt(4) == '-'
                && isDigit(token.charAt(5))
                && isDigit(token.charAt(6))
                && token.charAt(7) == '-'
                && isDigit(token.charAt(8))
                && isDigit(token.charAt(9));
    }

    private static String timeText(String token, TomlVersionBehavior versionBehavior) {
        if (token.length() < 5 || token.charAt(2) != ':' || !isDigit(token.charAt(0)) || !isDigit(token.charAt(1))
                || !isDigit(token.charAt(3)) || !isDigit(token.charAt(4))) {
            return null;
        }
        if (token.length() == 5) {
            return versionBehavior.allowsV11() ? token + ":00" : null;
        }
        if (token.length() < 8 || token.charAt(5) != ':' || !isDigit(token.charAt(6)) || !isDigit(token.charAt(7))) {
            return null;
        }
        if (token.length() == 8) {
            return token;
        }
        if (token.charAt(8) != '.' || token.length() == 9) {
            return null;
        }
        for (int i = 9; i < token.length(); i++) {
            if (!isDigit(token.charAt(i))) {
                return null;
            }
        }
        return token.substring(0, 8) + normalizeFraction(token.substring(8));
    }

    private static String normalizeFraction(String fraction) {
        if (fraction == null) {
            return "";
        }
        return fraction.length() > 10 ? fraction.substring(0, 10) : fraction;
    }

    private static int offsetIndex(String rest) {
        if (rest.endsWith("Z")) {
            return rest.length() - 1;
        }
        if (rest.length() < 11) {
            return -1;
        }
        int candidate = rest.length() - 6;
        char c = rest.charAt(candidate);
        return c == '+' || c == '-' ? candidate : -1;
    }

    private static boolean isOffset(String offset) {
        return "Z".equals(offset)
                || (offset.length() == 6
                && (offset.charAt(0) == '+' || offset.charAt(0) == '-')
                && isDigit(offset.charAt(1))
                && isDigit(offset.charAt(2))
                && offset.charAt(3) == ':'
                && isDigit(offset.charAt(4))
                && isDigit(offset.charAt(5)));
    }

    private static int decimalIntegerPartEnd(String token, int start) {
        if (start >= token.length() || !isDigit(token.charAt(start))) {
            return -1;
        }
        if (token.charAt(start) == '0') {
            int next = start + 1;
            if (next < token.length() && (token.charAt(next) == '_' || isDigit(token.charAt(next)))) {
                return -1;
            }
            return next;
        }
        return digitRunEnd(token, start);
    }

    private static int digitRunEnd(String token, int start) {
        if (start >= token.length() || !isDigit(token.charAt(start))) {
            return -1;
        }
        int i = start + 1;
        while (i < token.length()) {
            char c = token.charAt(i);
            if (isDigit(c)) {
                i++;
            } else if (c == '_') {
                if (i + 1 >= token.length() || !isDigit(token.charAt(i + 1))) {
                    return -1;
                }
                i += 2;
            } else {
                break;
            }
        }
        return i;
    }

    private static void validateBasicStringChar(char c, boolean multiline) {
        if (c == '\u007F' || c < ' ') {
            boolean valid = c == '\t' || (multiline && c == '\n');
            if (!valid) {
                throw new TomlParseException("Control character in basic string");
            }
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static void validateLiteralStringChar(char c, boolean multiline) {
        if (c == '\u007F' || c < ' ') {
            boolean valid = c == '\t' || (multiline && c == '\n');
            if (!valid) {
                throw new TomlParseException("Control character in literal string");
            }
        }
    }

    private static final class Parser {
        private final String input;
        private final MutableTable root = new MutableTable(false);
        private final int maxNestingDepth;
        private final TomlVersionBehavior versionBehavior;

        private MutableTable current = root;
        private int index;
        private int line = 1;
        private int column = 1;

        private Parser(String input, TomlParserConfig config) {
            this.input = normalizeNewlines(input);
            this.maxNestingDepth = config.maxNestingDepth();
            this.versionBehavior = config.versionBehavior();
        }

        private TomlTable parse() {
            skipWhitespaceNewlinesAndComments();
            while (!isEnd()) {
                if (peek() == '[') {
                    parseHeader();
                } else {
                    parseKeyValue(current);
                }
                skipWhitespaceNewlinesAndComments();
            }
            return toTomlTable(root, 0);
        }

        private void parseHeader() {
            boolean array = startsWith("[[");
            advance();

            if (array) {
                advance();
            }

            skipWhitespace();
            List<String> key = parseDottedKey();
            skipWhitespace();

            expect(']');

            if (array) {
                expect(']');
                current = openArrayTable(key);
            } else {
                current = openTable(key);
            }
            endOfStatement();
        }

        private void parseKeyValue(MutableTable table) {
            List<String> key = parseDottedKey();
            skipWhitespace();
            expect('=');
            skipWhitespace();
            ParsedValue value = parseValue();
            putDotted(table, key, value, false);
            endOfStatement();
        }

        private List<String> parseDottedKey() {
            List<String> result = new ArrayList<>();
            result.add(parseKeyPart());
            skipWhitespace();
            while (!isEnd() && peek() == '.') {
                advance();
                skipWhitespace();
                result.add(parseKeyPart());
                skipWhitespace();
            }
            return result;
        }

        private String parseKeyPart() {
            char c = peekOrThrow("Expected key");
            if (startsWith("\"\"\"") || startsWith("'''")) {
                throw error("Multiline strings cannot be used as keys");
            }
            if (c == '"') {
                return parseBasicString(false);
            }
            if (c == '\'') {
                return parseLiteralString(false);
            }
            return parseBareKey();
        }

        private String parseBareKey() {
            int start = index;
            while (!isEnd()) {
                char c = peek();
                if (isBareKey(c)) {
                    advance();
                } else {
                    break;
                }
            }
            if (start == index) {
                throw error("Expected bare key");
            }
            return input.substring(start, index);
        }

        private ParsedValue parseValue() {
            return parseValue(0);
        }

        private ParsedValue parseValue(int depth) {
            char c = peekOrThrow("Expected value");
            return switch (c) {
            case '"' -> new ScalarValue(TomlString.create(parseBasicString(startsWith("\"\"\""))));
            case '\'' -> new ScalarValue(TomlString.create(parseLiteralString(startsWith("'''"))));
            case '[' -> parseArray(depth + 1);
            case '{' -> parseInlineTable(depth + 1);
            default -> new ScalarValue(parsePrimitive());
            };
        }

        private MutableArray parseArray(int depth) {
            checkNestingDepth(depth);
            expect('[');
            MutableArray array = new MutableArray();
            skipWhitespaceNewlinesAndComments();
            if (consume(']')) {
                return array;
            }

            while (true) {
                array.values.add(parseValue(depth));
                skipWhitespaceNewlinesAndComments();
                if (consume(',')) {
                    skipWhitespaceNewlinesAndComments();
                    if (consume(']')) {
                        return array;
                    }
                    continue;
                }
                expect(']');
                return array;
            }
        }

        private MutableTable parseInlineTable(int depth) {
            checkNestingDepth(depth);
            expect('{');
            MutableTable table = new MutableTable(true);
            table.inlineDefined = true;
            skipInlineTableWhitespace();
            if (consume('}')) {
                return table;
            }

            while (true) {
                List<String> key = parseDottedKey();
                skipWhitespace();
                expect('=');
                skipWhitespace();
                ParsedValue value = parseValue(depth);
                putDotted(table, key, value, true);
                skipInlineTableWhitespace();
                if (consume(',')) {
                    skipInlineTableWhitespace();
                    if (consume('}')) {
                        requireV11("Trailing comma in inline table");
                        return table;
                    }
                    continue;
                }
                expect('}');
                return table;
            }
        }

        private TomlScalar<?> parsePrimitive() {
            int start = index;
            while (!isEnd()) {
                char c = peek();
                if (c == ',' || c == ']' || c == '}' || c == '#' || c == '\n') {
                    break;
                }
                advance();
            }
            String token = input.substring(start, index).trim();
            if (token.isEmpty()) {
                throw error("Expected value");
            }
            return parsePrimitiveToken(token);
        }

        private TomlScalar<?> parsePrimitiveToken(String token) {
            if ("true".equals(token)) {
                return TomlBoolean.TRUE;
            }
            if ("false".equals(token)) {
                return TomlBoolean.FALSE;
            }

            try {
                TomlScalar<?> dateTime = parseDateTime(token, versionBehavior);
                if (dateTime != null) {
                    return dateTime;
                }

                if (LOCAL_DATE.matcher(token).matches()) {
                    return TomlLocalDate.create(LocalDate.parse(token));
                }

                String localTime = timeText(token, versionBehavior);
                if (localTime != null) {
                    return TomlLocalTime.create(LocalTime.parse(localTime));
                }
            } catch (DateTimeParseException e) {
                throw error("Invalid date/time value: " + token, e);
            }

            if (SPECIAL_FLOAT.matcher(token).matches()) {
                return TomlFloat.create(specialFloat(token));
            }

            if (isFloat(token)) {
                return TomlFloat.create(Double.parseDouble(token.replace("_", "")));
            }

            if (DECIMAL_INTEGER.matcher(token).matches()) {
                return TomlInteger.create(parseDecimalInteger(token));
            }

            if (HEX_INTEGER.matcher(token).matches()) {
                return TomlInteger.create(parseBaseInteger(token, 16));
            }
            if (OCTAL_INTEGER.matcher(token).matches()) {
                return TomlInteger.create(parseBaseInteger(token, 8));
            }
            if (BINARY_INTEGER.matcher(token).matches()) {
                return TomlInteger.create(parseBaseInteger(token, 2));
            }

            throw error("Invalid value: " + token);
        }

        private boolean isFloat(String token) {
            int pos = 0;
            if (pos < token.length() && (token.charAt(pos) == '+' || token.charAt(pos) == '-')) {
                pos++;
            }
            int afterInteger = decimalIntegerPartEnd(token, pos);
            if (afterInteger < 0) {
                return false;
            }
            pos = afterInteger;

            boolean hasFraction = false;
            boolean hasExponent = false;
            if (pos < token.length() && token.charAt(pos) == '.') {
                hasFraction = true;
                pos = digitRunEnd(token, pos + 1);
                if (pos < 0) {
                    return false;
                }
            }
            if (pos < token.length() && (token.charAt(pos) == 'e' || token.charAt(pos) == 'E')) {
                hasExponent = true;
                pos++;
                if (pos < token.length() && (token.charAt(pos) == '+' || token.charAt(pos) == '-')) {
                    pos++;
                }
                pos = digitRunEnd(token, pos);
                if (pos < 0) {
                    return false;
                }
            }
            return pos == token.length() && (hasFraction || hasExponent);
        }

        private String parseBasicString(boolean multiline) {
            if (multiline) {
                expect("\"\"\"");
                consumeFirstMultilineNewline();
                return parseMultilineBasicString();
            }
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!isEnd()) {
                char c = advance();
                if (c == '"') {
                    return result.toString();
                }
                if (c == '\n') {
                    throw error("Unterminated basic string");
                }
                if (c == '\\') {
                    appendEscaped(result);
                } else {
                    appendBasicStringChar(result, c, false);
                }
            }
            throw error("Unterminated basic string");
        }

        private String parseMultilineBasicString() {
            StringBuilder result = new StringBuilder();
            while (!isEnd()) {
                if (peek() == '"') {
                    int quoteCount = quoteRun('"');
                    if (quoteCount >= 3) {
                        if (quoteCount > 5) {
                            throw error("Too many quotation marks in multiline basic string");
                        }
                        result.repeat("\"", quoteCount - 3);
                        advance(quoteCount);
                        return result.toString();
                    }
                }

                char c = advance();
                if (c == '\\') {
                    if (consumeLineEndingBackslash()) {
                        continue;
                    }
                    appendEscaped(result);
                } else {
                    appendBasicStringChar(result, c, true);
                }
            }
            throw error("Unterminated multiline basic string");
        }

        private String parseLiteralString(boolean multiline) {
            if (multiline) {
                expect("'''");
                consumeFirstMultilineNewline();
                return parseMultilineLiteralString();
            }
            expect('\'');
            StringBuilder result = new StringBuilder();
            while (!isEnd()) {
                char c = advance();
                if (c == '\'') {
                    return result.toString();
                }
                if (c == '\n') {
                    throw error("Unterminated literal string");
                }
                appendLiteralStringChar(result, c, false);
            }
            throw error("Unterminated literal string");
        }

        private String parseMultilineLiteralString() {
            StringBuilder result = new StringBuilder();
            while (!isEnd()) {
                if (peek() == '\'') {
                    int quoteCount = quoteRun('\'');
                    if (quoteCount >= 3) {
                        if (quoteCount > 5) {
                            throw error("Too many apostrophes in multiline literal string");
                        }
                        result.repeat("'", quoteCount - 3);
                        advance(quoteCount);
                        return result.toString();
                    }
                }
                char c = advance();
                appendLiteralStringChar(result, c, true);
            }
            throw error("Unterminated multiline literal string");
        }

        private void appendBasicStringChar(StringBuilder result, char c, boolean multiline) {
            validateBasicStringChar(c, multiline);
            appendUnicodeScalar(result, c);
        }

        private void appendLiteralStringChar(StringBuilder result, char c, boolean multiline) {
            validateLiteralStringChar(c, multiline);
            appendUnicodeScalar(result, c);
        }

        private void appendUnicodeScalar(StringBuilder result, char c) {
            if (Character.isHighSurrogate(c)) {
                if (isEnd() || !Character.isLowSurrogate(peek())) {
                    throw error("Invalid unicode scalar value");
                }
                result.append(c);
                result.append(advance());
                return;
            }
            if (Character.isLowSurrogate(c)) {
                throw error("Invalid unicode scalar value");
            }
            result.append(c);
        }

        private void appendEscaped(StringBuilder result) {
            char escaped = advanceOrThrow();
            switch (escaped) {
            case 'b' -> result.append('\b');
            case 't' -> result.append('\t');
            case 'n' -> result.append('\n');
            case 'f' -> result.append('\f');
            case 'r' -> result.append('\r');
            case 'e' -> {
                requireV11("Escape sequence \\e");
                result.append('\u001B');
            }
            case '"' -> result.append('"');
            case '\\' -> result.append('\\');
            case 'x' -> {
                requireV11("Escape sequence \\xHH");
                result.appendCodePoint(readCodePoint(2));
            }
            case 'u' -> result.appendCodePoint(readCodePoint(4));
            case 'U' -> result.appendCodePoint(readCodePoint(8));
            default -> throw error("Invalid escape sequence: \\" + escaped);
            }
        }

        private int readCodePoint(int digits) {
            if (index + digits > input.length()) {
                throw error("Incomplete unicode escape");
            }
            String hex = input.substring(index, index + digits);
            for (int i = 0; i < hex.length(); i++) {
                if (Character.digit(hex.charAt(i), 16) < 0) {
                    throw error("Invalid unicode escape");
                }
            }
            advance(digits);
            int codePoint = Integer.parseUnsignedInt(hex, 16);
            if (!Character.isValidCodePoint(codePoint) || isSurrogate(codePoint)) {
                throw error("Invalid unicode scalar value");
            }
            return codePoint;
        }

        private boolean consumeLineEndingBackslash() {
            int savedIndex = index;
            int savedLine = line;
            int savedColumn = column;

            while (!isEnd() && isWhitespace(peek())) {
                advance();
            }
            if (isEnd() || peek() != '\n') {
                index = savedIndex;
                line = savedLine;
                column = savedColumn;
                return false;
            }
            do {
                advance();
            } while (!isEnd() && (isWhitespace(peek()) || peek() == '\n'));
            return true;
        }

        private MutableTable openTable(List<String> key) {
            TableLocation location = tableLocation(key);
            ParsedValue existing = location.existing();
            if (existing == null) {
                MutableTable table = new MutableTable(false);
                table.headerDefined = true;
                location.parent().values.put(location.last(), table);
                return table;
            }
            if (existing instanceof MutableTable table) {
                if (table.inline || table.headerDefined || table.dottedKeyDefined) {
                    throw error("Table already defined: " + String.join(".", key));
                }
                table.headerDefined = true;
                return table;
            }
            throw error("Cannot redefine non-table value as table: " + String.join(".", key));
        }

        private MutableTable openArrayTable(List<String> key) {
            TableLocation location = tableLocation(key);
            ParsedValue existing = location.existing();
            MutableTableArray array;
            if (existing == null) {
                array = new MutableTableArray();
                location.parent().values.put(location.last(), array);
            } else if (existing instanceof MutableTableArray existingArray) {
                array = existingArray;
            } else {
                throw error("Cannot redefine value as array of tables: " + String.join(".", key));
            }

            MutableTable table = new MutableTable(false);
            table.headerDefined = true;
            array.values.add(table);
            return table;
        }

        private TableLocation tableLocation(List<String> key) {
            MutableTable parent = root;
            for (int i = 0; i < key.size() - 1; i++) {
                parent = tableForPart(parent, key.get(i), false);
            }

            String last = key.getLast();
            return new TableLocation(parent, last, parent.values.get(last));
        }

        private MutableTable tableForPart(MutableTable parent, String part, boolean inlineDottedKey) {
            ParsedValue existing = parent.values.get(part);
            switch (existing) {
            case null -> {
                MutableTable table = new MutableTable(inlineDottedKey);
                parent.values.put(part, table);
                return table;
            }
            case MutableTable table -> {
                if (table.inline && (!inlineDottedKey || table.inlineDefined)) {
                    throw error("Cannot add keys to an inline table");
                }
                return table;
            }
            case MutableTableArray array -> {
                if (array.values.isEmpty()) {
                    throw error("Array of tables has no current element: " + part);
                }
                return array.values.getLast();
            }
            default -> {
            }
            }
            throw error("Cannot redefine non-table value as table: " + part);
        }

        private void putDotted(MutableTable table, List<String> key, ParsedValue value, boolean inlineDottedKey) {
            MutableTable parent = table;
            for (int i = 0; i < key.size() - 1; i++) {
                parent = tableForPart(parent, key.get(i), inlineDottedKey);
                parent.dottedKeyDefined = true;
            }

            String last = key.getLast();
            if (parent.values.containsKey(last)) {
                throw error("Key already defined: " + String.join(".", key));
            }
            parent.values.put(last, value);
        }

        private void endOfStatement() {
            skipWhitespace();
            if (!isEnd() && peek() == '#') {
                skipComment();
            }
            if (isEnd()) {
                return;
            }
            if (peek() == '\n') {
                advance();
                return;
            }
            throw error("Expected end of statement");
        }

        private void checkNestingDepth(int depth) {
            if (depth > maxNestingDepth) {
                throw error("TOML nesting depth exceeds maximum of " + maxNestingDepth);
            }
        }

        private void skipWhitespaceNewlinesAndComments() {
            while (!isEnd()) {
                char c = peek();
                if (isWhitespace(c) || c == '\n') {
                    advance();
                } else if (c == '#') {
                    skipComment();
                } else {
                    return;
                }
            }
        }

        private void skipInlineTableWhitespace() {
            if (versionBehavior.allowsV11()) {
                skipWhitespaceNewlinesAndComments();
            } else {
                skipWhitespace();
            }
        }

        private void skipWhitespace() {
            while (!isEnd() && isWhitespace(peek())) {
                advance();
            }
        }

        private void skipComment() {
            expect('#');
            while (!isEnd() && peek() != '\n') {
                char c = advance();
                if (isControl(c) && c != '\t') {
                    throw error("Control character in comment");
                }
            }
        }

        private void consumeFirstMultilineNewline() {
            if (!isEnd() && peek() == '\n') {
                advance();
            }
        }

        private boolean consume(char expected) {
            if (!isEnd() && peek() == expected) {
                advance();
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private void expect(String expected) {
            if (!startsWith(expected)) {
                throw error("Expected \"" + expected + "\"");
            }
            advance(expected.length());
        }

        private char peekOrThrow(String message) {
            if (isEnd()) {
                throw error(message);
            }
            return peek();
        }

        private char advanceOrThrow() {
            if (isEnd()) {
                throw error("Incomplete escape sequence");
            }
            return advance();
        }

        private char peek() {
            return input.charAt(index);
        }

        private boolean startsWith(String value) {
            return input.startsWith(value, index);
        }

        private char advance() {
            char c = input.charAt(index++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            return c;
        }

        private void advance(int count) {
            for (int i = 0; i < count; i++) {
                advance();
            }
        }

        private boolean isEnd() {
            return index >= input.length();
        }

        private int quoteRun(char quote) {
            int count = 0;
            int currentIndex = index;
            while (currentIndex < input.length() && input.charAt(currentIndex) == quote) {
                count++;
                currentIndex++;
            }
            return count;
        }

        private TomlParseException error(String message) {
            return new TomlParseException(message + " at line " + line + ", column " + column);
        }

        private TomlParseException error(String message, Throwable cause) {
            return new TomlParseException(message + " at line " + line + ", column " + column, cause);
        }

        private void requireV11(String feature) {
            if (!versionBehavior.allowsV11()) {
                throw error(feature + " requires TOML v1.1.0");
            }
        }

        private TomlTable toTomlTable(MutableTable table, int depth) {
            checkNestingDepth(depth);
            Map<String, TomlValue> result = new LinkedHashMap<>();
            table.values.forEach((key, value) -> result.put(key, toTomlValue(value, depth + 1)));
            return new TomlTable(result);
        }

        private TomlArray toTomlArray(MutableArray array, int depth) {
            checkNestingDepth(depth);
            List<TomlValue> result = new ArrayList<>();
            array.values.forEach(value -> result.add(toTomlValue(value, depth + 1)));
            return new TomlArray(result);
        }

        private TomlArray toTomlArray(MutableTableArray array, int depth) {
            checkNestingDepth(depth);
            List<TomlValue> result = new ArrayList<>();
            array.values.forEach(value -> result.add(toTomlTable(value, depth + 1)));
            return new TomlArray(result);
        }

        private TomlValue toTomlValue(ParsedValue value, int depth) {
            return switch (value) {
            case ScalarValue scalar -> scalar.value();
            case MutableTable table -> toTomlTable(table, depth);
            case MutableArray array -> toTomlArray(array, depth);
            case MutableTableArray array -> toTomlArray(array, depth);
            };
        }

        private record TableLocation(MutableTable parent, String last, ParsedValue existing) {
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isBareKey(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }

    private static boolean isControl(char c) {
        return c <= '\u001F' || c == '\u007F';
    }

    private static boolean isSurrogate(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }

    private static String normalizeNewlines(String text) {
        StringBuilder result = null;
        int line = 1;
        int column = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                    throw new TomlParseException("Bare carriage return at line " + line + ", column " + column);
                }
                if (result == null) {
                    result = new StringBuilder(text.length());
                    result.append(text, 0, i);
                }
                result.append('\n');
                i++;
                line++;
                column = 1;
            } else {
                if (result != null) {
                    result.append(c);
                }
                if (c == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
        }
        return result == null ? text : result.toString();
    }

    private sealed interface ParsedValue permits ScalarValue, MutableTable, MutableArray, MutableTableArray {
    }

    private record ScalarValue(TomlScalar<?> value) implements ParsedValue {
        ScalarValue {
            Objects.requireNonNull(value);
        }
    }

    private static final class MutableTable implements ParsedValue {
        private final Map<String, ParsedValue> values = new LinkedHashMap<>();
        private final boolean inline;
        private boolean inlineDefined;
        private boolean headerDefined;
        private boolean dottedKeyDefined;

        private MutableTable(boolean inline) {
            this.inline = inline;
        }
    }

    private static final class MutableArray implements ParsedValue {
        private final List<ParsedValue> values = new ArrayList<>();

        private MutableArray() {
        }
    }

    private static final class MutableTableArray implements ParsedValue {
        private final List<MutableTable> values = new ArrayList<>();
    }
}
