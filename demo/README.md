# Demo: felix-healthcheck-jdk

This package shows how the Servlet-based HTTP endpoint of
[Apache Felix Health Check](https://github.com/apache/felix-dev/tree/master/healthcheck)
can be reimplemented on top of the **OSGi JDK HttpServer Whiteboard**
using `com.sun.net.httpserver.HttpHandler` instead of
`jakarta.servlet.http.HttpServlet`.

## What was ported

The original
`org.apache.felix.hc.core.impl.servlet.HealthCheckExecutorServlet` (from the
`org.apache.felix.healthcheck.core` bundle) is a `HttpServlet` that:

* triggers the `HealthCheckExecutor` service to run selected health checks
  (selected by tags/names, taken from the URL path or request parameters),
* renders the aggregated `Result` in one of several formats
  (`html`, `json`, `jsonp`, `txt`, `verbose.txt`),
* is registered multiple times - once at its base path, and once more per
  allowed format at `<path>.<format>` (via a small `ProxyServlet` helper) -
  using the OSGi HTTP Whiteboard Servlet properties.

The health check computation itself
(`HealthCheckExecutor`, `Result`, `HealthCheckExecutionResult`, ...) is
**not** re-implemented: it is reused unchanged from
`org.apache.felix.healthcheck.api` / `org.apache.felix.healthcheck.core`.
Only the piece that exposes the results via HTTP is ported.

| Original (Servlet)                                       | This demo (JDK HttpServer)                                  |
|------------------------------------------------------------|---------------------------------------------------------------|
| `HealthCheckExecutorServlet extends HttpServlet`            | `HealthCheckExecutorHandler implements HttpHandler`            |
| `doGet(HttpServletRequest, HttpServletResponse)`             | `handle(HttpExchange)`                                         |
| `request.getParameter(name)`                                 | manually parsed from `exchange.getRequestURI().getRawQuery()` (see `parseQuery`) |
| `response.setStatus(...)` / `getWriter()`                     | `exchange.sendResponseHeaders(status, length)` + `exchange.getResponseBody()` |
| `HTTP_WHITEBOARD_SERVLET_PATTERN` / `HTTP_WHITEBOARD_CONTEXT_SELECT` service properties | `JDK_HTTP_CONTEXT_PATH` service property (single flat namespace, no servlet context selection) |
| nested `ProxyServlet` (one instance per format, registered at `<path>.<format>`) | nested `FormatHandler` (same idea, registered the same way) |
| `ResultHtmlSerializer` / `ResultJsonSerializer` / `ResultTxtSerializer` / `ResultTxtVerboseSerializer` | copied unchanged (only the package changed) - these classes never used the Servlet API |

## Package layout

* `HealthCheckExecutorHandler` - the ported handler (formerly the servlet).
* `HealthCheckHandlerConfiguration` - `@ObjectClassDefinition` configuration,
  ported from `HealthCheckExecutorServletConfiguration` (dropped the
  `servletContextName` attribute, see below).
* `HealthCheckParam` / `CombinedExecutionResult` - small helper types that
  were private nested classes of the original servlet.
* `serializer` - the four result serializers, copied unchanged.
* `checks.DemoHealthCheck` - a trivial `HealthCheck` implementation so the
  demo has something to show.

## Why there is no "servlet context" attribute

The OSGi HTTP Whiteboard specification supports multiple servlet contexts
(virtual hosts sharing one `HttpService`), selected via
`osgi.http.whiteboard.context.select`. The JDK HttpServer Whiteboard
specification this repository prototypes only has a single, flat context
path namespace per `JdkHttpServerRuntime` (an embedded
`com.sun.net.httpserver.HttpServer`); a specific runtime instance can
instead be targeted via `osgi.http.jdk.target`. `servletContextName` was
therefore dropped in `HealthCheckHandlerConfiguration` in favor of just
`contextPath`.

## Trying it out

See `HealthCheckExecutorHandlerTest` for a full example that starts an
embedded Apache Felix framework, the JDK HttpServer Whiteboard runtime from
the `runtime` module, and the ported handler, then issues real HTTP
requests against `/system/health`, `/system/health.json`,
`/system/health.txt`, and `/system/health.verbose.txt`.
