/*
 * Copyright (c) 2021, 2026 Oracle and/or its affiliates.
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

package io.helidon.extensions.neo4j.examples.declarative;

import java.util.List;

import io.helidon.extensions.neo4j.examples.declarative.domain.Movie;
import io.helidon.extensions.neo4j.examples.declarative.domain.MovieRepository;
import io.helidon.http.Http;
import io.helidon.service.registry.Service;
import io.helidon.webserver.http.RestServer;

/**
 * The Movie service.
 */
@SuppressWarnings("deprecation")
@RestServer.Endpoint
@Http.Path("/api/movies")
class MovieService {

    private final MovieRepository movieRepository;

    /**
     * The movies service.
     *
     * @param movieRepository a movie repository.
     */
    @Service.Inject
    MovieService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    @Http.GET
    List<Movie> findMoviesHandler() {
        return this.movieRepository.findAll();
    }
}
