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
package io.helidon.extensions.jackson.jackson2.tests.compatibility;

import java.io.File;
import java.io.StringWriter;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class LegacyCompatibilityTest {
    private static final String OLD_TYPE = "io.helidon.builder.tests.jackson.DemoWorkflowArguments";
    private static final String NEW_TYPE = "io.helidon.extensions.jackson.jackson2.tests.builder.codegen.DemoWorkflowArguments";
    private static final String MAIN = "io.helidon.extensions.jackson.jackson2.tests.compatibility.runner.CompatibilityMain";
    private static final String RUNNER_MODULE = "io.helidon.extensions.jackson.jackson2.tests.compatibility.runner";
    private static final Path RESOURCES = Path.of("target/test-classes");

    @Test
    void legacySourcesAndBinariesWorkWithReplacement() throws Exception {
        List<Path> runtime;
        try (var files = Files.list(Path.of("target/runtime"))) {
            runtime = files.filter(path -> path.toString().endsWith(".jar")).sorted().toList();
        }
        assertThat("Runtime dependencies were copied", runtime.isEmpty(), is(false));
        for (var path : runtime) {
            assertThat(path.toString(), not(containsString("helidon-http-media-jackson-")));
            assertThat(path.toString(), not(containsString("helidon-builder-codegen-jackson-")));
        }
        Path newApplication = Path.of(Class.forName(NEW_TYPE).getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> libraries = runtime;
        Path binary = RESOURCES.resolve("legacy/legacy-generated.jar");
        // The exact same baseline bytes run in all modes, independently of the recompiled sources.
        for (boolean modular : List.of(false, true)) {
            Path recompiled = Path.of("target/compatibility/recompiled-" + modular);
            compile(RESOURCES.resolve("legacy/source"), recompiled, libraries, modular);
            for (Path legacy : List.of(binary, recompiled)) {
                run(libraries, List.of(legacy), List.of(OLD_TYPE), modular,
                    legacy.getFileName() + "-old-" + modular);
                run(libraries, List.of(newApplication), List.of(NEW_TYPE), modular,
                    legacy.getFileName() + "-new-" + modular);
                run(libraries, List.of(legacy, newApplication), List.of(OLD_TYPE, NEW_TYPE), modular,
                    legacy.getFileName() + "-mixed-" + modular);
            }
        }
    }

    private static void compile(Path source, Path output, List<Path> dependencies, boolean modular) throws Exception {
        Files.createDirectories(output);
        var options = new ArrayList<>(List.of("-proc:none", "-d", output.toString(),
                                              modular ? "--module-path" : "--class-path", paths(dependencies)));
        List<File> files;
        try (var sources = Files.walk(source)) {
            files = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> modular || !path.getFileName().toString().equals("module-info.java"))
                    .sorted().map(Path::toFile).toList();
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var diagnostics = new StringWriter();
            boolean success = compiler.getTask(diagnostics, manager, null, options, null,
                                              manager.getJavaFileObjectsFromFiles(files)).call();
            assertThat("Compilation of " + source + ": " + diagnostics, success, is(true));
        }
    }

    private static void run(List<Path> libraries,
                            List<Path> applications,
                            List<String> types,
                            boolean modular,
                            String scenario) throws Exception {
        var dependencies = new ArrayList<>(libraries);
        dependencies.addAll(applications);
        var output = Path.of("target/compatibility").resolve(scenario);
        compile(RESOURCES.resolve("runner"), output, dependencies, modular);
        dependencies.add(output);
        var command = new ArrayList<String>();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        if (modular) {
            for (Path application : applications) {
                var descriptor = ModuleFinder.of(application).findAll().iterator().next().descriptor();
                for (String name : descriptor.packages()) {
                    command.addAll(List.of("--add-opens", descriptor.name() + "/" + name + "=com.fasterxml.jackson.databind"));
                }
            }
            var modules = ModuleFinder.of(dependencies.toArray(Path[]::new)).findAll().stream()
                    .map(reference -> reference.descriptor().name()).sorted().collect(Collectors.joining(","));
            command.addAll(List.of("--module-path", paths(dependencies), "--add-modules", modules,
                                   "--module", RUNNER_MODULE + "/" + MAIN));
        } else {
            command.addAll(List.of("--class-path", paths(dependencies), MAIN));
        }
        command.addAll(types);
        var log = output.resolve("execution.log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            assertThat(scenario + " exceeded timeout", finished, is(true));
            assertThat(scenario + ": " + Files.readString(log), process.exitValue(), is(0));
            for (var type : types) {
                assertThat(scenario, Files.readString(log), containsString("Roundtrip OK: " + type));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static String paths(List<Path> dependencies) {
        return dependencies.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }
}
