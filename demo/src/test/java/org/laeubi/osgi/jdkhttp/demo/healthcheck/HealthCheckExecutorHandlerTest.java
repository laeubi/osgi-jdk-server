/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.laeubi.osgi.jdkhttp.demo.healthcheck;

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
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultHtmlSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultHtmlSerializerConfiguration;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultJsonSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultTxtSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultTxtVerboseSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultTxtVerboseSerializerConfiguration;
import org.laeubi.osgi.jdkhttp.impl.JdkHttpServerWhiteboard;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for the {@code felix-healthcheck-jdk} demo: verifies that
 * {@link HealthCheckExecutorHandler} - the JDK HttpServer based port of
 * Apache Felix Health Check's {@code HealthCheckExecutorServlet} - correctly
 * serves health check results in all supported formats.
 */
class HealthCheckExecutorHandlerTest {

    private Felix felix;
    private BundleContext context;
    private JdkHttpServerWhiteboard whiteboard;
    private HttpClient http;
    private ServiceRegistration<HealthCheck> checkRegistration;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("felix.log.level", "1");
        config.put("org.osgi.framework.storage", System.getProperty("java.io.tmpdir")
                + "/felix-hc-demo-cache-" + System.nanoTime());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");

        felix = new Felix(config);
        felix.init();
        felix.start();
        context = felix.getBundleContext();

        whiteboard = new JdkHttpServerWhiteboard(context, "localhost", 0);
        whiteboard.start();

        // register a real HealthCheck service so we get a real ServiceReference / HealthCheckMetadata
        Dictionary<String, Object> hcProps = new Hashtable<>();
        hcProps.put(HealthCheck.NAME, "Demo Health Check");
        hcProps.put(HealthCheck.TAGS, new String[] { "demo" });
        checkRegistration = context.registerService(HealthCheck.class, () -> {
            FormattingResultLog log = new FormattingResultLog();
            log.info("all good");
            return new Result(log);
        }, hcProps);

        HealthCheckExecutorHandler handler = new HealthCheckExecutorHandler();
        handler.healthCheckExecutor = fakeExecutor(checkRegistration.getReference());
        handler.htmlSerializer = new ResultHtmlSerializer();
        activate(handler.htmlSerializer, configOf(ResultHtmlSerializerConfiguration.class));
        handler.jsonSerializer = new ResultJsonSerializer();
        handler.txtSerializer = new ResultTxtSerializer();
        handler.verboseTxtSerializer = new ResultTxtVerboseSerializer();
        activate(handler.verboseTxtSerializer, configOf(ResultTxtVerboseSerializerConfiguration.class));

        handler.activate(configOf(HealthCheckHandlerConfiguration.class), context);

        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (checkRegistration != null) {
            checkRegistration.unregister();
        }
        if (whiteboard != null) {
            whiteboard.stop();
        }
        if (felix != null) {
            felix.stop();
            felix.waitForStop(5_000);
        }
    }

    @Test
    void defaultFormatIsHtml() throws Exception {
        HttpResponse<String> response = get("/system/health");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("System Health"));
        assertTrue(response.body().contains("Demo Health Check"));
    }

    @Test
    void jsonFormatViaExtension() throws Exception {
        HttpResponse<String> response = get("/system/health.json");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"overallResult\""));
        assertTrue(response.body().contains("Demo Health Check"));
    }

    @Test
    void txtFormatViaExtension() throws Exception {
        HttpResponse<String> response = get("/system/health.txt");
        assertEquals(200, response.statusCode());
        assertEquals("OK", response.body().trim());
    }

    @Test
    void verboseTxtFormatViaExtension() throws Exception {
        HttpResponse<String> response = get("/system/health.verbose.txt");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Overall Health Result: OK"));
        assertTrue(response.body().contains("Demo Health Check"));
    }

    @Test
    void formatViaQueryParameter() throws Exception {
        HttpResponse<String> response = get("/system/health?format=json");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"overallResult\""));
    }

    @Test
    void tagSelectionViaPath() throws Exception {
        HttpResponse<String> response = get("/system/health/demo.json");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Demo Health Check"));
    }

    @Test
    void xHealthHeaderIsSet() throws Exception {
        HttpResponse<String> response = get("/system/health.txt");
        assertEquals("OK", response.headers().firstValue("X-Health").orElse(null));
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

    private static HealthCheckExecutor fakeExecutor(ServiceReference<HealthCheck> ref) {
        HealthCheckMetadata metadata = new HealthCheckMetadata(ref);
        return new HealthCheckExecutor() {
            @Override
            public List<HealthCheckExecutionResult> execute(HealthCheckSelector selector) {
                return execute(selector, new HealthCheckExecutionOptions());
            }

            @Override
            public List<HealthCheckExecutionResult> execute(HealthCheckSelector selector, HealthCheckExecutionOptions options) {
                FormattingResultLog log = new FormattingResultLog();
                log.info("all good");
                Result result = new Result(log);
                return Collections.singletonList(new HealthCheckExecutionResult() {
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
                });
            }
        };
    }

    /** Builds a proxy instance of an annotation-based OSGi component configuration type, returning its declared defaults. */
    @SuppressWarnings("unchecked")
    private static <T> T configOf(Class<T> type) {
        InvocationHandler handler = (proxy, method, args) -> method.getDefaultValue();
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    /** Invokes the (protected) {@code activate(configType)} lifecycle method reflectively, as SCR would at runtime. */
    private static void activate(Object component, Object config) throws Exception {
        for (Method method : component.getClass().getDeclaredMethods()) {
            if (method.getName().equals("activate") && method.getParameterCount() == 1) {
                method.setAccessible(true);
                method.invoke(component, config);
                return;
            }
        }
        throw new IllegalStateException("No activate(config) method found on " + component.getClass());
    }
}
