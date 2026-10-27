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

package io.helidon.extensions.jackson.jackson3.tests.builder.codegen;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class BuilderModulePathTest {
    private static final String MODEL_MODULE = "io.helidon.extensions.jackson.jackson3.tests.builder.codegen";
    private static final String RUNNER_MODULE = MODEL_MODULE + ".runner";
    private static final String RUNNER_MAIN = RUNNER_MODULE + ".BuilderModulePathMain";

    @Test
    void generatedModelsRoundTripWithoutOpeningTheirModule() throws Exception {
        Path output = Path.of("target", "builder-module-path");
        Path classes = output.resolve("classes");
        Files.createDirectories(classes);
        Path model = Path.of(DemoWorkflowArguments.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var dependencies = new ArrayList<>(Arrays.stream(System.getProperty("surefire.test.class.path",
                                                                           System.getProperty("java.class.path"))
                                                                .split(Pattern.quote(File.pathSeparator)))
                                                  .filter(entry -> entry.endsWith(".jar"))
                                                  .distinct()
                                                  .toList());
        dependencies.add(model.toString());
        String modulePath = String.join(File.pathSeparator, dependencies);
        Path source = Path.of("target", "test-classes", "module-path");

        run(List.of(executable("javac"), "-proc:none", "--module-path", modulePath,
                    "-d", classes.toString(), source.resolve("module-info.java").toString(),
                    source.resolve("BuilderModulePathMain.java").toString()), output.resolve("compilation.log"));

        dependencies.add(classes.toString());
        Path log = output.resolve("execution.log");
        run(List.of(executable("java"), "--module-path", String.join(File.pathSeparator, dependencies),
                    "--module", RUNNER_MODULE + "/" + RUNNER_MAIN), log);
        assertThat(Files.readString(log), containsString("PASS bean and generic models without opens"));

        Path strictLog = output.resolve("execution-strict.log");
        // Jackson's method handles require a read edge when reflective access overrides are disabled.
        run(List.of(executable("java"), "--module-path", String.join(File.pathSeparator, dependencies),
                    "--add-reads", "tools.jackson.databind=" + MODEL_MODULE,
                    "--module", RUNNER_MODULE + "/" + RUNNER_MAIN, "strict-access"), strictLog);
        assertThat(Files.readString(strictLog), containsString("PASS bean and generic models without opens"));
    }

    private static String executable(String tool) {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", tool + suffix).toString();
    }

    private static void run(List<String> command, Path log) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor();
            }
            String transcript = Files.readString(log);
            assertThat(command.getFirst() + " timed out\n" + transcript, exited, is(true));
            assertThat(command.getFirst() + "\n" + transcript, process.exitValue(), is(0));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
