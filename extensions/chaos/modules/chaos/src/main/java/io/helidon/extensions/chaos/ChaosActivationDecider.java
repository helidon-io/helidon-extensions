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

import java.nio.charset.StandardCharsets;

/**
 * Stable activation decisions that do not depend on JDK random-generator implementations.
 */
final class ChaosActivationDecider {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private ChaosActivationDecider() {
    }

    static long streamSeed(long runSeed, String... identities) {
        long identityHash = FNV_OFFSET_BASIS;
        identityHash = hashByte(identityHash, identities.length);
        for (String component : identities) {
            byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
            identityHash = hashByte(identityHash, bytes.length >>> 24);
            identityHash = hashByte(identityHash, bytes.length >>> 16);
            identityHash = hashByte(identityHash, bytes.length >>> 8);
            identityHash = hashByte(identityHash, bytes.length);
            for (byte value : bytes) {
                identityHash = hashByte(identityHash, value);
            }
        }
        return mix64(runSeed ^ identityHash);
    }

    static boolean activates(ChaosActivation activation, long streamSeed, long matchedInvocation) {
        if (activation instanceof ProbabilityActivation probability) {
            long sampleBits = mix64(streamSeed + (matchedInvocation - 1) * GOLDEN_GAMMA);
            double sample = (sampleBits >>> 11) * DOUBLE_UNIT;
            return sample < probability.probability();
        }
        return true;
    }

    static long mix64(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    private static long hashByte(long hash, int value) {
        return (hash ^ (value & 0xff)) * FNV_PRIME;
    }
}
