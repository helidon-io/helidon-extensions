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
package io.helidon.extensions.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TestChaosScheduler implements ChaosScheduler {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-24T12:00:00Z"));
    private final List<Task> tasks = new ArrayList<>();

    Clock clock() {
        return clock;
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable action) {
        Task task = new Task(clock.instant().plus(delay), action);
        tasks.add(task);
        return task;
    }

    void advance(Duration duration) {
        clock.advance(duration);
        runDueTasks();
    }

    void fireLast() {
        Task task = tasks.stream()
                .filter(candidate -> !candidate.cancelled && !candidate.executed)
                .max(Comparator.comparing(candidate -> candidate.due))
                .orElseThrow();
        clock.set(task.due);
        task.run();
    }

    private void runDueTasks() {
        tasks.stream()
                .filter(task -> !task.cancelled && !task.executed && !task.due.isAfter(clock.instant()))
                .sorted(Comparator.comparing(task -> task.due))
                .toList()
                .forEach(Task::run);
    }

    @Override
    public void close() {
        tasks.forEach(Task::cancel);
    }

    private static final class Task implements Cancellable {
        private final Instant due;
        private final Runnable action;
        private boolean cancelled;
        private boolean executed;

        private Task(Instant due, Runnable action) {
            this.due = due;
            this.action = action;
        }

        @Override
        public boolean cancel() {
            if (cancelled || executed) {
                return false;
            }
            cancelled = true;
            return true;
        }

        private void run() {
            if (!cancelled && !executed) {
                executed = true;
                action.run();
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test clock supports UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        private void set(Instant value) {
            instant = value;
        }
    }
}
