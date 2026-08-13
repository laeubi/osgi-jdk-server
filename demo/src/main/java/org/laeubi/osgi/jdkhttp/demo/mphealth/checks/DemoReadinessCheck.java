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
package org.laeubi.osgi.jdkhttp.demo.mphealth.checks;

import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.osgi.service.component.annotations.Component;

/**
 * Trivial readiness probe, always {@code OK} - tagged {@code ready} to
 * appear in {@code /health/ready} and the combined {@code /health} endpoint.
 */
@Component(service = HealthCheck.class, property = { HealthCheck.NAME + "=Demo Readiness Check", HealthCheck.TAGS + "=ready" })
public class DemoReadinessCheck implements HealthCheck {

    @Override
    public Result execute() {
        FormattingResultLog log = new FormattingResultLog();
        log.info("Ready to serve requests");
        return new Result(log);
    }
}
