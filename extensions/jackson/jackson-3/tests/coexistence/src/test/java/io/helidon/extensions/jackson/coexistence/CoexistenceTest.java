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

package io.helidon.extensions.jackson.coexistence;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class CoexistenceTest {
    private static final List<String> SCENARIOS = List.of("jackson-2", "jackson-3", "jackson,jackson-2",
                                                         "jackson-2,jackson-3", "jackson,jackson-2,jackson-3");

    @Test
    void providersOnClasspathAndModulePath() throws Exception {
        List<String> dependencies = Arrays.asList(System.getProperty("surefire.test.class.path",
                                                                     System.getProperty("java.class.path"))
                                                         .split(Pattern.quote(File.pathSeparator)));
        Path output = Path.of("target", "coexistence");
        Files.createDirectories(output);
        for (String scenario : SCENARIOS) {
            for (boolean modules : List.of(false, true)) {
                for (boolean reverse : List.of(false, true)) {
                    runProbe(dependencies, scenario, modules, reverse, output);
                }
            }
        }
    }

    private static void runProbe(List<String> dependencies,
                                 String scenario,
                                 boolean modules,
                                 boolean reverse,
                                 Path output) throws Exception {
        List<String> entries = new ArrayList<>(dependencies.stream().filter(entry -> included(entry, scenario)).toList());
        if (reverse) {
            Collections.reverse(entries);
        }
        List<String> command = new ArrayList<>();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        if (modules) {
            // The probe remains in the unnamed module; all library JARs are resolved as named modules.
            // This exercises the libraries' module descriptors and JPMS service declarations.
            command.add("--module-path");
            command.add(String.join(File.pathSeparator, entries.stream().filter(entry -> !probeClasses(entry)).toList()));
            command.add("--add-modules");
            command.add("ALL-MODULE-PATH");
            command.add("-cp");
            command.add(String.join(File.pathSeparator, entries.stream().filter(CoexistenceTest::probeClasses).toList()));
        } else {
            command.add("-cp");
            command.add(String.join(File.pathSeparator, entries));
        }
        command.add(CoexistenceProbe.class.getName());
        command.add(scenario);
        String name = scenario.replace(',', '-') + (modules ? "-jpms" : "-classpath") + (reverse ? "-2" : "-1");
        Path log = output.resolve(name + ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor();
            }
            String transcript = Files.readString(log);
            assertThat(name + " timed out\n" + transcript, exited, is(true));
            assertThat(name + "\n" + transcript, process.exitValue(), is(0));
            assertThat(name, transcript, containsString("PASS " + scenario));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static boolean included(String entry, String scenario) {
        String path = entry.replace('\\', '/');
        if (path.contains("helidon-extensions-jackson-jackson-2-http-media")
                || path.contains("/jackson-2/modules/http-media/target/")) {
            return scenario.contains("jackson-2");
        }
        if (path.contains("helidon-extensions-jackson-jackson-3-http-media")
                || path.contains("/jackson-3/modules/http-media/target/")) {
            return scenario.contains("jackson-3");
        }
        if (path.contains("helidon-http-media-jackson/")) {
            return Arrays.asList(scenario.split(",")).contains("jackson");
        }
        // Jackson 3 still uses the shared com.fasterxml.jackson annotation library.
        if (path.contains("/tools/jackson/")) {
            return scenario.contains("jackson-3");
        }
        if (path.contains("/com/fasterxml/jackson/") && !path.contains("/jackson-annotations/")) {
            return !scenario.equals("jackson-3");
        }
        return true;
    }

    private static boolean probeClasses(String entry) {
        return entry.replace('\\', '/').contains("/coexistence/target/");
    }
}
