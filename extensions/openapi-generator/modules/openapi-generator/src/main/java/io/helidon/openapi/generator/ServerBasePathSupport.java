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
import io.swagger.v3.oas.models.servers.Server;

/**
 * Resolves route prefixes and keeps published server URLs consistent with an explicit prefix override.
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
     * @param openAPI source specification
     * @param overrides server variable overrides
     * @return normalized route prefix, or {@code /} when no prefix is declared
     */
    static String path(OpenAPI openAPI, Map<String, String> overrides) {
        if (openAPI.getServers() == null || openAPI.getServers().isEmpty()) {
            return "/";
        }
        Server server = openAPI.getServers().get(0);
        String expanded = VARIABLE.matcher(server.getUrl()).replaceAll(match -> {
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
        String path = URI.create(expanded).getRawPath();
        return normalize(path == null ? "" : path);
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
     * Copies the specification and replaces every locally declared server path with the effective prefix.
     * Server origins, query strings, fragments, descriptions, and remaining variables are preserved.
     *
     * @param source caller-owned specification
     * @param path normalized override
     * @return independent document suitable for publication
     */
    static OpenAPI publishedDocument(OpenAPI source, String path) {
        OpenAPI published = Yaml.mapper().convertValue(source, OpenAPI.class);
        if (published.getServers() == null || published.getServers().isEmpty()) {
            published.setServers(List.of(new Server().url(path)));
        } else {
            replacePaths(published.getServers(), path);
        }
        if (published.getPaths() != null) {
            published.getPaths().values().forEach(item -> {
                replacePaths(item.getServers(), path);
                item.readOperations().forEach(operation -> replacePaths(operation.getServers(), path));
            });
        }
        return published;
    }

    /**
     * Applies the override to a server list while retaining metadata unrelated to its replaced path.
     *
     * @param servers locally declared servers, or {@code null} when inherited
     * @param path normalized override
     */
    private static void replacePaths(List<Server> servers, String path) {
        if (servers == null) {
            return;
        }
        for (Server server : servers) {
            server.setUrl(replacePath(server.getUrl(), path));
            if (server.getVariables() != null) {
                server.getVariables().keySet().removeIf(name -> !server.getUrl().contains("{" + name + "}"));
                if (server.getVariables().isEmpty()) {
                    server.setVariables(null);
                }
            }
        }
    }

    /**
     * Replaces a URL path using component boundaries, retaining template variables in its origin and suffix.
     *
     * @param url server URL, which may be relative or contain unresolved variables
     * @param path normalized override
     * @return server URL with the replaced path
     */
    private static String replacePath(String url, String path) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int suffix = query < 0 ? fragment : fragment < 0 ? query : Math.min(query, fragment);
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
