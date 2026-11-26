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

package io.helidon.extensions.langchain4j.httpclient;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.Executor;

import io.helidon.common.context.Context;
import io.helidon.common.context.Contexts;

import dev.langchain4j.spi.CapturedContext;
import dev.langchain4j.spi.ExecutorProvider;
import dev.langchain4j.spi.ServiceHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelidonExecutorProviderTest {
    private static final String SERVICE = "META-INF/services/" + ExecutorProvider.class.getName();
    private static final URL ADAPTER = HelidonExecutorProvider.class.getProtectionDomain().getCodeSource().getLocation();

    @Test
    void applicationExecutorWinsInBothClasspathOrders(@TempDir Path application) throws IOException {
        Path descriptor = application.resolve(SERVICE);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, ApplicationProvider.class.getName());
        URL app = application.toUri().toURL();

        assertThat(discover(ADAPTER, app).executor(), sameInstance(ApplicationProvider.EXECUTOR));
        assertThat(discover(app, ADAPTER).executor(), sameInstance(ApplicationProvider.EXECUTOR));
    }

    @Test
    void restoresCompletionContextAfterExceptionalTask() throws IOException {
        Context caller = Context.create();
        Context completion = Context.create();
        ExecutorProvider provider = discover(ADAPTER);
        CapturedContext captured = Contexts.runInContext(caller, provider::captureContext);

        Contexts.runInContext(completion, () -> {
            assertThrows(IllegalStateException.class, () -> captured.wrap(() -> {
                assertThat(Contexts.context().orElseThrow(), sameInstance(caller));
                throw new IllegalStateException("task failed");
            }).run());
            assertThat(Contexts.context().orElseThrow(), sameInstance(completion));
        });
    }

    @Test
    void unboundCallerDoesNotInheritCompletionIdentity() {
        CapturedContext captured = new HelidonExecutorProvider().captureContext();
        Context completion = Context.create();
        completion.register("identity", "completion-user");

        Contexts.runInContext(completion, () -> {
            captured.wrap(() -> assertThat(Contexts.context().orElseThrow()
                                                  .get("identity", String.class).isEmpty(), is(true))).run();
            assertThat(Contexts.context().orElseThrow(), sameInstance(completion));
        });
    }

    private static ExecutorProvider discover(URL... classpath) throws IOException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (var loader = new URLClassLoader(classpath, previous) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                // Discover providers in this scenario's classpath order, sharing the test JVM's classes.
                return SERVICE.equals(name) ? findResources(name) : super.getResources(name);
            }
        }) {
            thread.setContextClassLoader(loader);
            return ServiceHelper.loadFactory(ExecutorProvider.class);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static final class ApplicationProvider implements ExecutorProvider {
        private static final Executor EXECUTOR = Runnable::run;

        @Override
        public Executor executor() {
            return EXECUTOR;
        }
    }
}
