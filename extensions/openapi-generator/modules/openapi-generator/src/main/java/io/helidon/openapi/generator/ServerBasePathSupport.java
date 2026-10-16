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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.servers.Server;
import org.openapitools.codegen.CodegenOperation;

/**
 * Resolves route prefixes and keeps published servers consistent with configured path and variable overrides.
 */
final class ServerBasePathSupport {
    private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

    /**
     * Prevents instantiation of this stateless support class.
     */
    private ServerBasePathSupport() {
    }

    /**
     * Extracts the first server's path after substituting server variables.
     * Relative URLs retain every path segment instead of interpreting the first segment as a host.
     *
     * @param servers effective server declarations
     * @param overrides server variable overrides
     * @return normalized route prefix, or {@code /} when no prefix is declared
     */
    static String path(List<Server> servers, Map<String, String> overrides) {
        if (servers == null || servers.isEmpty()) {
            return "/";
        }
        String path = URI.create(expand(servers.get(0), overrides)).getRawPath();
        return normalize(path == null ? "" : path);
    }

    /**
     * Resolves operation servers before path-item servers, inheriting the root prefix when neither is declared.
     *
     * @param operation source operation
     * @param servers path-item servers, or {@code null} when inherited
     * @param overrides configured server variable values
     * @param rootPrefix effective top-level server prefix
     * @return normalized prefix for the operation
     */
    static String operationPrefix(Operation operation, List<Server> servers, Map<String, String> overrides,
                                  String rootPrefix) {
        List<Server> scopedServers = operation.getServers() == null || operation.getServers().isEmpty()
                ? servers : operation.getServers();
        return scopedServers == null || scopedServers.isEmpty() ? rootPrefix : path(scopedServers, overrides);
    }

    /**
     * Reads the route resolved before tag grouping, retaining the original path for operations created externally.
     *
     * @param operation generated operation
     * @return effective operation route
     */
    static String endpointPath(CodegenOperation operation) {
        return operation.vendorExtensions.getOrDefault("x-helidon-operation-path", operation.path).toString();
    }

    /**
     * Find the longest common path prefix shared by all operations in a tag group.
     * Stops before any path-parameter segment ({...}).
     *
     * @param ops operations with their resolved server prefixes
     * @return common route prefix, or {@code /} when no literal prefix is shared
     */
    static String commonPath(List<CodegenOperation> ops) {
        if (ops.isEmpty()) return "/";

        String first = endpointPath(ops.get(0));
        String[] firstParts = first.split("/", -1);

        // Find how many leading segments all operations share
        int commonSegments = firstParts.length;
        for (CodegenOperation op : ops) {
            String[] parts = endpointPath(op).split("/", -1);
            int match = 0;
            for (int i = 0; i < Math.min(commonSegments, parts.length); i++) {
                if (firstParts[i].equals(parts[i])) {
                    match = i + 1;
                } else {
                    break;
                }
            }
            commonSegments = Math.min(commonSegments, match);
        }

        // Build the common path, stopping before any path-parameter segments
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonSegments; i++) {
            if (firstParts[i].isEmpty()) continue;      // skip the leading ""
            if (firstParts[i].startsWith("{")) break;   // stop at path params
            sb.append("/").append(firstParts[i]);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    /**
     * Gives configured and derived paths the same leading-slash and trailing-slash semantics.
     *
     * @param path route prefix
     * @return normalized absolute path, with {@code /} representing the root
     */
    static String normalize(String path) {
        String prefix = path.replaceAll("^/+|/+$", "");
        String normalized = URI.create("/" + prefix).normalize().getRawPath();
        while (normalized.startsWith("/../")) {
            normalized = normalized.substring(3);
        }
        if (normalized.equals("/..")) {
            return "/";
        }
        String trimmed = normalized.replaceAll("/+$", "");
        return trimmed.isEmpty() ? "/" : trimmed;
    }

    /**
     * Copies the specification and applies configured variables and an optional path override at every server scope.
     * Server origins, query strings, fragments, descriptions, and remaining variables are preserved.
     *
     * @param source caller-owned specification
     * @param path normalized override, or empty to retain declared paths
     * @param overrides server variable overrides
     * @return independent document suitable for publication
     */
    static OpenAPI publishedDocument(OpenAPI source, String path, Map<String, String> overrides) {
        OpenAPI published = Yaml.mapper().convertValue(source, OpenAPI.class);
        if (published.getServers() == null || published.getServers().isEmpty()) {
            if (!path.isEmpty()) {
                published.setServers(List.of(new Server().url(path)));
            }
        } else {
            updateServers(published.getServers(), path, overrides);
        }
        if (published.getPaths() != null) {
            published.getPaths().values().forEach(item -> {
                updateServers(item.getServers(), path, overrides);
                item.readOperations().forEach(operation -> updateServers(operation.getServers(), path, overrides));
            });
        }
        return published;
    }

    /**
     * Updates the publication copy while retaining metadata unrelated to configured overrides.
     *
     * @param servers locally declared servers, or {@code null} when inherited
     * @param path normalized override, or empty to retain declared paths
     * @param overrides server variable overrides
     */
    private static void updateServers(List<Server> servers, String path, Map<String, String> overrides) {
        if (servers == null) {
            return;
        }
        for (Server server : servers) {
            if (server.getVariables() != null) {
                overrides.forEach((name, value) -> {
                    if (server.getVariables().containsKey(name)) {
                        server.getVariables().get(name).setDefault(value);
                    }
                });
            }
            if (path.isEmpty()) {
                continue;
            }
            String url = server.getUrl();
            int suffix = suffixStart(url);
            String address = suffix < 0 ? url : url.substring(0, suffix);
            if (!address.contains("://") && !address.startsWith("//")) {
                // A variable can supply the entire origin rather than just a host or path segment.
                String expanded = expand(server, overrides);
                if (URI.create(expanded).getRawAuthority() != null) {
                    url = expanded;
                }
            }
            server.setUrl(replacePath(url, path));
            if (server.getVariables() != null) {
                server.getVariables().keySet().removeIf(name -> !server.getUrl().contains("{" + name + "}"));
                if (server.getVariables().isEmpty()) {
                    server.setVariables(null);
                }
            }
        }
    }

    /**
     * Resolves URL template variables using configured values before declared defaults.
     *
     * @param server server declaration containing variable definitions
     * @param overrides configured server variable values
     * @return expanded URL
     */
    private static String expand(Server server, Map<String, String> overrides) {
        return VARIABLE.matcher(server.getUrl()).replaceAll(match -> {
            String name = match.group(1);
            var variable = server.getVariables() == null ? null : server.getVariables().get(name);
            String value = overrides.get(name);
            if (value == null && variable != null) {
                value = variable.getDefault();
            }
            if (value == null) {
                throw new IllegalArgumentException("No value for server variable '" + name + "'");
            }
            return Matcher.quoteReplacement(value);
        });
    }

    /**
     * Locates the query or fragment boundary without interpreting unresolved URL variables.
     *
     * @param url server URL template
     * @return first suffix index, or {@code -1} when neither component is present
     */
    private static int suffixStart(String url) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        return query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
    }

    /**
     * Replaces a URL path using component boundaries, retaining template variables in its origin and suffix.
     *
     * @param url server URL, which may be relative or contain unresolved variables
     * @param path normalized override
     * @return server URL with the replaced path
     */
    private static String replacePath(String url, String path) {
        int suffix = suffixStart(url);
        String address = suffix < 0 ? url : url.substring(0, suffix);
        int scheme = address.indexOf("://");
        int authority;
        if (address.startsWith("//")) {
            authority = 2;
        } else if (scheme > 0 && address.substring(0, scheme).indexOf('/') < 0) {
            authority = scheme + 3;
        } else {
            authority = -1;
        }
        int pathStart = authority < 0 ? 0 : address.indexOf('/', authority);
        String origin = pathStart < 0 ? address : address.substring(0, pathStart);
        return origin + path + (suffix < 0 ? "" : url.substring(suffix));
    }
}
