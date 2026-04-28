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

package io.helidon.openapi.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.opentest4j.TestAbortedException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

final class GeneratedProjectBuildSupport {

    private static final long BUILD_TIMEOUT_SECONDS = 600;
    private static final String BUILD_TESTS_PROPERTY = "helidon.codegen.it.buildsWithMaven";
    private static final String GRADLE_BUILD_TESTS_PROPERTY = "helidon.codegen.it.buildsWithGradle";

    private GeneratedProjectBuildSupport() {
    }

    static void assertMavenPackageSucceeds(Path projectDir) throws IOException, InterruptedException {
        if (System.getProperty(BUILD_TESTS_PROPERTY) == null) {
            throw new TestAbortedException("Skipping Maven build check. Set -D"
                                                   + BUILD_TESTS_PROPERTY + "=true to enable.");
        }

        runMaven(projectDir, "Maven package", "-DskipTests", "package");
    }

    static void assertMavenTestSucceeds(Path projectDir) throws IOException, InterruptedException {
        if (System.getProperty(BUILD_TESTS_PROPERTY) == null) {
            throw new TestAbortedException("Skipping Maven test check. Set -D"
                                                   + BUILD_TESTS_PROPERTY + "=true to enable.");
        }

        runMaven(projectDir, "Maven test", "test");
    }

    private static void runMaven(Path projectDir, String actionLabel, String... goals)
            throws IOException, InterruptedException {
        String mavenExecutable = isWindows() ? "mvn.cmd" : "mvn";
        Path settingsFile = Files.createTempFile("maven-settings-", ".xml");
        Files.writeString(settingsFile, "<settings/>", StandardCharsets.UTF_8);

        String[] command = new String[5 + goals.length];
        command[0] = mavenExecutable;
        command[1] = "-B";
        command[2] = "-q";
        command[3] = "-s";
        command[4] = settingsFile.toString();
        System.arraycopy(goals, 0, command, 5, goals.length);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir.toFile());
        processBuilder.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("generated-project-maven-", ".log");
        processBuilder.redirectOutput(outputFile.toFile());
        try {
            Process process = processBuilder.start();

            boolean finished = process.waitFor(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertThat(
                    String.format("Generated project %s timed out after %s seconds.%nOutput:%n%s",
                            actionLabel,
                            BUILD_TIMEOUT_SECONDS,
                            output),
                    finished,
                    is(true));
            if (process.exitValue() != 0 && isDependencyResolutionUnavailable(output)) {
                throw new TestAbortedException("Skipping " + actionLabel + " check: dependency resolution unavailable "
                                                       + "in this environment.\n" + output);
            }
            assertThat(
                    String.format("Generated project %s failed.%nOutput:%n%s", actionLabel, output),
                    process.exitValue(),
                    is(0));
        } finally {
            Files.deleteIfExists(outputFile);
            Files.deleteIfExists(settingsFile);
        }
    }

    static void assertGradleBuildSucceeds(Path projectDir) throws IOException, InterruptedException {
        if (System.getProperty(GRADLE_BUILD_TESTS_PROPERTY) == null) {
            throw new TestAbortedException("Skipping Gradle build check. Set -D"
                                                   + GRADLE_BUILD_TESTS_PROPERTY + "=true to enable.");
        }

        runGradle(projectDir, "Gradle build", "build", "-x", "test");
    }

    static void assertGradleTestSucceeds(Path projectDir) throws IOException, InterruptedException {
        if (System.getProperty(GRADLE_BUILD_TESTS_PROPERTY) == null) {
            throw new TestAbortedException("Skipping Gradle test check. Set -D"
                                                   + GRADLE_BUILD_TESTS_PROPERTY + "=true to enable.");
        }

        runGradle(projectDir, "Gradle test", "test");
    }

    private static void runGradle(Path projectDir, String actionLabel, String... tasks)
            throws IOException, InterruptedException {
        String gradleExecutable = isWindows() ? "gradle.bat" : "gradle";
        String[] command = new String[3 + tasks.length];
        command[0] = gradleExecutable;
        command[1] = "--no-daemon";
        command[2] = "-q";
        System.arraycopy(tasks, 0, command, 3, tasks.length);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectDir.toFile());
        processBuilder.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("generated-project-gradle-", ".log");
        processBuilder.redirectOutput(outputFile.toFile());
        try {
            Process process = processBuilder.start();

            boolean finished = process.waitFor(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertThat(
                    String.format("Generated project %s timed out after %s seconds.%nOutput:%n%s",
                            actionLabel,
                            BUILD_TIMEOUT_SECONDS, output),
                    finished,
                    is(true));
            if (process.exitValue() != 0 && isGradleDependencyResolutionUnavailable(output)) {
                throw new TestAbortedException("Skipping " + actionLabel
                                                       + " check: dependency resolution unavailable "
                                                       + "in this environment.\n" + output);
            }
            assertThat(
                    String.format("Generated project %s failed.%nOutput:%n%s", actionLabel, output),
                    process.exitValue(),
                    is(0));
        } catch (IOException e) {
            throw new TestAbortedException("Skipping " + actionLabel + " check: Gradle executable not available.", e);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    private static boolean isDependencyResolutionUnavailable(String output) {
        return output.contains("Unknown host")
                || output.contains("Could not transfer artifact")
                || output.contains("Non-resolvable parent POM")
                || output.contains("Could not read artifact descriptor");
    }

    private static boolean isGradleDependencyResolutionUnavailable(String output) {
        return output.contains("Unknown host")
                || output.contains("Could not resolve")
                || output.contains("Could not download")
                || output.contains("Temporary failure in name resolution");
    }
}
