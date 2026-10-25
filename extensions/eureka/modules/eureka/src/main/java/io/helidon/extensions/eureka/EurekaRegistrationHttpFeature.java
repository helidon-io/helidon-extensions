/*
 * Copyright (c) 2025, 2026 Oracle and/or its affiliates.
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
package io.helidon.extensions.eureka;

import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import io.helidon.http.Status;
import io.helidon.json.JsonNull;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonString;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientConfig;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpFeature;
import io.helidon.webserver.http.HttpRouting;

import static io.helidon.common.media.type.MediaTypes.APPLICATION_JSON;
import static io.helidon.http.HeaderNames.ACCEPT_ENCODING;
import static io.helidon.http.Status.Family.SUCCESSFUL;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;
import static java.lang.System.getLogger;
import static java.lang.Thread.sleep;

final class EurekaRegistrationHttpFeature implements HttpFeature {

    private static final Logger LOGGER = getLogger(EurekaRegistrationHttpFeature.class.getName());

    private static final JsonString UP = JsonString.create("UP");
    private static final JsonString DOWN = JsonString.create("DOWN");
    private static final JsonString STARTING = JsonString.create("STARTING");
    private static final JsonString OUT_OF_SERVICE = JsonString.create("OUT_OF_SERVICE");
    private static final JsonString UNKNOWN = JsonString.create("UNKNOWN");


    private final EurekaRegistrationConfig prototype;
    private final EurekaRegistrationState registration = new EurekaRegistrationState();
    private final ReentrantLock operationLock = new ReentrantLock();

    private volatile Thread renewer;
    private volatile Http1Client client;

    EurekaRegistrationHttpFeature(EurekaRegistrationConfig prototype) {
        super();
        this.prototype = Objects.requireNonNull(prototype, "prototype");
    }


    static JsonObject json(InstanceInfoConfig prototype, int actualPort, boolean tls) {
        var instance = JsonObject.builder();

        instance.set("instanceId", prototype.instanceId(actualPort));

        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L892
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/PropertiesInstanceConfig.java#L233-L236
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/PropertyBasedInstanceConfigConstants.java#L12
        // Native Eureka uppercases these identifiers when it builds InstanceInfo.
        instance.set("app", prototype.appName().toUpperCase(Locale.ROOT));

        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/PropertyBasedInstanceConfigConstants.java#L13
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/PropertiesInstanceConfig.java#L238-L241
        instance.set("appGroupName", prototype.appGroupName().toUpperCase(Locale.ROOT));

        instance.set("dataCenterInfo", JsonObject.builder()
                .set("name", "MyOwn") // ! yes really
                .set("@class", "com.netflix.appinfo.MyDataCenterInfo") // ! yes really
                .build());

        instance.set("ipAddr", prototype.ipAddr());

        instance.set("hostName", prototype.hostName());

        boolean portEnabled = prototype.portEnabled(tls);
        int port = prototype.port(actualPort, tls);
        instance.set("port", JsonObject.builder()
                .set("$", port)
                .set("@enabled", portEnabled)
                .build());

        boolean securePortEnabled = prototype.securePortEnabled(tls);
        int securePort = prototype.securePort(actualPort, tls);
        instance.set("securePort", JsonObject.builder()
                .set("$", securePort)
                .set("@enabled", securePortEnabled)
                .build());

        if (portEnabled) {
            instance.set("vipAddress",
                         prototype.vipAddress().orElseGet(() -> prototype.hostName() + ":" + port));
        }

        if (securePortEnabled) {
            instance.set("secureVipAddress",
                         prototype.secureVipAddress().orElseGet(() -> prototype.hostName() + ":" + securePort));
        }

        instance.set("homePageUrl",
                     prototype.homePageUrl()
                             .map(URI::toString)
                             .orElseGet(() -> "http://" + prototype.hostName() + ":"
                                     + port + prototype.homePageUrlPath()));

        instance.set("statusPageUrl",
                     prototype.statusPageUrl()
                             .map(URI::toString)
                             .orElseGet(() -> "http://" + prototype.hostName() + ":"
                                     + port
                                     + prototype.statusPageUrlPath()));

        prototype.asgName().ifPresent(asgName -> instance.set("asgName", asgName));

        instance.set("healthCheckUrl",
                     prototype.healthCheckUrl()
                             .map(URI::toString)
                             .orElseGet(() -> "http://" + prototype.hostName() + ":"
                                     + port
                                     + prototype.healthCheckUrlPath()));

        instance.set("secureHealthCheckUrl",
                     prototype.secureHealthCheckUrl()
                             .map(URI::toString)
                             .orElseGet(() -> "https://" + prototype.hostName() + ":"
                                     + securePort
                                     + prototype.healthCheckUrlPath()));

        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L98-L100
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L919-L929
        // But also:
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L228-L230
        instance.set("sid", "na");

        // countryId; cannot be anything other than 1; must be shipped in the payload, however, to ensure data integrity
        // on the server side
        instance.set("countryId", 1);

        // (The default value must be shipped with the payload to ensure data integrity on the server. Or so it seems?
        // There are places in the Eureka codebase where the field is dereferenced assuming it is not null; the JSON
        // marshalling can and absolutely will set it to null. Which is correct? it is hard to say.)
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L209
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L138
        instance.set("overriddenStatus", "UNKNOWN");

        long ts = System.currentTimeMillis();
        instance.set("lastUpdatedTimestamp", ts);
        instance.set("lastDirtyTimestamp", ts);

        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L137
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L316-L321
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/providers/EurekaConfigBasedInstanceInfoProvider.java#L104-L113
        // Eureka uses false as a default value, but I think true is better given that we are running in afterStart()
        instance.set("status", prototype.trafficEnabled() ? "UP" : "STARTING");

        Map<String, String> metadata = prototype.metadata();
        if (metadata.isEmpty()) {
            instance.set("metadata", JsonObject.empty());
        } else {
            var mb = JsonObject.builder();
            metadata.forEach(mb::set);
            instance.set("metadata", mb.build());
        }

        instance.set("leaseInfo", JsonObject.builder()
                .set("renewalIntervalInSecs", prototype.leaseInfo().renewalIntervalInSecs())
                .set("durationInSecs", prototype.leaseInfo().durationInSecs())
                .build());

        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L55
        return JsonObject.builder()
                .set("instance", instance.build())
                .build();
    }

    /**
     * Begins the process of registering the current microservice as a <dfn>Eureka service instance</dfn> in an
     * (external) Eureka server.
     *
     * @param webServer the {@link WebServer} that has successfully started; must not be {@code null}
     * @throws NullPointerException if {@code webServer} is {@code null}
     * @deprecated End users should not call this method.
     */
    @Deprecated // End users should not call this method
    @Override // HttpFeature (ServerLifecycle)
    public void afterStart(WebServer webServer) {
        if (webServer.isRunning()) {
            this.afterStart(webServer.port(), webServer.hasTls());
        }
    }

    /**
     * Unregisters the current microservice as an available <dfn>Eureka service instance</dfn> in an (external) Eureka
     * server.
     *
     * <p>This method deliberately has no effect after the first time it is invoked.</p>
     *
     * @deprecated End users should not call this method.
     */
    @Deprecated // End users should not call this method
    @Override // HttpFeature (ServerLifecycle)
    public void afterStop() {
        // Although users *should* not call this method, they might, from any thread. Proceed with caution.
        if (!this.registration.stop()) {
            return;
        }
        Thread renewer = this.renewer; // volatile read
        if (renewer != null) {
            renewer.interrupt();
        }
        // Drain startup or an in-flight renewal before unregistering and closing the client.
        this.operationLock.lock();
        try {
            this.cancelAndClose();
        } finally {
            this.operationLock.unlock();
        }
    }

    /**
     * Implements the {@link HttpFeature#setup(HttpRouting.Builder)} method by deliberately doing nothing.
     *
     * @param routingBuilder an {@link HttpRouting.Builder}; ignored
     * @deprecated End users should not call this method.
     */
    @Deprecated // End users should not call this method
    @Override // HttpFeature
    public void setup(HttpRouting.Builder routingBuilder) {
        // Nothing to do.
    }

    // for testing
    void afterStart(int actualPort, boolean tls) {
        this.operationLock.lock();
        try {
            this.start(actualPort, tls);
        } finally {
            this.operationLock.unlock();
        }
    }

    private void cancelAndClose() {
        var client = this.client;
        if (client == null) {
            // Registration never happened
            if (LOGGER.isLoggable(DEBUG)) {
                LOGGER.log(DEBUG,
                           "No cancellation necessary; registration never occurred");
            }
            return;
        }
        boolean canceled = false;
        RuntimeException e = null;
        try {
            canceled = this.cancel(client);
        } catch (RuntimeException e0) {
            e = e0;
        } finally {
            try {
                client.closeResource();
            } catch (RuntimeException e1) {
                if (e == null) {
                    e = e1;
                } else {
                    e.addSuppressed(e1);
                }
            }
            if (!canceled && LOGGER.isLoggable(WARNING)) {
                if (e == null) {
                    LOGGER.log(WARNING,
                               "Cancellation operation failed");
                } else {
                    LOGGER.log(WARNING,
                               "Cancellation operation failed", e);
                }
            }
        }
    }

    private void start(int actualPort, boolean tls) {
        if (this.registration.stopped()) {
            // Some other thread called this.afterStop() for some reason. This is technically a programming error, but
            // afterStop() is public, so there are many codepaths that might result in it being called for a variety of
            // reasons, some of which may? perhaps? be valid.
            if (LOGGER.isLoggable(WARNING)) {
                LOGGER.log(WARNING,
                           "Unexpected stop explicitly requested;"
                                   + " no attempt at registration will occur");
            }
            return;
        }
        Http1ClientConfig.Builder clientBuilder;
        try {
            clientBuilder = this.prototype.clientBuilderSupplier().get();
        } catch (RuntimeException e) {
            if (LOGGER.isLoggable(ERROR)) {
                LOGGER.log(ERROR,
                           "Getting a client builder failed"
                                   + " so no attempt at registration can or will occur",
                           e);
            }
            return;
        }
        if (clientBuilder == null) {
            // Should never happen but by no means should we crash.
            if (LOGGER.isLoggable(ERROR)) {
                LOGGER.log(ERROR,
                           "No " + Http1ClientConfig.Builder.class.getName() + " was supplied"
                                   + " so no attempt at registration can or will occur");
            }
            return;
        }
        if (clientBuilder.baseUri().isPresent()) {
            JsonObject instanceInfo = null;
            try {
                instanceInfo = json(this.prototype.instanceInfo(), actualPort, tls);
            } catch (RuntimeException e) {
                if (LOGGER.isLoggable(ERROR)) {
                    LOGGER.log(ERROR,
                               "Producing the initial JSON failed"
                                       + " so no attempt at registration can or will occur",
                               e);
                }
                return;
            }
            if (instanceInfo == null) {
                // Should never happen but by no means should we crash.
                if (LOGGER.isLoggable(ERROR)) {
                    LOGGER.log(ERROR,
                               "Producing the initial JSON resulted in null"
                                       + " so no attempt at registration can or will occur");
                }
                return;
            }
            var client = clientBuilder.sendExpectContinue(false) // Spring's version of Eureka server has trouble otherwise
                    .build();
            if (!this.registration.update(null, instanceInfo)) {
                client.closeResource();
                return;
            }
            this.client = client; // volatile write
            // Native Eureka registers the instance before it enters the renewal loop.
            this.register(instanceInfo);
            this.createAndStartRenewalLoop(instanceInfo);
        } else if (LOGGER.isLoggable(ERROR)) {
            LOGGER.log(ERROR,
                       "No baseUri was set on " + clientBuilder
                               + " so a Eureka Server cannot be contacted,"
                               + " so no attempt at registration will occur");
        }
    }

    // (Called only by the afterStop method.)
    private boolean cancel(Http1Client client) {
        if (!this.registration.stopped()) {
            // Programming error internal to this class. Truly an illegal state.
            throw new IllegalStateException();
        }
        // Keep payload conversion within cancelAndClose's cleanup boundary.
        JsonObject instanceInfo = json(this.registration.instanceInfo(), DOWN);
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L55
        JsonObject instance = instanceInfo.objectValue("instance", null);
        return this.cancel(client, instance.stringValue("app", null), instance.stringValue("instanceId", null));
    }

    // DELETE {baseUri}/v2/apps/{appName}/{id}
    private boolean cancel(Http1Client client, String appName, String id) {
        try (var response = client
                .delete("/v2/apps/" + Objects.requireNonNull(appName, "appName") + "/" + Objects.requireNonNull(id, "id"))
                .request()) {
            if (response.status().family() == SUCCESSFUL) {
                if (LOGGER.isLoggable(DEBUG)) {
                    LOGGER.log(DEBUG,
                               "DELETE /v2/apps/" + appName + "/" + id + ": " + response.status());
                }
                return true;
            } else if (LOGGER.isLoggable(WARNING)) {
                LOGGER.log(WARNING,
                           "DELETE /v2/apps/" + appName + "/" + id + ": " + response.status());
            }
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                if (LOGGER.isLoggable(WARNING)) {
                    LOGGER.log(WARNING,
                               "Eureka Server ("
                                       + this.client.prototype().baseUri().orElse(null) // volatile read
                                       + ") not reachable",
                               e);
                }
            } else {
                throw e;
            }
        }
        return false;
    }

    private void createAndStartRenewalLoop(JsonObject instanceInfo) {
        if (LOGGER.isLoggable(DEBUG)) {
            LOGGER.log(DEBUG,
                       "Creating and starting Eureka lease renewal loop");
        }

        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L55
        long sleepTimeInMilliSeconds = instanceInfo.objectValue("instance", null)
                .objectValue("leaseInfo", null)
                .intValue("renewalIntervalInSecs", 60) * 1000L;
        // Publish UP before the first heartbeat can capture the startup snapshot.
        this.up(instanceInfo);
        this.renewer = Thread.ofVirtual() // volatile write
                .name("Eureka lease renewer")
                .uncaughtExceptionHandler((t, e) -> {
                    if (LOGGER.isLoggable(ERROR)) {
                        LOGGER.log(ERROR, e.getMessage(), e);
                    }
                    // afterStop must still cancel the registration and close the client if renewal fails.
                })
                .start(() -> {
                    // Simplest possible heartbeat loop; nothing more complicated is needed.
                    while (!this.registration.stopped()) {
                        this.operationLock.lock();
                        try {
                            if (this.registration.stopped()) {
                                break;
                            }
                            JsonObject snapshot = this.registration.instanceInfo();
                            JsonObject renewed = this.renew(snapshot);
                            this.registration.update(snapshot, renewed);
                        } finally {
                            this.operationLock.unlock();
                        }
                        if (this.registration.stopped()) {
                            break;
                        }
                        try {
                            sleep(sleepTimeInMilliSeconds);
                        } catch (InterruptedException e) {
                            if (LOGGER.isLoggable(DEBUG)) {
                                LOGGER.log(DEBUG,
                                           "Eureka lease renewal loop interrupted",
                                           e);
                            }
                        }
                    }
                    if (LOGGER.isLoggable(DEBUG)) {
                        LOGGER.log(DEBUG,
                                   "Eureka lease renewal loop stopped");
                    }
                });
    }

    // PUT {baseUri}/v2/apps/{appName}/{id}?status={status}&lastDirtyTimestamp={lastDirtyTimestamp}
    //
    // (...&overriddenstatus={someOverriddenStatus} is recognized by the Eureka server, but never sent by Eureka's
    // registration client.)
    private Http1ClientResponse heartbeat(String appName,
                                          String id,
                                          String status,
                                          Long lastDirtyTimestamp) {
        var request = this.client // volatile read
                .put("/v2/apps/" + Objects.requireNonNull(appName, "appName") + "/" + Objects.requireNonNull(id, "id"))
                .accept(APPLICATION_JSON);
        if (status != null) {
            request.queryParam("status", status);
        }
        if (lastDirtyTimestamp != null) {
            request.queryParam("lastDirtyTimestamp", lastDirtyTimestamp.toString()); // yes, String-typed value
        }
        return request.request();
    }

    private void statusChange() {
        // For later, perhaps; Eureka's DiscoveryClient can be configured to notify the server "on demand"; not sure
        // whether we should as well
    }

    // POST {baseUri}/v2/apps/{payload.getJsonObject("instance").getString("app")}
    private boolean register(JsonObject payload) {
        if (payload == null || this.registration.stopped()) {
            return false;
        }
        try {
            try (var response = this.client // volatile read
                    .post("/v2/apps/" + payload.objectValue("instance", null).stringValue("app", null))
                    .accept(APPLICATION_JSON) // needed? native client has it, but throws any entity away
                    .contentType(APPLICATION_JSON)
                    .header(ACCEPT_ENCODING, "gzip")
                    .submit(payload)) {
                switch (response.status().code()) {
                case 200:
                    if (LOGGER.isLoggable(DEBUG)) {
                        if (response.entity().hasEntity()) {
                            LOGGER.log(DEBUG,
                                       "Registration succeeded: 200; " + response.entity().as(JsonObject.class));
                        }
                    }
                    return true;
                case 204:
                    return true;
                default:
                    if (response.status().family() == SUCCESSFUL) {
                        return true;
                    }
                    if (LOGGER.isLoggable(WARNING)) {
                        if (response.entity().hasEntity()) {
                            LOGGER.log(WARNING,
                                       "Registration failed: " + response.status()
                                               + "; " + response.entity().as(JsonObject.class).stringValue("error", null));
                        } else {
                            LOGGER.log(WARNING,
                                       "Registration failed: " + response.status());
                        }
                    }
                    return false;
                }
            }
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                if (LOGGER.isLoggable(WARNING)) {
                    LOGGER.log(WARNING,
                               "Eureka Server ("
                                       + this.client.prototype().baseUri().orElse(null) // volatile read
                                       + ") not reachable",
                               e);
                }
            } else {
                throw e;
            }
        }
        return false;
    }

    /**
     * Calls the {@link #heartbeat(String, String, String, Long)} method and handles its response appropriately.
     *
     * <p>This method is normally invoked in a loop every 30 seconds or so.</p>
     *
     * @return the {@link JsonObject} representing the service registration details, possibly amended to contain a
     *         different status and/or other attributes depending on what the server supplied; never {@code null}
     * @see #heartbeat(String, String, String, Long)
     */
    private JsonObject renew(JsonObject instanceInfo) {
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L55
        JsonObject instance = instanceInfo.objectValue("instance", null);
        try (var response =
                this.heartbeat(instance.stringValue("app", null),
                               instance.stringValue("instanceId", null),
                               instance.stringValue("status", null),
                               instance.numberValue("lastDirtyTimestamp")
                                       .map(BigDecimal::longValueExact)
                                       .orElse(null))) {
            switch (response.status()) {
            case Status s when s.family().equals(SUCCESSFUL):
                if (LOGGER.isLoggable(DEBUG)) {
                    LOGGER.log(DEBUG,
                               "Successfully renewed lease");
                }
                // See
                // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-core/src/main/java/com/netflix/eureka/resources/InstanceResource.java;
                // there is often no entity returned, presumably to indicate no changes.
                if (response.entity().hasEntity()) {
                    // this method can never return null, it would fail if the entity cannot be read
                    instanceInfo = response.entity().as(JsonObject.class);
                    if (LOGGER.isLoggable(DEBUG)) {
                        LOGGER.log(DEBUG,
                                   "New registration details received: " + instanceInfo);
                    }
                }
                break;
            case Status s when s.code() == 404:
                if (this.registration.stopped()) {
                    break;
                }
                // (Can't test for equality with Status.NOT_FOUND_404 equality because the reason is not (always?!) set
                // by the server.)
                //
                // Eureka's native machinery re-registers here.
                if (LOGGER.isLoggable(DEBUG)) {
                    LOGGER.log(DEBUG,
                               "Lease not found; reregistering");
                }
                instanceInfo = json(instanceInfo, System.currentTimeMillis());
                boolean registrationResult = this.register(instanceInfo);
                if (!registrationResult && LOGGER.isLoggable(DEBUG)) {
                    LOGGER.log(DEBUG,
                               "Reregistration failed");
                }
                break;
            default:
                if (LOGGER.isLoggable(WARNING)) {
                    LOGGER.log(WARNING,
                               "Heartbeat HTTP status: " + response.status());
                    if (response.entity().hasEntity()) {
                        LOGGER.log(WARNING,
                                   response.entity().as(JsonObject.class).stringValue("error", null));
                    }
                }
                break;
            }
        } catch (UncheckedIOException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException) {
                if (LOGGER.isLoggable(WARNING)) {
                    LOGGER.log(WARNING,
                               "Eureka Server ("
                                       + this.client.prototype().baseUri().orElse(null) // volatile read
                                       + ") not reachable",
                               e);
                }
            } else {
                throw e;
            }
        }
        return instanceInfo;
    }

    private boolean up(JsonObject oldInstanceInfo) {
        if (oldInstanceInfo == null) {
            return false;
        }
        JsonObject instanceInfo = json(oldInstanceInfo, UP);
        if (instanceInfo == oldInstanceInfo) {
            return false;
        }
        if (this.registration.update(oldInstanceInfo, instanceInfo)) {
            this.statusChange();
            return true;
        }
        return false;
    }

    private JsonObject json(JsonObject json, long lastDirtyTimestamp) {
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L55
        JsonObject instance = json.objectValue("instance", null);
        if (lastDirtyTimestamp <= instance.numberValue("lastDirtyTimestamp")
                .map(BigDecimal::longValueExact)
                .orElse(Long.MAX_VALUE)) {
            return json;
        }

        var b = JsonObject.builder();
        instance.keysAsStrings()
                .forEach(k -> {
                    if ("lastDirtyTimestamp".equals(k)) {
                        b.set(k, lastDirtyTimestamp);
                    } else {
                        b.set(k, instance.value(k, JsonNull.instance()));
                    }
                });
        return JsonObject.builder()
                .set("instance", b.build())
                .build();
    }


    /*
     * Static methods.
     */

    private JsonObject json(JsonObject json, JsonString status) {
        // https://github.com/Netflix/eureka/blob/v2.0.4/eureka-client/src/main/java/com/netflix/appinfo/InstanceInfo.java#L55
        JsonObject instance = json.objectValue("instance", null);
        if (Objects.requireNonNull(status, "status").value().equals(instance.stringValue("status", null))) {
            return json;
        }
        var b = JsonObject.builder();
        var b0 = JsonObject.builder();

        instance.keysAsStrings()
                .forEach(k -> {
                    switch (k) {
                    case "lastDirtyTimestamp" -> b0.set(k, System.currentTimeMillis());
                    case "status" -> b0.set(k, switch (status.value()) {
                        case "UP" -> UP;
                        case "DOWN" -> DOWN;
                        case "STARTING" -> STARTING;
                        case "OUT_OF_SERVICE" -> OUT_OF_SERVICE;
                        default -> UNKNOWN;
                    });
                    default -> b0.set(k, instance.value(k, null));
                    }
                });

        b.set("instance", b0.build());
        return b.build();
    }
}
