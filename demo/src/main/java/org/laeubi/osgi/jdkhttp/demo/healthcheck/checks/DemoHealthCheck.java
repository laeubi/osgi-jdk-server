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
package org.laeubi.osgi.jdkhttp.demo.healthcheck.checks;

import org.apache.felix.hc.api.FormattingResultLog;
import org.apache.felix.hc.api.HealthCheck;
import org.apache.felix.hc.api.Result;
import org.osgi.service.component.annotations.Component;

/**
 * A trivial always-healthy check, registered with the {@code demo} tag, so
 * the {@code /system/health} endpoint of the demo has at least one result to
 * display.
 */
@Component(service = HealthCheck.class, property = {
        HealthCheck.NAME + "=Demo Health Check",
        HealthCheck.TAGS + "=demo"
})
public class DemoHealthCheck implements HealthCheck {

    @Override
    public Result execute() {
        FormattingResultLog log = new FormattingResultLog();
        log.info("The JDK HttpServer Whiteboard demo is up and running.");
        return new Result(log);
    }
}
