/*
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
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.laeubi.osgi.jdkhttp.demo.mphealth;

import org.apache.felix.framework.Felix;
import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionOptions;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckMetadata;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.laeubi.osgi.jdkhttp.impl.JdkHttpServerWhiteboard;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the {@code mp-health-jdk} demo: verifies that
 * {@link MicroProfileHealthHandler} exposes Apache Felix Health Check
 * results in the MicroProfile Health "Protocol and Wireformat" (JSON
 * payload + status code mapping + empty-response defaults).
 */
class MicroProfileHealthHandlerTest {

    private Felix felix;
    private BundleContext context;
    private JdkHttpServerWhiteboard whiteboard;
    private HttpClient http;
    private final List<ServiceRegistration<HealthCheck>> checkRegistrations = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("felix.log.level", "1");
        config.put("org.osgi.framework.storage", System.getProperty("java.io.tmpdir")
                + "/mp-health-demo-cache-" + System.nanoTime());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");

        felix = new Felix(config);
        felix.init();
        felix.start();
        context = felix.getBundleContext();

        whiteboard = new JdkHttpServerWhiteboard(context, "localhost", 0);
        whiteboard.start();

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (ServiceRegistration<HealthCheck> registration : checkRegistrations) {
            registration.unregister();
        }
        if (whiteboard != null) {
            whiteboard.stop();
        }
        if (felix != null) {
            felix.stop();
            felix.waitForStop(5_000);
        }
    }

    /** A (name, tag, status) triple describing one fake HealthCheck to register for a test. */
    private static final class CheckSpec {
        final String name;
        final String tag;
        final Result.Status status;

        CheckSpec(String name, String tag, Result.Status status) {
            this.name = name;
            this.tag = tag;
            this.status = status;
        }
    }

    /** Registers a real HealthCheck (so we get a real ServiceReference/HealthCheckMetadata) and starts a handler backed by a fake executor. */
    private MicroProfileHealthHandler startHandler(MicroProfileHealthConfiguration cfg, CheckSpec... specs) throws Exception {
        List<HealthCheckExecutionResult> executionResults = new ArrayList<>();
        for (CheckSpec spec : specs) {
            Dictionary<String, Object> hcProps = new Hashtable<>();
            hcProps.put(HealthCheck.NAME, spec.name);
            hcProps.put(HealthCheck.TAGS, new String[] { spec.tag });
            Result result = new Result(spec.status, "check message");
            ServiceRegistration<HealthCheck> registration = context.registerService(HealthCheck.class, () -> result, hcProps);
            checkRegistrations.add(registration);

            HealthCheckMetadata metadata = new HealthCheckMetadata(registration.getReference());
            executionResults.add(executionResult(result, metadata));
        }

        MicroProfileHealthHandler handler = new MicroProfileHealthHandler();
        handler.healthCheckExecutor = fakeExecutor(executionResults);
        handler.activate(cfg, context);
        return handler;
    }

    @Test
    void livenessDefaultsToUpWhenEmpty() throws Exception {
        startHandler(configOf(MicroProfileHealthConfiguration.class));
        HttpResponse<String> response = get("/health/live");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
        assertTrue(response.body().contains("\"checks\":[]"));
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void readinessDefaultsToDownWhenEmpty() throws Exception {
        startHandler(configOf(MicroProfileHealthConfiguration.class));
        HttpResponse<String> response = get("/health/ready");
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"DOWN\""));
    }

    @Test
    void readinessEmptyDefaultIsConfigurable() throws Exception {
        startHandler(configOf(MicroProfileHealthConfiguration.class, "defaultReadinessEmptyResponse", "UP"));
        HttpResponse<String> response = get("/health/ready");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }

    @Test
    void registeredPassingCheckReportsUp() throws Exception {
        startHandler(configOf(MicroProfileHealthConfiguration.class), new CheckSpec("Demo Check", "live", Result.Status.OK));
        HttpResponse<String> response = get("/health/live");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""));
        assertTrue(response.body().contains("\"name\":\"Demo Check\""));
    }

    @Test
    void registeredFailingCheckReportsDown() throws Exception {
        startHandler(configOf(MicroProfileHealthConfiguration.class), new CheckSpec("Failing Check", "ready", Result.Status.CRITICAL));
        HttpResponse<String> response = get("/health/ready");
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"DOWN\""));
        assertTrue(response.body().contains("\"name\":\"Failing Check\""));
    }

    @Test
    void combinedEndpointAggregatesAllKinds() throws Exception {
        startHandler(configOf(MicroProfileHealthConfiguration.class),
                new CheckSpec("Live Check", "live", Result.Status.OK),
                new CheckSpec("Ready Check", "ready", Result.Status.CRITICAL));
        HttpResponse<String> response = get("/health");
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"DOWN\""));
        assertTrue(response.body().contains("\"name\":\"Live Check\""));
        assertTrue(response.body().contains("\"name\":\"Ready Check\""));
    }

    // -- helpers -------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + whiteboard.getPort() + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** A fake executor that filters the fixed execution result list by the selector's tags, mimicking real Felix behavior. */
    private static HealthCheckExecutor fakeExecutor(List<HealthCheckExecutionResult> all) {
        return new HealthCheckExecutor() {
            @Override
            public List<HealthCheckExecutionResult> execute(HealthCheckSelector selector) {
                return execute(selector, new HealthCheckExecutionOptions());
            }

            @Override
            public List<HealthCheckExecutionResult> execute(HealthCheckSelector selector, HealthCheckExecutionOptions options) {
                String[] tags = selector.tags();
                if (tags == null || tags.length == 0) {
                    return all;
                }
                List<String> tagList = Arrays.asList(tags);
                List<HealthCheckExecutionResult> filtered = new ArrayList<>();
                for (HealthCheckExecutionResult result : all) {
                    for (String tag : result.getHealthCheckMetadata().getTags()) {
                        if (tagList.contains(tag)) {
                            filtered.add(result);
                            break;
                        }
                    }
                }
                return filtered;
            }
        };
    }

    private static HealthCheckExecutionResult executionResult(Result result, HealthCheckMetadata metadata) {
        return new HealthCheckExecutionResult() {
            @Override
            public Result getHealthCheckResult() {
                return result;
            }

            @Override
            public long getElapsedTimeInMs() {
                return 1;
            }

            @Override
            public Date getFinishedAt() {
                return new Date();
            }

            @Override
            public boolean hasTimedOut() {
                return false;
            }

            @Override
            public HealthCheckMetadata getHealthCheckMetadata() {
                return metadata;
            }
        };
    }

    /** Builds a proxy instance of an annotation-based OSGi component configuration type, returning its declared defaults, with overrides. */
    @SuppressWarnings("unchecked")
    private static <T> T configOf(Class<T> type, Object... overrides) {
        Map<String, Object> overrideMap = new HashMap<>();
        for (int i = 0; i < overrides.length; i += 2) {
            overrideMap.put((String) overrides[i], overrides[i + 1]);
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (overrideMap.containsKey(method.getName())) {
                return overrideMap.get(method.getName());
            }
            return method.getDefaultValue();
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
