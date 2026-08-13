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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;

/**
 * Adapted from
 * {@code org.apache.felix.hc.core.impl.servlet.HealthCheckExecutorServletConfiguration}.
 * <p>
 * Compared to the original there is no {@code servletContextName} attribute:
 * the OSGi JDK HttpServer Whiteboard has a single flat context path
 * namespace per {@code JdkHttpServerRuntime} (selectable via
 * {@code osgi.http.jdk.target} instead of a servlet context), so
 * {@link #contextPath()} directly replaces {@code servletPath}.
 */
@ObjectClassDefinition(name = "Apache Felix Health Check Executor - JDK HttpServer Handler",
        description = "Serializes health check results into html, json or txt format via a com.sun.net.httpserver.HttpHandler")
public @interface HealthCheckHandlerConfiguration {

    String CONTEXT_PATH_DEFAULT = "/system/health";

    @AttributeDefinition(name = "Context Path", description = "Context path (defaults to " + CONTEXT_PATH_DEFAULT + ")")
    String contextPath() default CONTEXT_PATH_DEFAULT;

    @AttributeDefinition(name = "Http Status Mapping", description = "Maps HC result status values to http response codes. Can be overwritten via request parameter 'httpStatus'")
    String httpStatusMapping() default "OK:200,WARN:200,CRITICAL:503,TEMPORARILY_UNAVAILABLE:503,HEALTH_CHECK_ERROR:500";

    @AttributeDefinition(name = "Timeout", description = "Timeout for health check executor. If not configured (left to -1), the default from health check executor's configuration is taken. The setting can always be overwritten by request parameter 'timeout'")
    long timeout() default -1;

    @AttributeDefinition(name = "Default Tags", description = "Default tags if no tags are provided in URL.")
    String[] tags() default {};

    @AttributeDefinition(name = "Combine Tags with OR", description = "If true, will execute checks that have any of the given tags. If false, will only execute checks that have *all* of the given tags.")
    boolean combineTagsWithOr() default true;

    @AttributeDefinition(name = "Default Format", description = "Default format if format is not provided in URL",
        options = {
            @Option(label = "HTML", value = HealthCheckExecutorHandler.FORMAT_HTML),
            @Option(label = "JSON", value = HealthCheckExecutorHandler.FORMAT_JSON),
            @Option(label = "JSONP", value = HealthCheckExecutorHandler.FORMAT_JSONP),
            @Option(label = "TXT", value = HealthCheckExecutorHandler.FORMAT_TXT),
            @Option(label = "VERBOSE TXT", value = HealthCheckExecutorHandler.FORMAT_VERBOSE_TXT)
        })
    String format() default HealthCheckExecutorHandler.FORMAT_HTML;

    @AttributeDefinition(name = "Allowed Formats", description = "Allow list for formats passed in via the URL",
        options = {
            @Option(label = "HTML", value = HealthCheckExecutorHandler.FORMAT_HTML),
            @Option(label = "JSON", value = HealthCheckExecutorHandler.FORMAT_JSON),
            @Option(label = "JSONP", value = HealthCheckExecutorHandler.FORMAT_JSONP),
            @Option(label = "TXT", value = HealthCheckExecutorHandler.FORMAT_TXT),
            @Option(label = "VERBOSE TXT", value = HealthCheckExecutorHandler.FORMAT_VERBOSE_TXT)
        })
    String[] allowed_formats() default {
        HealthCheckExecutorHandler.FORMAT_HTML,
        HealthCheckExecutorHandler.FORMAT_JSON,
        HealthCheckExecutorHandler.FORMAT_JSONP,
        HealthCheckExecutorHandler.FORMAT_TXT,
        HealthCheckExecutorHandler.FORMAT_VERBOSE_TXT
    };

    @AttributeDefinition(name = "Disabled", description = "Allows to disable the handler if required for security reasons")
    boolean disabled() default false;

    @AttributeDefinition(name = "CORS Access-Control-Allow-Origin", description = "Sets the Access-Control-Allow-Origin CORS header. If blank no header is sent.")
    String cors_accessControlAllowOrigin() default "*";

    @AttributeDefinition(name = "Disable Request Configuration", description = "If set, parameters passed in via the request are ignored (except for format)")
    boolean disable_request_configuration() default false;

    @AttributeDefinition
    String webconsole_configurationFactory_nameHint() default "{contextPath} default format:{format} default tags:{tags} ";
}
