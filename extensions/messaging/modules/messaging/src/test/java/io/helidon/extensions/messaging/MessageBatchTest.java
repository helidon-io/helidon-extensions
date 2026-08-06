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

package io.helidon.extensions.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBatchTest {
    @Test
    void createsImmutableEnvelopePreservingSnapshot() {
        Message<String> first = weightedMessage("one", 3);
        Message<String> second = weightedMessage("two", 5);
        List<Message<String>> source = new ArrayList<>(List.of(first, second));

        MessageBatch<String> batch = MessageBatch.<String>builder()
                .id("batch-1")
                .messages(source)
                .build();
        source.clear();

        assertEquals("batch-1", batch.id());
        assertEquals(2, batch.size());
        assertSame(first, batch.get(0));
        assertSame(second, batch.get(1));
        assertEquals(List.of("one", "two"), batch.payloads());
        assertEquals(8, batch.admissionBytes().orElseThrow());
        assertEquals(batch.messages(), toList(batch));
        assertThrows(UnsupportedOperationException.class, () -> batch.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.payloads().clear());
    }

    @Test
    void usesConservativeDeclaredAdmissionWeight() {
        MessageBatch<String> declaredLarger = MessageBatch.<String>builder()
                .add(weightedMessage("one", 3))
                .add(weightedMessage("two", 5))
                .admissionBytes(13)
                .build();
        MessageBatch<String> calculatedLarger = MessageBatch.<String>builder()
                .add(weightedMessage("one", 3))
                .add(weightedMessage("two", 5))
                .admissionBytes(2)
                .build();
        MessageBatch<String> declaredForUnknown = MessageBatch.<String>builder()
                .add(Message.create("unknown"))
                .admissionBytes(7)
                .build();
        MessageBatch<String> declaredWithKnownAndUnknown = MessageBatch.<String>builder()
                .add(weightedMessage("one", 3))
                .add(unknownMessage("unknown"))
                .add(weightedMessage("two", 5))
                .admissionBytes(7)
                .build();
        MessageBatch<String> unknown = MessageBatch.create(List.of(unknownMessage("unknown")));
        MessageBatch<String> overflowing = MessageBatch.<String>builder()
                .add(weightedMessage("one", Long.MAX_VALUE))
                .add(weightedMessage("two", 1))
                .admissionBytes(Long.MAX_VALUE)
                .build();

        assertEquals(13, declaredLarger.admissionBytes().orElseThrow());
        assertEquals(8, calculatedLarger.admissionBytes().orElseThrow());
        assertEquals(7, declaredForUnknown.admissionBytes().orElseThrow());
        assertEquals(8, declaredWithKnownAndUnknown.admissionBytes().orElseThrow());
        assertTrue(unknown.admissionBytes().isEmpty());
        assertTrue(overflowing.admissionBytes().isEmpty());
    }

    @Test
    void preservesDeliveryLineageAcrossDerivationAndSubsets() {
        Message<String> first = weightedMessage("one", 3);
        Message<String> second = weightedMessage("two", 5);
        Message<String> third = weightedMessage("three", 7);
        MessageBatch<String> original = MessageBatch.<String>builder()
                .id("batch-1")
                .messages(List.of(first, second, third))
                .build();

        MessageBatch<Integer> derived = original.derive(List.of(Message.create(1),
                                                                 Message.create(2),
                                                                 Message.create(3)));
        MessageBatch<String> retry = original.subset(List.of(0, 2));
        MessageBatch<Integer> derivedRetry = derived.subset(List.of(0, 2));
        MessageBatch<String> copiedIdentity = MessageBatch.<String>builder()
                .id(original.id())
                .messages(original.messages())
                .build();

        assertTrue(original.sameDelivery(derived));
        assertFalse(original.sameDelivery(copiedIdentity));
        assertFalse(original.sameDelivery(retry));
        assertTrue(retry.sameDelivery(derivedRetry));
        assertTrue(retry.isRetainedSubsetOf(original));
        assertFalse(copiedIdentity.isRetainedSubsetOf(original));
        assertFalse(derivedRetry.isRetainedSubsetOf(original));
        assertEquals(List.of("one", "three"), retry.payloads());
        assertEquals("batch-1", retry.id());
        assertThrows(IllegalArgumentException.class, () -> original.derive(List.of(Message.create(1))));
        assertThrows(IllegalArgumentException.class, () -> original.subset(List.of(2, 1)));
        assertThrows(IllegalArgumentException.class, () -> original.subset(List.of(1, 1)));
    }

    @Test
    void recalculatesAdmissionWeightForSelectedSubset() {
        MessageBatch<String> original = MessageBatch.<String>builder()
                .add(weightedMessage("one", 3))
                .add(unknownMessage("unknown"))
                .add(weightedMessage("three", 7))
                .admissionBytes(20)
                .build();

        MessageBatch<String> knownSubset = original.subset(List.of(0, 2));
        MessageBatch<String> unknownSubset = original.subset(List.of(1));
        MessageBatch<String> completeSelection = original.subset(List.of(0, 1, 2));

        assertEquals(10, knownSubset.admissionBytes().orElseThrow());
        assertTrue(unknownSubset.admissionBytes().isEmpty());
        assertEquals(20, completeSelection.admissionBytes().orElseThrow());
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> MessageBatch.create(List.of()));
        assertThrows(IllegalArgumentException.class,
                     () -> MessageBatch.<String>builder().id(" ").add(Message.create("one")).build());
        assertThrows(IllegalArgumentException.class,
                     () -> MessageBatch.<String>builder()
                             .id("x".repeat(MessageBatch.MAX_ID_LENGTH + 1))
                             .add(Message.create("one"))
                             .build());
        assertThrows(IllegalArgumentException.class,
                     () -> MessageBatch.<String>builder().add(Message.create("one")).admissionBytes(-1));
        assertThrows(NullPointerException.class,
                     () -> MessageBatch.<String>builder().messages(null));
        assertThrows(NullPointerException.class,
                     () -> MessageBatch.<String>builder().add(null));
    }

    @Test
    void describesSequentialAndIndeterminateFailures() {
        MessageBatch<String> batch = MessageBatch.create(List.of(Message.create("one"),
                                                                 Message.create("two"),
                                                                 Message.create("three")));
        IllegalStateException failure = new IllegalStateException("send failed");

        BatchDeliveryException sequential = BatchDeliveryException.sequential("send", batch, 1, failure);

        assertSame(batch, sequential.batch());
        assertSame(failure, sequential.getCause());
        assertEquals(BatchItemStatus.SUCCEEDED, sequential.outcome(0).status());
        assertEquals(BatchItemStatus.INDETERMINATE, sequential.outcome(1).status());
        assertSame(failure, sequential.outcome(1).failure().orElseThrow());
        assertEquals(BatchItemStatus.NOT_ATTEMPTED, sequential.outcome(2).status());
        assertFalse(sequential.outcome(2).failure().isPresent());
        assertThrows(UnsupportedOperationException.class, () -> sequential.outcomes().clear());

        BatchDeliveryException indeterminate = BatchDeliveryException.indeterminate("send", batch, failure);
        assertTrue(indeterminate.outcomes().stream()
                           .allMatch(outcome -> outcome.status() == BatchItemStatus.INDETERMINATE));

        BatchDeliveryException attemptedPrefix = BatchDeliveryException.attemptedPrefix("process", batch, 1, failure);
        assertEquals(List.of(BatchItemStatus.INDETERMINATE,
                             BatchItemStatus.INDETERMINATE,
                             BatchItemStatus.NOT_ATTEMPTED),
                     attemptedPrefix.outcomes().stream().map(BatchItemOutcome::status).toList());

        BatchDeliveryException notAttempted = BatchDeliveryException.notAttempted("send", batch, failure);
        assertTrue(notAttempted.outcomes().stream()
                           .allMatch(outcome -> outcome.status() == BatchItemStatus.NOT_ATTEMPTED));
    }

    @Test
    void validatesStructuredOutcomesAgainstOriginalIndexes() {
        MessageBatch<String> batch = MessageBatch.create(List.of(Message.create("one"), Message.create("two")));

        assertThrows(IllegalArgumentException.class,
                     () -> new BatchDeliveryException("failed",
                                                      batch,
                                                      List.of(BatchItemOutcome.failed(0, new RuntimeException())),
                                                      null));
        assertThrows(IllegalArgumentException.class,
                     () -> new BatchDeliveryException("failed",
                                                      batch,
                                                      List.of(BatchItemOutcome.succeeded(1),
                                                              BatchItemOutcome.notAttempted(0)),
                                                      null));
        assertThrows(IllegalArgumentException.class,
                     () -> new BatchDeliveryException("failed",
                                                      batch,
                                                      List.of(BatchItemOutcome.succeeded(0),
                                                              BatchItemOutcome.succeeded(1)),
                                                      null));
        assertThrows(IndexOutOfBoundsException.class,
                     () -> BatchDeliveryException.sequential("send", batch, 2, new RuntimeException()));
        assertThrows(IllegalArgumentException.class, () -> BatchItemOutcome.succeeded(-1));
    }

    private static Message<String> weightedMessage(String payload, long admissionBytes) {
        return new Message<>() {
            @Override
            public String entity() {
                return payload;
            }

            @Override
            public Map<String, String> headers() {
                return Map.of();
            }

            @Override
            public OptionalLong admissionBytes() {
                return OptionalLong.of(admissionBytes);
            }
        };
    }

    private static Message<String> unknownMessage(String payload) {
        return new Message<>() {
            @Override
            public String entity() {
                return payload;
            }

            @Override
            public Map<String, String> headers() {
                return Map.of();
            }
        };
    }

    private static <T> List<Message<T>> toList(MessageBatch<T> batch) {
        List<Message<T>> result = new ArrayList<>();
        batch.forEach(result::add);
        return result;
    }
}
