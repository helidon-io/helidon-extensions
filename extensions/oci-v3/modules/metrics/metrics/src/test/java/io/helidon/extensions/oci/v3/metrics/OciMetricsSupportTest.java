/*
 * Copyright (c) 2022, 2026 Oracle and/or its affiliates.
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
package io.helidon.extensions.oci.v3.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import io.helidon.metrics.api.Counter;
import io.helidon.metrics.api.MeterRegistry;
import io.helidon.service.registry.Services;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;

import com.oracle.bmc.monitoring.Monitoring;
import com.oracle.bmc.monitoring.model.MetricDataDetails;
import com.oracle.bmc.monitoring.model.PostMetricDataDetails;
import com.oracle.bmc.monitoring.requests.PostMetricDataRequest;
import com.oracle.bmc.monitoring.responses.PostMetricDataResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OciMetricsSupportTest {
    private static final Monitoring monitoringClient = mock(Monitoring.class);
    private static volatile Double[] testMetricUpdateCounterValue = new Double[2];
    private static volatile int testMetricCount = 0;
    private static int noOfExecutions;
    private static int noOfMetrics;
    private static String endPoint = "https://telemetry.DummyEndpoint.com";
    private static String postingEndPoint;
    private static final MeterRegistry meterRegistry = Services.get(MeterRegistry.class);

    @BeforeAll
    static void mockSetGetEndpoints() {
        doAnswer(invocation -> {
            endPoint = invocation.getArgument(0);
            return null;
        }).when(monitoringClient).setEndpoint(any());
        doAnswer(invocation -> {
            return endPoint;
        }).when(monitoringClient).getEndpoint();
    }

    @BeforeEach
    void resetState() {
        // clear all registry
        meterRegistry.meters()
                .forEach(meterRegistry::remove);

        endPoint = "https://telemetry.DummyEndpoint.com";
    }

    @Test
    void testMetricUpdate() throws InterruptedException {
        Counter counter = meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("DummyCounter"));

        CountDownLatch countDownLatch = new CountDownLatch(1);
        noOfExecutions = 0;

        doAnswer(invocationOnMock -> {
            noOfExecutions++;
            PostMetricDataRequest postMetricDataRequest = invocationOnMock.getArgument(0);
            PostMetricDataDetails postMetricDataDetails = postMetricDataRequest.getPostMetricDataDetails();
            List<MetricDataDetails> allMetricDataDetails = postMetricDataDetails.getMetricData();
            // put 1st result in testMetricUpdateCounterValue index 0 and succeeding update in index 1 to ensure
            // that the 1st update result does not overwrite the 2nd update in rare situations where all metric
            // updates have already completed before the process to assert results has even started
            testMetricUpdateCounterValue[noOfExecutions == 1 ? 0 : 1] =
                    allMetricDataDetails.get(0).getDatapoints().get(0).getValue();
            if (noOfExecutions == 1) {
                counter.increment();
            } else {
                // Give signal that multiple metric updates have been triggered
                countDownLatch.countDown();
            }
            return PostMetricDataResponse.builder()
                    .__httpStatusCode__(200)
                    .build();
        }).when(monitoringClient).postMetricData(any());

        OciMetricsSupport.Builder ociMetricsSupportBuilder = OciMetricsSupport.builder()
                .compartmentId("compartmentId")
                .namespace("namespace")
                .resourceGroup("resourceGroup")
                .initialDelay(1L)
                .delay(2L)
                .descriptionEnabled(false)
                .monitoringClient(monitoringClient)
                .enabled(true);

        HttpRouting.Builder routing = createRouting(ociMetricsSupportBuilder);

        counter.increment();
        WebServer webServer = createWebServer(routing);

        // Wait for metric updates to complete
        countDownLatchWait(countDownLatch);

        // Test the 1st and 2nd metric counter updates
        assertThat(ociMetricsSupportBuilder.enabled(), is(true));
        assertThat(testMetricUpdateCounterValue[0].intValue(), is(equalTo(1)));
        assertThat(testMetricUpdateCounterValue[1].intValue(), is(equalTo(2)));

        webServer.stop();
    }

    @Test
    void testEndpoint() throws InterruptedException {
        String originalEndPoint = endPoint;

        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("DummyCounter")).increment();

        CountDownLatch countDownLatch = new CountDownLatch(1);
        noOfExecutions = 0;

        doAnswer(invocationOnMock -> {
            PostMetricDataRequest postMetricDataRequest = invocationOnMock.getArgument(0);
            PostMetricDataDetails postMetricDataDetails = postMetricDataRequest.getPostMetricDataDetails();
            postingEndPoint = monitoringClient.getEndpoint();
            // Give signal that metrics has been posted
            countDownLatch.countDown();
            return PostMetricDataResponse.builder()
                    .__httpStatusCode__(200)
                    .build();
        }).when(monitoringClient).postMetricData(any());

        OciMetricsSupport.Builder ociMetricsSupportBuilder = OciMetricsSupport.builder()
                .compartmentId("compartmentId")
                .namespace("namespace")
                .resourceGroup("resourceGroup")
                .initialDelay(0L)
                .delay(20L)
                .descriptionEnabled(false)
                .monitoringClient(monitoringClient);

        HttpRouting.Builder routing = createRouting(ociMetricsSupportBuilder);

        WebServer webServer = createWebServer(routing);

        // Wait for metrics to be posted
        countDownLatchWait(countDownLatch);

        assertThat(ociMetricsSupportBuilder.enabled(), is(true));
        // Verify that telemetry-ingestion endpoint is properly set during postin
        assertThat(postingEndPoint, startsWith("https://telemetry-ingestion."));
        // In a span of 10 seconds, verify that original endpoint is restored after metric posting
        long start = System.currentTimeMillis();
        long end = start + 10 * 1000;
        boolean endPointIsRestored = false;
        while (System.currentTimeMillis() < end) {
            if (monitoringClient.getEndpoint().equals(originalEndPoint)) {
                endPointIsRestored = true;
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                fail("Failed with " + ie);
            }
        }
        assertThat(endPointIsRestored, is(true));
        // assertThat(monitoringClient.getEndpoint(), is(equalTo(originalEndPoint)));

        webServer.stop();
    }

    @Test
    void testBatchSize() throws InterruptedException {
        IntStream.rangeClosed(1, 10)
                .forEach(index -> meterRegistry.getOrCreate(meterRegistry.metricsFactory()
                                                                   .counterBuilder("DummyCounter" + index)).increment());

        // Should be 10 metrics
        int totalMetrics = meterRegistry.meters().size();

        int batchSize = 3;
        long batchDelay = 100L;
        // Should be 1
        int remainder = totalMetrics % batchSize;
        // Should be 4
        int noOfBatches = Math.round(totalMetrics / batchSize) + (remainder > 0 ? 1 : 0);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        noOfExecutions = 0;
        noOfMetrics = 0;

        doAnswer(invocationOnMock -> {
            noOfExecutions++;
            PostMetricDataRequest postMetricDataRequest = invocationOnMock.getArgument(0);
            PostMetricDataDetails postMetricDataDetails = postMetricDataRequest.getPostMetricDataDetails();
            int metricSizeToPost = postMetricDataDetails.getMetricData().size();
            noOfMetrics += metricSizeToPost;
            // Give signal that the last remaining metric in the last batch has been posted
            if (metricSizeToPost == remainder) {
                countDownLatch.countDown();
            }
            return PostMetricDataResponse.builder()
                    .__httpStatusCode__(200)
                    .build();
        }).when(monitoringClient).postMetricData(any());

        OciMetricsSupport.Builder ociMetricsSupportBuilder = OciMetricsSupport.builder()
                .compartmentId("compartmentId")
                .namespace("namespace")
                .resourceGroup("resourceGroup")
                .initialDelay(0L)
                .delay(20000L)
                .batchDelay(batchDelay)
                .schedulingTimeUnit(TimeUnit.MILLISECONDS)
                .descriptionEnabled(false)
                .batchSize(batchSize)
                .monitoringClient(monitoringClient);

        Instant start = Instant.now();

        HttpRouting.Builder routing = createRouting(ociMetricsSupportBuilder);

        WebServer webServer = createWebServer(routing);

        // Wait for last batch to be completed
        countDownLatchWait(countDownLatch);
        Instant finish = Instant.now();

        // Batch size of 3 for 10 metrics should yield 4 batches to post
        assertThat(totalMetrics, is(10));
        assertThat(noOfBatches, is(4));
        assertThat(noOfExecutions, is(noOfBatches));
        assertThat(noOfMetrics, is(totalMetrics));

        // Last batch is not delayed so compute only the delays for prior batches
        long estimatedTotalBatchDelay = batchDelay * (noOfBatches - 1);
        // Test total batch delay
        assertThat(Duration.between(start, finish).toMillis(), is(greaterThanOrEqualTo(estimatedTotalBatchDelay)));

        webServer.stop();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void testUnavailableGaugesWithTraceLogging(int validMetricCount) throws InterruptedException {
        CountDownLatch gaugeReads = new CountDownLatch(2);
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().gaugeBuilder("unavailable", () -> {
            gaugeReads.countDown();
            return Double.NaN;
        }));
        IntStream.range(0, validMetricCount)
                .forEach(index -> meterRegistry.getOrCreate(meterRegistry.metricsFactory()
                                                                   .counterBuilder("available" + index)).increment());

        Monitoring client = mock(Monitoring.class);
        when(client.getEndpoint()).thenReturn("https://telemetry.DummyEndpoint.com");
        var batches = new LinkedBlockingQueue<List<MetricDataDetails>>();
        doAnswer(invocation -> {
            PostMetricDataRequest request = invocation.getArgument(0);
            // Copy each batch before the exporter removes the posted metrics from its list.
            batches.add(List.copyOf(request.getPostMetricDataDetails().getMetricData()));
            return PostMetricDataResponse.builder().__httpStatusCode__(200).build();
        }).when(client).postMetricData(any());

        OciMetricsSupport support = OciMetricsSupport.builder()
                .compartmentId("compartmentId")
                .namespace("namespace")
                .initialDelay(0)
                .delay(50)
                .batchDelay(0)
                .batchSize(2)
                .schedulingTimeUnit(TimeUnit.MILLISECONDS)
                .monitoringClient(client)
                .build();

        Logger logger = Logger.getLogger(OciMetricsSupport.class.getName());
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.FINEST);
        try {
            assertThat("TRACE logging is enabled",
                       System.getLogger(OciMetricsSupport.class.getName()).isLoggable(System.Logger.Level.TRACE), is(true));
            support.beforeStart();
            countDownLatchWait(gaugeReads);
            if (validMetricCount == 0) {
                verify(client, never()).postMetricData(any());
            } else {
                int batchCount = (validMetricCount + 1) / 2;
                // Verify two export cycles so an unavailable sample cannot silently stop future exports.
                for (int cycle = 0; cycle < 2; cycle++) {
                    var exported = new ArrayList<MetricDataDetails>();
                    for (int batch = 0; batch < batchCount; batch++) {
                        List<MetricDataDetails> metrics = batches.poll(10, TimeUnit.SECONDS);
                        assertThat("Posted batch", metrics, notNullValue());
                        assertThat("Batch size", metrics, hasSize(Math.min(2, validMetricCount - batch * 2)));
                        exported.addAll(metrics);
                    }
                    assertThat("Exported metric names", exported.stream().map(MetricDataDetails::getName).sorted().toList(),
                               is(IntStream.range(0, validMetricCount).mapToObj(index -> "available" + index + "_counter")
                                          .toList()));
                    exported.forEach(metric -> assertThat("Exported value", metric.getDatapoints().getFirst().getValue(),
                                                          is(1.0)));
                }
            }
        } finally {
            support.afterStop();
            logger.setLevel(originalLevel);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "base", "vendor", "application", "base, vendor, application", "*", "custom"})
    void testLegacyConfigScopesAreIgnored(String scopes) throws InterruptedException {
        IntStream.rangeClosed(1, 6)
                .forEach(index -> meterRegistry.getOrCreate(meterRegistry.metricsFactory()
                                                                   .counterBuilder("DummyCounter" + index)).increment());

        validateMetricCount(scopes, 6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "base", "vendor", "application", "base,vendor,application", "*", "custom"})
    void testLegacyBuilderScopesAreIgnored(String scopes) throws InterruptedException {
        IntStream.rangeClosed(1, 6)
                .forEach(index -> meterRegistry.getOrCreate(meterRegistry.metricsFactory()
                                                                   .counterBuilder("DummyCounter" + index)).increment());

        validateMetricCount(scopes.isEmpty() ? new String[0] : scopes.split(","), 6);
    }

    @Test
    void testDisableMetrics() {
        meterRegistry.getOrCreate(meterRegistry.metricsFactory().counterBuilder("DummyCounter")).increment();

        OciMetricsSupport.Builder ociMetricsSupportBuilder = OciMetricsSupport.builder()
                .namespace("namespace")
                .compartmentId("compartmentId")
                .resourceGroup("resourceGroup")
                .initialDelay(50L)
                .delay(500L)
                .schedulingTimeUnit(TimeUnit.MILLISECONDS)
                .descriptionEnabled(false)
                .monitoringClient(monitoringClient)
                .enabled(false);

        testMetricCount = 0;
        HttpRouting.Builder routing = createRouting(ociMetricsSupportBuilder);
        WebServer webServer = createWebServer(routing);

        delay(1000L);

        webServer.stop();
        assertThat(ociMetricsSupportBuilder.enabled(), is(false));
        // metric count should remain 0 as metrics is disabled
        assertThat(testMetricCount, is(equalTo(0)));
    }

    private void mockPostMetricDataAndGetTestMetricCount(CountDownLatch countDownLatch) {
        // mock monitoringClient.postMetricData()
        doAnswer(invocationOnMock -> {
            PostMetricDataRequest postMetricDataRequest = invocationOnMock.getArgument(0);
            PostMetricDataDetails postMetricDataDetails = postMetricDataRequest.getPostMetricDataDetails();
            testMetricCount = postMetricDataDetails.getMetricData().size();
            // Give signal that testMetricCount was retrieved
            countDownLatch.countDown();
            return PostMetricDataResponse.builder()
                    .__httpStatusCode__(200)
                    .build();
        }).when(monitoringClient).postMetricData(any());
    }

    private WebServer createWebServer(HttpRouting.Builder routing) {
        WebServer webServer = WebServer.builder()
                .host("localhost")
                .addRouting(routing)
                .build();
        webServer.start();
        return webServer;
    }

    private HttpRouting.Builder createRouting(OciMetricsSupport.Builder ociMetricsSupportBuilder) {
        return HttpRouting.builder()
                .put("/test", (req, res) -> res.send())
                .register(ociMetricsSupportBuilder);
    }

    private void validateMetricCount(String scopesList, int expectedMetricCount) throws InterruptedException {
        Config config = Config.just(ConfigSources.create(Map.of(
                "compartmentId", "compartmentId",
                "namespace", "namespace",
                "resourceGroup", "resourceGroup",
                "initialDelay", "50",
                "delay", "15000",
                "schedulingTimeUnit", "milliseconds",
                "scopes", scopesList)
        ));
        OciMetricsSupport.Builder ociMetricsSupportBuilder = OciMetricsSupport.builder()
                .config(config)
                .monitoringClient(monitoringClient);

        validateMetricCount(ociMetricsSupportBuilder, expectedMetricCount);
    }

    @SuppressWarnings("removal")
    private void validateMetricCount(String[] scopes, int expectedMetricCount) throws InterruptedException {
        OciMetricsSupport.Builder ociMetricsSupportBuilder = OciMetricsSupport.builder()
                .namespace("namespace")
                .compartmentId("compartmentId")
                .resourceGroup("resourceGroup")
                .initialDelay(50L)
                .delay(2000L)
                .schedulingTimeUnit(TimeUnit.MILLISECONDS)
                .descriptionEnabled(false)
                .scopes(scopes)
                .monitoringClient(monitoringClient);

        validateMetricCount(ociMetricsSupportBuilder, expectedMetricCount);
    }

    private void validateMetricCount(OciMetricsSupport.Builder ociMetricsSupportBuilder, int expectedMetricCount)
            throws InterruptedException {
        testMetricCount = 0;
        CountDownLatch countDownLatch = new CountDownLatch(1);
        mockPostMetricDataAndGetTestMetricCount(countDownLatch);
        HttpRouting.Builder routing = createRouting(ociMetricsSupportBuilder);
        WebServer webServer = createWebServer(routing);

        try {
            // Wait for signal from metric update that testMetricCount has been retrieved
            countDownLatchWait(countDownLatch);
            assertThat("Number of exported unscoped counters", testMetricCount, is(expectedMetricCount));
        } finally {
            webServer.stop();
        }
    }

    private void countDownLatchWait(CountDownLatch countDownLatch) throws InterruptedException {
        if (!countDownLatch.await(10, TimeUnit.SECONDS)) {
            fail("CountDownLatch timed out");
        }
    }

    private void delay(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            fail("InterruptedException received in delay(" + millis + ") with message: " + ie.getMessage());
        }
    }
}
