/**
 * Demo: "felix-healthcheck-jdk".
 * <p>
 * Shows how the Servlet-based
 * {@code org.apache.felix.hc.core.impl.servlet.HealthCheckExecutorServlet}
 * from Apache Felix Health Check
 * (see the {@code healthcheck} project at
 * <a href="https://github.com/apache/felix-dev">apache/felix-dev</a>) can be
 * reimplemented on top of the OSGi JDK HttpServer Whiteboard using
 * {@code com.sun.net.httpserver.HttpHandler} instead of
 * {@code jakarta.servlet.http.HttpServlet}.
 * <p>
 * The health check computation itself (the {@code HealthCheckExecutor},
 * {@code Result}, and {@code HealthCheckExecutionResult} types) is reused
 * unchanged from {@code org.apache.felix.healthcheck.api} - only the piece
 * that exposes the results via HTTP is ported.
 */
package org.laeubi.osgi.jdkhttp.demo.healthcheck;
