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

import java.util.ArrayList;
import java.util.List;

import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;

/**
 * Minimal CLI entry point for the Helidon SE declarative generator.
 *
 * <p>Usage:</p>
 * <pre>
 *   java -jar target/helidon-declarative-generator-1.0-SNAPSHOT.jar generate \
 *     -g helidon-declarative \
 *     -i /path/to/spec.yaml \
 *     -o /path/to/output \
 *     [--additional-properties key=value,...]
 * </pre>
 * <p>Runtime dependencies are copied to {@code target/libs} during packaging.</p>
 */
public class GeneratorCli {

    private GeneratorCli() {
    }

    /**
     * Generates sources from an OpenAPI specification.
     *
     * @param args CLI arguments
     */
    public static void main(String[] args) {
        // Only "generate" command is supported
        if (args.length == 0 || !"generate".equals(args[0])) {
            System.err.println("Usage: generate -g <generatorName> -i <spec> -o <output> [--additional-properties k=v,...]");
            System.exit(1);
        }

        String generatorName = null;
        String inputSpec = null;
        String outputDir = null;
        List<String[]> additionalProps = new ArrayList<>();

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-g" -> {
                    i++;
                    generatorName = args[i];
                }
                case "-i" -> {
                    i++;
                    inputSpec = args[i];
                }
                case "-o" -> {
                    i++;
                    outputDir = args[i];
                }
                case "--additional-properties" -> {
                    i++;
                    for (String kv : args[i].split(",")) {
                        String[] pair = kv.split("=", 2);
                        if (pair.length == 2) additionalProps.add(pair);
                    }
                }
                default -> System.err.println("Ignoring unknown arg: " + arg);
            }
        }

        if (generatorName == null || inputSpec == null || outputDir == null) {
            System.err.println("Missing required arguments: -g, -i, -o");
            System.exit(1);
        }

        CodegenConfigurator configurator = new CodegenConfigurator()
                .setGeneratorName(generatorName)
                .setInputSpec(inputSpec)
                .setOutputDir(outputDir);
        for (String[] kv : additionalProps) {
            configurator.addAdditionalProperty(kv[0], kv[1]);
        }

        new DefaultGenerator().opts(configurator.toClientOptInput()).generate();
    }
}
