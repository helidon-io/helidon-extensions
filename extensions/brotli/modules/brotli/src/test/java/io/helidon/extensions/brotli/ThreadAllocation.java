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

package io.helidon.extensions.brotli;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ThreadAllocation {
    private ThreadAllocation() {
    }

    static LongSupplier counter() throws ReflectiveOperationException {
        // Query public JDK management APIs without adding a dependency to the production JPMS module.
        var managementFactory = Class.forName("java.lang.management.ManagementFactory");
        Object allocationBean = managementFactory.getMethod("getThreadMXBean").invoke(null);
        var threadMxBean = Class.forName("com.sun.management.ThreadMXBean");
        assumeTrue(threadMxBean.isInstance(allocationBean), "Thread allocation measurements are unavailable");
        assumeTrue((boolean) threadMxBean.getMethod("isThreadAllocatedMemoryEnabled").invoke(allocationBean),
                   "Thread allocation measurements are disabled");
        var getAllocatedBytes = threadMxBean.getMethod("getThreadAllocatedBytes", long.class);
        long threadId = Thread.currentThread().threadId();
        return () -> {
            try {
                return (long) getAllocatedBytes.invoke(allocationBean, threadId);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        };
    }
}
