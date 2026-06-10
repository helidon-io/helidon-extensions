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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

final class TomlDateTimes {
    private static final DateTimeFormatter LOCAL_TIME = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter();
    private static final DateTimeFormatter LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral('T')
            .append(LOCAL_TIME)
            .toFormatter();
    private static final DateTimeFormatter OFFSET_DATE_TIME = new DateTimeFormatterBuilder()
            .append(LOCAL_DATE_TIME)
            .appendOffset("+HH:MM", "Z")
            .toFormatter();

    private TomlDateTimes() {
    }

    static String format(LocalTime value) {
        return LOCAL_TIME.format(value);
    }

    static String format(LocalDateTime value) {
        return LOCAL_DATE_TIME.format(value);
    }

    static String format(OffsetDateTime value) {
        return OFFSET_DATE_TIME.format(value);
    }
}
