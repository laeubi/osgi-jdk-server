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

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.apache.felix.hc.api.Result;
import org.apache.felix.hc.api.execution.HealthCheckExecutionOptions;
import org.apache.felix.hc.api.execution.HealthCheckExecutionResult;
import org.apache.felix.hc.api.execution.HealthCheckExecutor;
import org.apache.felix.hc.api.execution.HealthCheckSelector;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultHtmlSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultJsonSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultTxtSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.serializer.ResultTxtVerboseSerializer;
import org.laeubi.osgi.jdkhttp.demo.healthcheck.util.StringUtils;
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
 * Reimplementation of Apache Felix Health Check's
 * {@code org.apache.felix.hc.core.impl.servlet.HealthCheckExecutorServlet} on
 * top of the OSGi JDK HttpServer Whiteboard.
 * <p>
 * The logic is a close port of the original class: it triggers the
 * {@link HealthCheckExecutor} and renders the aggregated {@link Result} in
 * one of several formats. The main differences to the Servlet original are:
 * <ul>
 * <li>{@code HttpHandler} / {@code HttpExchange} are used instead of
 * {@code HttpServlet} / {@code HttpServletRequest} / {@code HttpServletResponse}.
 * <li>request parameters are parsed manually from the raw query string,
 * since {@code HttpExchange} has no {@code getParameter} equivalent.
 * <li>the response body is fully rendered into a {@code byte[]} before
 * calling {@link HttpExchange#sendResponseHeaders(int, long)}, since the JDK
 * HttpServer requires the content length (or {@code 0} for chunked
 * transfer) up front.
 * <li>there is no servlet context selection - a single
 * {@link JdkHttpWhiteboardConstants#JDK_HTTP_CONTEXT_PATH} property picks
 * the path, see {@link HealthCheckHandlerConfiguration}.
 * </ul>
 */
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = HealthCheckHandlerConfiguration.class, factory = true)
public class HealthCheckExecutorHandler implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckExecutorHandler.class);

    public static final String PARAM_SPLIT_REGEX = "[,;]+";

    static final HealthCheckParam PARAM_TAGS = new HealthCheckParam("tags",
            "Comma-separated list of health checks tags to select - can also be specified via path, e.g. /system/health/tag1,tag2.json. Exclusions can be done by prepending '-' to the tag name");
    static final HealthCheckParam PARAM_FORMAT = new HealthCheckParam("format", null /* to be set in activate() */);
    static final HealthCheckParam PARAM_HTTP_STATUS = new HealthCheckParam("httpStatus", "Specify HTTP result code, for example"
            + " CRITICAL:503 (status 503 if result >= CRITICAL)"
            + " or CRITICAL:503,HEALTH_CHECK_ERROR:500,OK:418 for more specific HTTP status");

    static final HealthCheckParam PARAM_COMBINE_TAGS_WITH_OR = new HealthCheckParam("combineTagsWithOr",
            "Combine tags with OR, active by default. Set to false to combine with AND");
    static final HealthCheckParam PARAM_FORCE_INSTANT_EXECUTION = new HealthCheckParam("forceInstantExecution",
            "If true, forces instant execution by executing async health checks directly, circumventing the cache (2sec by default) of the HealthCheckExecutor");
    static final HealthCheckParam PARAM_OVERRIDE_GLOBAL_TIMEOUT = new HealthCheckParam("timeout",
            "(msec) a timeout status is returned for any health check still running after this period. Overrides the default HealthCheckExecutor timeout");

    static final HealthCheckParam PARAM_INCLUDE_DEBUG = new HealthCheckParam("hcDebug", "Include the DEBUG output of the Health Checks");

    static final HealthCheckParam PARAM_NAMES = new HealthCheckParam("names",
            "Comma-separated list of health check names to select. Exclusions can be done by prepending '-' to the health check name");

    static final String JSONP_CALLBACK_DEFAULT = "processHealthCheckResults";
    static final HealthCheckParam PARAM_JSONP_CALLBACK = new HealthCheckParam("callback",
            "name of the JSONP callback function to use, defaults to " + JSONP_CALLBACK_DEFAULT);

    static final List<HealthCheckParam> PARAM_LIST = Arrays.asList(PARAM_TAGS, PARAM_NAMES, PARAM_FORMAT, PARAM_HTTP_STATUS,
            PARAM_COMBINE_TAGS_WITH_OR, PARAM_FORCE_INSTANT_EXECUTION, PARAM_OVERRIDE_GLOBAL_TIMEOUT, PARAM_INCLUDE_DEBUG,
            PARAM_JSONP_CALLBACK);

    public static final String FORMAT_HTML = "html";
    public static final String FORMAT_JSON = "json";
    public static final String FORMAT_JSONP = "jsonp";
    public static final String FORMAT_TXT = "txt";
    public static final String FORMAT_VERBOSE_TXT = "verbose.txt";

    private static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    private static final String CONTENT_TYPE_TXT = "text/plain; charset=UTF-8";
    private static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
    private static final String CONTENT_TYPE_JSONP = "application/javascript; charset=UTF-8";
    private static final String STATUS_HEADER_NAME = "X-Health";

    private static final String CACHE_CONTROL_KEY = "Cache-control";
    private static final String CACHE_CONTROL_VALUE = "no-cache";
    private static final String CORS_ORIGIN_HEADER_NAME = "Access-Control-Allow-Origin";

    private String contextPath;

    private String corsAccessControlAllowOrigin;

    private Map<Result.Status, Integer> defaultStatusMapping;

    // Key: context path | Value: handler registration
    private Map<String, ServiceRegistration<HttpHandler>> handlerRegistrations;

    private BundleContext bundleContext;
    private long handlerDefaultTimeout;
    private String[] handlerDefaultTags;
    private String defaultFormat;
    private String[] allowedFormats;
    private boolean defaultCombineTagsWithOr;
    private boolean disableRequestConfiguration;

    @Reference
    HealthCheckExecutor healthCheckExecutor;

    @Reference
    ResultHtmlSerializer htmlSerializer;

    @Reference
    ResultJsonSerializer jsonSerializer;

    @Reference
    ResultTxtSerializer txtSerializer;

    @Reference
    ResultTxtVerboseSerializer verboseTxtSerializer;

    @Activate
    protected final void activate(final HealthCheckHandlerConfiguration configuration, final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.handlerRegistrations = new HashMap<>();

        this.contextPath = configuration.contextPath();
        this.defaultStatusMapping = getStatusMapping(configuration.httpStatusMapping());
        this.handlerDefaultTimeout = configuration.timeout();
        this.handlerDefaultTags = configuration.tags();
        this.defaultCombineTagsWithOr = configuration.combineTagsWithOr();
        this.defaultFormat = configuration.format();
        this.allowedFormats = configuration.allowed_formats();
        // make sure to include default format
        if (!this.isFormatAllowed(this.defaultFormat)) {
            final String[] allFormats = new String[this.allowedFormats.length + 1];
            System.arraycopy(this.allowedFormats, 0, allFormats, 0, this.allowedFormats.length);
            allFormats[this.allowedFormats.length] = this.defaultFormat;
            this.allowedFormats = allFormats;
        }
        PARAM_FORMAT.setDescription("Output format, " + String.join("|", allowedFormats) + " - an extension in the URL overrides this");

        this.corsAccessControlAllowOrigin = configuration.cors_accessControlAllowOrigin();
        this.disableRequestConfiguration = configuration.disable_request_configuration();

        if (configuration.disabled()) {
            LOG.info("Health Check Handler is disabled by configuration");
            return;
        }

        LOG.info("Health Check Handler Configuration: contextPath={}, defaultStatusMapping={}, handlerDefaultTimeout={}, "
                + "handlerDefaultTags={}, defaultCombineTagsWithOr={}, defaultFormat={}, allowedFormats={}, corsAccessControlAllowOrigin={}",
                contextPath, defaultStatusMapping, handlerDefaultTimeout,
                handlerDefaultTags != null ? Arrays.asList(handlerDefaultTags) : "<none>", defaultCombineTagsWithOr, defaultFormat,
                Arrays.toString(this.allowedFormats), corsAccessControlAllowOrigin);

        Map<String, HttpHandler> handlersToRegister = new LinkedHashMap<>();
        handlersToRegister.put(this.contextPath, this);
        if (isFormatAllowed(FORMAT_HTML)) {
            handlersToRegister.put(this.contextPath.concat(".").concat(FORMAT_HTML), new FormatHandler(FORMAT_HTML));
        }
        if (isFormatAllowed(FORMAT_JSON)) {
            handlersToRegister.put(this.contextPath.concat(".").concat(FORMAT_JSON), new FormatHandler(FORMAT_JSON));
        }
        if (isFormatAllowed(FORMAT_JSONP)) {
            handlersToRegister.put(this.contextPath.concat(".").concat(FORMAT_JSONP), new FormatHandler(FORMAT_JSONP));
        }
        if (isFormatAllowed(FORMAT_TXT)) {
            handlersToRegister.put(this.contextPath.concat(".").concat(FORMAT_TXT), new FormatHandler(FORMAT_TXT));
        }
        if (isFormatAllowed(FORMAT_VERBOSE_TXT)) {
            handlersToRegister.put(this.contextPath.concat(".").concat(FORMAT_VERBOSE_TXT), new FormatHandler(FORMAT_VERBOSE_TXT));
        }

        for (final Map.Entry<String, HttpHandler> handler : handlersToRegister.entrySet()) {
            try {
                LOG.info("Registering HC Handler > Name: '{}', Path: '{}'", getClass().getSimpleName(), handler.getKey());
                registerHandler(handler.getKey(), handler.getValue());
            } catch (Exception e) {
                LOG.error("Could not register health check handler: " + e, e);
            }
        }
    }

    @Deactivate
    public void deactivate() {
        for (final Entry<String, ServiceRegistration<HttpHandler>> entry : handlerRegistrations.entrySet()) {
            try {
                LOG.info("Unregistering HC Handler {} from path {}", getClass().getSimpleName(), entry.getKey());
                entry.getValue().unregister();
            } catch (Exception e) {
                // ignore the exception - this might happen on shutdown
            }
        }
        handlerRegistrations.clear();
    }

    private void registerHandler(final String contextPath, final HttpHandler handler) {
        final Dictionary<String, Object> properties = new Hashtable<>();
        properties.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, contextPath);

        final ServiceRegistration<HttpHandler> registration = bundleContext.registerService(HttpHandler.class, handler, properties);
        handlerRegistrations.put(contextPath, registration);
    }

    /**
     * Check if the format is allowed
     * @param format The format
     * @return {@code true} if allowed
     */
    private boolean isFormatAllowed(final String format) {
        for (final String f : this.allowedFormats) {
            if (f.equals(format)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
        try {
            final String[] splitPathInfo = splitFormat(pathInfo(exchange));
            String format = splitPathInfo[1];
            final Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            if (format == null) {
                // if not provided via extension use parameter or default
                format = StringUtils.defaultIfBlank(query.get(PARAM_FORMAT.getName()), defaultFormat);
            }

            String pathTokensStr = splitPathInfo[0];
            if (pathTokensStr != null && pathTokensStr.startsWith("/")) {
                pathTokensStr = pathTokensStr.substring(1);
            }

            doGet(exchange, query, pathTokensStr, format);
        } finally {
            exchange.close();
        }
    }

    /** the request path below the registered context path (may be {@code null}). */
    private String pathInfo(final HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.equals(contextPath)) {
            return null;
        }
        if (path.startsWith(contextPath + "/")) {
            return path.substring(contextPath.length());
        }
        // request went to one of the format-specific contexts registered by FormatHandler
        return null;
    }

    void doGet(final HttpExchange exchange, final Map<String, String> query, final String pathTokensStr, final String format)
            throws IOException {
        HealthCheckSelector selector = HealthCheckSelector.empty();

        List<String> tags = new ArrayList<>();
        List<String> names = new ArrayList<>();

        if (!disableRequestConfiguration && StringUtils.isNotBlank(pathTokensStr)) {
            String[] pathTokens = pathTokensStr.split(PARAM_SPLIT_REGEX);
            for (String pathToken : pathTokens) {
                if (pathToken.indexOf(' ') >= 0) {
                    // token contains space. assume it is a name
                    names.add(pathToken);
                } else {
                    tags.add(pathToken);
                }
            }
        }
        if (tags.size() == 0) {
            // if not provided via path use parameter or configured default
            String tagsParameter = this.disableRequestConfiguration ? null : query.get(PARAM_TAGS.getName());
            tags = Arrays.asList(StringUtils.isNotBlank(tagsParameter) ? tagsParameter.split(PARAM_SPLIT_REGEX) : handlerDefaultTags);
        }
        selector.withTags(tags.toArray(new String[0]));

        if (names.size() == 0 && !this.disableRequestConfiguration) {
            // if not provided via path use parameter or default
            names = Arrays.asList(StringUtils.defaultIfBlank(query.get(PARAM_NAMES.getName()), "").split(PARAM_SPLIT_REGEX));
        }
        selector.withNames(names.toArray(new String[0]));

        final boolean includeDebug = this.disableRequestConfiguration ? false : Boolean.parseBoolean(query.get(PARAM_INCLUDE_DEBUG.getName()));

        String httpStatusMappingParameterVal = this.disableRequestConfiguration ? null : query.get(PARAM_HTTP_STATUS.getName());
        final Map<Result.Status, Integer> statusMapping =
                httpStatusMappingParameterVal != null ? getStatusMapping(httpStatusMappingParameterVal) : defaultStatusMapping;

        HealthCheckExecutionOptions executionOptions = new HealthCheckExecutionOptions();

        String paramCombineTagsWithOr = this.disableRequestConfiguration ? null : query.get(PARAM_COMBINE_TAGS_WITH_OR.getName());
        executionOptions.setCombineTagsWithOr(paramCombineTagsWithOr != null ? Boolean.parseBoolean(paramCombineTagsWithOr) : defaultCombineTagsWithOr);

        if (!this.disableRequestConfiguration) {
            executionOptions.setForceInstantExecution(Boolean.parseBoolean(query.get(PARAM_FORCE_INSTANT_EXECUTION.getName())));
        }

        String overrideGlobalTimeoutVal = this.disableRequestConfiguration ? null : query.get(PARAM_OVERRIDE_GLOBAL_TIMEOUT.getName());
        if (StringUtils.isNotBlank(overrideGlobalTimeoutVal)) {
            executionOptions.setOverrideGlobalTimeout(Integer.parseInt(overrideGlobalTimeoutVal));
        } else if (handlerDefaultTimeout > -1) {
            executionOptions.setOverrideGlobalTimeout((int) handlerDefaultTimeout);
        }

        List<HealthCheckExecutionResult> executionResults = this.healthCheckExecutor.execute(selector, executionOptions);

        CombinedExecutionResult combinedExecutionResult = new CombinedExecutionResult(executionResults);
        Result overallResult = combinedExecutionResult.getHealthCheckResult();

        sendNoCacheHeaders(exchange);
        sendCorsHeaders(exchange);

        Integer httpStatus = statusMapping.get(overallResult.getStatus());
        exchange.getResponseHeaders().set(STATUS_HEADER_NAME, overallResult.getStatus().toString());

        final boolean formatAllowed = this.isFormatAllowed(format);

        if (formatAllowed && FORMAT_HTML.equals(format)) {
            sendHtmlResponse(overallResult, executionResults, exchange, httpStatus, includeDebug);
        } else if (formatAllowed && FORMAT_JSON.equals(format)) {
            sendJsonResponse(overallResult, executionResults, null, exchange, httpStatus, includeDebug);
        } else if (formatAllowed && FORMAT_JSONP.equals(format)) {
            String jsonpCallback = StringUtils.defaultIfBlank(query.get(PARAM_JSONP_CALLBACK.getName()), JSONP_CALLBACK_DEFAULT);
            sendJsonResponse(overallResult, executionResults, jsonpCallback, exchange, httpStatus, includeDebug);
        } else if (formatAllowed && format != null && format.endsWith(FORMAT_TXT)) {
            sendTxtResponse(overallResult, exchange, httpStatus, FORMAT_VERBOSE_TXT.equals(format), executionResults, includeDebug);
        } else {
            sendResponse(exchange, httpStatus, "text/plain; charset=UTF-8",
                    "Invalid format " + format + " - supported formats: " + Arrays.toString(this.allowedFormats));
        }
    }

    String[] splitFormat(final String pathInfo) {
        if (pathInfo != null) {
            for (String format : new String[] { FORMAT_HTML, FORMAT_JSON, FORMAT_JSONP, FORMAT_VERBOSE_TXT, FORMAT_TXT }) {
                final String formatWithDot = ".".concat(format);
                if (pathInfo.endsWith(formatWithDot)) {
                    return new String[] { pathInfo.substring(0, pathInfo.length() - formatWithDot.length()), format };
                }
            }
        }
        return new String[] { pathInfo, null };
    }

    private void sendTxtResponse(final Result overallResult, final HttpExchange exchange, final int httpStatus, boolean verbose,
            List<HealthCheckExecutionResult> executionResults, boolean includeDebug) throws IOException {
        String body = verbose ? verboseTxtSerializer.serialize(overallResult, executionResults, includeDebug)
                : txtSerializer.serialize(overallResult);
        sendResponse(exchange, httpStatus, CONTENT_TYPE_TXT, body);
    }

    private void sendJsonResponse(final Result overallResult, final List<HealthCheckExecutionResult> executionResults,
            final String jsonpCallback, final HttpExchange exchange, final int httpStatus, boolean includeDebug) throws IOException {
        String contentType = StringUtils.isNotBlank(jsonpCallback) ? CONTENT_TYPE_JSONP : CONTENT_TYPE_JSON;
        String resultJson = this.jsonSerializer.serialize(overallResult, executionResults, jsonpCallback, includeDebug);
        sendResponse(exchange, httpStatus, contentType, resultJson);
    }

    private void sendHtmlResponse(final Result overallResult, final List<HealthCheckExecutionResult> executionResults,
            final HttpExchange exchange, final int httpStatus, boolean includeDebug) throws IOException {
        List<HealthCheckParam> allowedParameters = disableRequestConfiguration ? Arrays.asList(PARAM_FORMAT) : PARAM_LIST;
        String body = this.htmlSerializer.serialize(overallResult, executionResults, allowedParameters, includeDebug);
        sendResponse(exchange, httpStatus, CONTENT_TYPE_HTML, body);
    }

    private void sendResponse(final HttpExchange exchange, final int httpStatus, final String contentType, final String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sendNoCacheHeaders(final HttpExchange exchange) {
        exchange.getResponseHeaders().set(CACHE_CONTROL_KEY, CACHE_CONTROL_VALUE);
    }

    private void sendCorsHeaders(final HttpExchange exchange) {
        if (StringUtils.isNotBlank(corsAccessControlAllowOrigin)) {
            exchange.getResponseHeaders().set(CORS_ORIGIN_HEADER_NAME, corsAccessControlAllowOrigin);
        }
    }

    /** Parses a raw (still percent-encoded) query string into a name → first-value map, mirroring
     * {@code HttpServletRequest#getParameter(String)}. */
    static Map<String, String> parseQuery(final String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (StringUtils.isBlank(rawQuery)) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            result.putIfAbsent(key, value);
        }
        return result;
    }

    Map<Result.Status, Integer> getStatusMapping(String mappingStr) {
        Map<Result.Status, Integer> statusMapping = new TreeMap<>();

        try {
            String[] bits = mappingStr.split("[,]");
            for (String bit : bits) {
                String[] tuple = bit.split("[:]");
                statusMapping.put(Result.Status.valueOf(tuple[0]), Integer.parseInt(tuple[1]));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid parameter httpStatus=" + mappingStr + " " + e, e);
        }

        if (!statusMapping.containsKey(Result.Status.OK)) {
            statusMapping.put(Result.Status.OK, 200);
        }
        if (!statusMapping.containsKey(Result.Status.WARN)) {
            statusMapping.put(Result.Status.WARN, statusMapping.get(Result.Status.OK));
        }
        if (!statusMapping.containsKey(Result.Status.TEMPORARILY_UNAVAILABLE)) {
            statusMapping.put(Result.Status.TEMPORARILY_UNAVAILABLE, 503);
        }
        if (!statusMapping.containsKey(Result.Status.CRITICAL)) {
            statusMapping.put(Result.Status.CRITICAL, 503);
        }
        if (!statusMapping.containsKey(Result.Status.HEALTH_CHECK_ERROR)) {
            statusMapping.put(Result.Status.HEALTH_CHECK_ERROR, 500);
        }
        return statusMapping;
    }

    /**
     * Serves one fixed format at its own context path, e.g. {@code /system/health.json}.
     * Equivalent to the original {@code HealthCheckExecutorServlet.ProxyServlet}.
     */
    private final class FormatHandler implements HttpHandler {

        private final String format;

        private FormatHandler(final String format) {
            this.format = format;
        }

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                HealthCheckExecutorHandler.this.doGet(exchange, query, null, format);
            } finally {
                exchange.close();
            }
        }
    }

}
