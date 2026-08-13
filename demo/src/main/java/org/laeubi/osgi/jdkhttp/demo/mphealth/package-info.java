/**
 * Demo: "mp-health-jdk".
 * <p>
 * The reverse of the {@code felix-healthcheck-jdk} demo: instead of porting
 * a Servlet-based HTTP endpoint to the JDK HttpServer Whiteboard, this
 * package exposes the existing Apache Felix Health Check API
 * ({@code org.apache.felix.hc.api.HealthCheck} /
 * {@code org.apache.felix.hc.api.execution.HealthCheckExecutor}) through a
 * {@code com.sun.net.httpserver.HttpHandler} that speaks the
 * <a href="https://download.eclipse.org/microprofile/microprofile-health-4.0/microprofile-health-spec-4.0.html">
 * MicroProfile Health</a> "Protocol and Wireformat": from the outside, the
 * server looks like a native MicroProfile Health endpoint
 * ({@code /health}, {@code /health/live}, {@code /health/ready},
 * {@code /health/started}), even though the checks themselves are plain
 * Felix Health Check services.
 */
package org.laeubi.osgi.jdkhttp.demo.mphealth;
