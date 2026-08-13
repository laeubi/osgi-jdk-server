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

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.ResultLog;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.apache.felix.utils.json.JSONWriter;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges Apache Felix Health Check ({@link HealthCheckExecutor}) to a
 * <a href="https://download.eclipse.org/microprofile/microprofile-health-4.0/microprofile-health-spec-4.0.html">
 * MicroProfile Health</a> compatible HTTP endpoint on top of the OSGi JDK
 * HttpServer Whiteboard.
 * <p>
 * From the "Protocol and Wireformat" chapter of the MicroProfile Health
 * specification, this handler implements:
 * <ul>
 * <li>Appendix A - the four REST endpoints {@code /health/live},
 * {@code /health/ready}, {@code /health/started} and the combined
 * {@code /health}, all {@code GET}, mapped to HTTP 200 (UP), 503 (DOWN) or
 * 500 (the request/procedure itself failed).
 * <li>Appendix B - the JSON payload {@code {"status": "UP"|"DOWN", "checks":
 * [{"name": ..., "status": ..., "data": {...}}]}}.
 * <li>the "logical conjunction" default policy: the overall status is
 * {@code UP} only if every individual check is {@code UP}.
 * <li>the empty-response rules: liveness with no procedures always reports
 * {@code UP}; readiness/startup with no procedures report the configurable
 * {@code mp.health.default.*.empty.response}-equivalent default (see
 * {@link MicroProfileHealthConfiguration}).
 * </ul>
 * Felix Health Check has no concept of liveness/readiness/startup - a
 * {@link org.apache.felix.hc.api.HealthCheck} carrying the configured
 * {@code livenessTag}/{@code readinessTag}/{@code startupTag} (see
 * {@link MicroProfileHealthConfiguration}) is included in the matching
 * MicroProfile probe. Felix's richer {@link Result.Status} (OK, WARN,
 * TEMPORARILY_UNAVAILABLE, CRITICAL, HEALTH_CHECK_ERROR) is collapsed to the
 * MicroProfile boolean UP/DOWN model: only {@code OK} maps to {@code UP},
 * everything else is {@code DOWN}.
 */
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = MicroProfileHealthConfiguration.class, factory = true)
public class MicroProfileHealthHandler implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MicroProfileHealthHandler.class);

    static final String STATUS_UP = MicroProfileHealthConfiguration.STATUS_UP;
    static final String STATUS_DOWN = MicroProfileHealthConfiguration.STATUS_DOWN;

    private static final String CONTENT_TYPE_JSON = "application/json";

    /** Which MicroProfile probe kind a given registered context path serves. */
    private enum Probe {
        LIVE, READY, STARTED, ALL
    }

    @Reference
    HealthCheckExecutor healthCheckExecutor;

    private BundleContext bundleContext;
    private String contextPath;
    private String livenessTag;
    private String readinessTag;
    private String startupTag;
    private String defaultReadinessEmptyResponse;
    private String defaultStartupEmptyResponse;

    /** context path (as returned by {@code exchange.getHttpContext().getPath()}) → probe kind. */
    private final Map<String, Probe> pathToProbe = new HashMap<>();

    private final List<ServiceRegistration<HttpHandler>> registrations = new ArrayList<>();

    @Activate
    protected final void activate(final MicroProfileHealthConfiguration configuration, final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.contextPath = configuration.contextPath();
        this.livenessTag = configuration.livenessTag();
        this.readinessTag = configuration.readinessTag();
        this.startupTag = configuration.startupTag();
        this.defaultReadinessEmptyResponse = configuration.defaultReadinessEmptyResponse();
        this.defaultStartupEmptyResponse = configuration.defaultStartupEmptyResponse();

        if (configuration.disabled()) {
            LOG.info("MicroProfile Health bridge is disabled by configuration");
            return;
        }

        LOG.info("MicroProfile Health bridge: contextPath={}, livenessTag={}, readinessTag={}, startupTag={}",
                contextPath, livenessTag, readinessTag, startupTag);

        pathToProbe.put(contextPath, Probe.ALL);
        pathToProbe.put(contextPath + "/live", Probe.LIVE);
        pathToProbe.put(contextPath + "/ready", Probe.READY);
        pathToProbe.put(contextPath + "/started", Probe.STARTED);

        for (String path : pathToProbe.keySet()) {
            register(path);
        }
    }

    @Deactivate
    protected void deactivate() {
        for (ServiceRegistration<HttpHandler> registration : registrations) {
            try {
                registration.unregister();
            } catch (Exception e) {
                // ignore - might happen on shutdown
            }
        }
        registrations.clear();
    }

    private void register(final String path) {
        final Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, path);
        LOG.info("Registering MicroProfile Health endpoint at '{}'", path);
        registrations.add(bundleContext.registerService(HttpHandler.class, this, properties));
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Probe probe = pathToProbe.get(exchange.getHttpContext().getPath());
            ProbeResult result = executeProbe(probe);
            sendJson(exchange, result);
        } catch (Exception e) {
            LOG.error("Health check request processing failed: " + e, e);
            // Appendix A: 500 - the producer wasn't able to process the health check request. No JSON payload required.
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    private ProbeResult executeProbe(final Probe probe) {
        switch (probe) {
            case LIVE:
                return executeTag(livenessTag, STATUS_UP);
            case READY:
                return executeTag(readinessTag, defaultReadinessEmptyResponse);
            case STARTED:
                return executeTag(startupTag, defaultStartupEmptyResponse);
            case ALL:
            default:
                ProbeResult live = executeTag(livenessTag, STATUS_UP);
                ProbeResult ready = executeTag(readinessTag, defaultReadinessEmptyResponse);
                ProbeResult started = executeTag(startupTag, defaultStartupEmptyResponse);
                List<CheckEntry> checks = new ArrayList<>();
                checks.addAll(live.checks);
                checks.addAll(ready.checks);
                checks.addAll(started.checks);
                boolean up = STATUS_UP.equals(live.status) && STATUS_UP.equals(ready.status) && STATUS_UP.equals(started.status);
                return new ProbeResult(up ? STATUS_UP : STATUS_DOWN, checks);
        }
    }

    /** Executes all Felix Health Checks tagged {@code tag} and converts the result to MicroProfile Health wireformat. */
    private ProbeResult executeTag(final String tag, final String emptyResponseStatus) {
        List<HealthCheckExecutionResult> executionResults = healthCheckExecutor.execute(HealthCheckSelector.tags(tag));

        if (executionResults.isEmpty()) {
            // "A producer with no <kind> procedures expected or installed MUST return positive overall status" and
            // "A producer with <kind> procedures expected but not yet installed" uses the configured/default status.
            return new ProbeResult(emptyResponseStatus, List.of());
        }

        List<CheckEntry> checks = new ArrayList<>(executionResults.size());
        boolean allUp = true;
        for (HealthCheckExecutionResult executionResult : executionResults) {
            CheckEntry entry = toCheckEntry(executionResult);
            checks.add(entry);
            allUp &= STATUS_UP.equals(entry.status);
        }
        return new ProbeResult(allUp ? STATUS_UP : STATUS_DOWN, checks);
    }

    /** Converts one Felix {@link HealthCheckExecutionResult} to a MicroProfile Health "check" entry. */
    private CheckEntry toCheckEntry(final HealthCheckExecutionResult executionResult) {
        Result result = executionResult.getHealthCheckResult();
        // MicroProfile only knows a boolean UP/DOWN - only Felix's OK maps to UP.
        String status = result.getStatus() == Result.Status.OK ? STATUS_UP : STATUS_DOWN;

        String name = executionResult.getHealthCheckMetadata().getTitle();

        Map<String, Object> data = new HashMap<>();
        StringBuilder message = new StringBuilder();
        for (ResultLog.Entry logEntry : result) {
            if (logEntry.isDebug()) {
                continue;
            }
            if (message.length() > 0) {
                message.append("; ");
            }
            message.append(logEntry.getMessage());
        }
        if (message.length() > 0) {
            data.put("message", message.toString());
        }
        if (executionResult.hasTimedOut()) {
            data.put("timedOut", Boolean.TRUE);
        }
        data.put("elapsedTimeInMs", executionResult.getElapsedTimeInMs());

        return new CheckEntry(name, status, data);
    }

    private void sendJson(final HttpExchange exchange, final ProbeResult result) throws IOException {
        StringWriter writer = new StringWriter();
        JSONWriter json = new JSONWriter(writer);
        json.object();
        json.key("status").value(result.status);
        json.key("checks").array();
        for (CheckEntry entry : result.checks) {
            json.object();
            json.key("name").value(entry.name);
            json.key("status").value(entry.status);
            if (!entry.data.isEmpty()) {
                json.key("data").object();
                for (Map.Entry<String, Object> dataEntry : entry.data.entrySet()) {
                    json.key(dataEntry.getKey()).value(dataEntry.getValue());
                }
                json.endObject();
            }
            json.endObject();
        }
        json.endArray();
        json.endObject();

        byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
        int httpStatus = STATUS_UP.equals(result.status) ? 200 : 503;
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** One entry of the {@code checks} array of the MicroProfile Health JSON payload (Appendix B). */
    private static final class CheckEntry {
        final String name;
        final String status;
        final Map<String, Object> data;

        CheckEntry(String name, String status, Map<String, Object> data) {
            this.name = name;
            this.status = status;
            this.data = data;
        }
    }

    /** The overall result of one probe invocation: the top-level MicroProfile Health JSON payload (Appendix B). */
    private static final class ProbeResult {
        final String status;
        final List<CheckEntry> checks;

        ProbeResult(String status, List<CheckEntry> checks) {
            this.status = status;
            this.checks = checks;
        }
    }
}
