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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;

/**
 * Configuration for {@link MicroProfileHealthHandler}.
 * <p>
 * MicroProfile Health has no notion of "tags" - a producer just knows
 * whether a given check is a liveness, readiness or startup procedure.
 * Felix Health Check only has free-form {@code hc.tags}. This bridge maps
 * one Felix HC tag to each of the three MicroProfile probe kinds (defaults:
 * {@code live}, {@code ready}, {@code started}); any Felix HC service
 * carrying that tag is included in the corresponding MicroProfile Health
 * endpoint.
 */
@ObjectClassDefinition(name = "MicroProfile Health Bridge for Apache Felix Health Check",
        description = "Exposes org.apache.felix.hc.api HealthCheck services via a MicroProfile Health compatible HTTP endpoint")
public @interface MicroProfileHealthConfiguration {

    String CONTEXT_PATH_DEFAULT = "/health";

    @AttributeDefinition(name = "Context Path", description = "Base context path (defaults to " + CONTEXT_PATH_DEFAULT
            + "); /live, /ready and /started are registered below it")
    String contextPath() default CONTEXT_PATH_DEFAULT;

    @AttributeDefinition(name = "Liveness Tag", description = "Felix Health Check tag (hc.tags) that marks a check as a MicroProfile liveness procedure")
    String livenessTag() default "live";

    @AttributeDefinition(name = "Readiness Tag", description = "Felix Health Check tag (hc.tags) that marks a check as a MicroProfile readiness procedure")
    String readinessTag() default "ready";

    @AttributeDefinition(name = "Startup Tag", description = "Felix Health Check tag (hc.tags) that marks a check as a MicroProfile startup procedure")
    String startupTag() default "started";

    String STATUS_UP = "UP";
    String STATUS_DOWN = "DOWN";

    @AttributeDefinition(name = "Default Readiness Empty Response", description = "Overall status to report for /health/ready (and /health) "
            + "when no readiness procedures are currently installed - mirrors MicroProfile Config property "
            + "mp.health.default.readiness.empty.response", options = {
                    @Option(label = "UP", value = STATUS_UP),
                    @Option(label = "DOWN", value = STATUS_DOWN)
            })
    String defaultReadinessEmptyResponse() default STATUS_DOWN;

    @AttributeDefinition(name = "Default Startup Empty Response", description = "Overall status to report for /health/started (and /health) "
            + "when no startup procedures are currently installed - mirrors MicroProfile Config property "
            + "mp.health.default.startup.empty.response", options = {
                    @Option(label = "UP", value = STATUS_UP),
                    @Option(label = "DOWN", value = STATUS_DOWN)
            })
    String defaultStartupEmptyResponse() default STATUS_DOWN;

    @AttributeDefinition(name = "Disabled", description = "Allows to disable the endpoint if required for security reasons")
    boolean disabled() default false;
}
