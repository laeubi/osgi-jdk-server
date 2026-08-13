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

# Demo: mp-health-jdk

This package is the reverse of `felix-healthcheck-jdk`: instead of porting a
Servlet-based endpoint to `HttpHandler`, it makes the **existing** Apache
Felix Health Check API (`HealthCheckExecutor`, `HealthCheck`, `Result`)
available through the JDK HttpServer Whiteboard using the wire format
defined by the
[MicroProfile Health](https://download.eclipse.org/microprofile/microprofile-health-4.0/microprofile-health-spec-4.0.html)
specification's "Protocol and Wireformat" chapter - so, from the outside,
the OSGi backend looks like a native MicroProfile Health server.

## What it does

`MicroProfileHealthHandler` registers four `HttpHandler` services:

| Path              | MicroProfile probe        |
|-------------------|----------------------------|
| `/health/live`    | liveness                   |
| `/health/ready`   | readiness                  |
| `/health/started` | startup                    |
| `/health`         | combined (all three kinds) |

Each request runs `HealthCheckExecutor.execute(HealthCheckSelector.tags(tag))`
for the tag configured for that probe kind (defaults: `live`, `ready`,
`started` - see `MicroProfileHealthConfiguration`) and renders the result as
the MicroProfile Health JSON payload:

```json
{ "status": "UP", "checks": [ { "name": "...", "status": "UP", "data": { "message": "..." } } ] }
```

* Overall `status` is computed with logical conjunction (AND): `UP` only if
  every executed check reports `UP`.
* `200` is returned for `UP`, `503` for `DOWN` (per the spec's Appendix A);
  unexpected exceptions produce `500` with no JSON payload.
* `Content-Type: application/json` is always set on JSON responses.

## Mapping Felix Health Check onto MicroProfile Health

MicroProfile Health has no concept of "tags" like Felix Health Check does -
a check is simply known to a producer as a liveness, readiness or startup
procedure. This bridge uses the configurable `livenessTag` /
`readinessTag` / `startupTag` (defaulting to `live` / `ready` / `started`)
to decide which Felix `HealthCheck` services show up under which
MicroProfile endpoint; a check can carry more than one of these tags to
appear under multiple probes.

Felix Health Check has a richer, five-value status model
(`OK`, `WARN`, `TEMPORARILY_UNAVAILABLE`, `CRITICAL`, `HEALTH_CHECK_ERROR`)
than MicroProfile Health's boolean `UP`/`DOWN`. This bridge collapses it the
simplest possible way: only `OK` maps to `UP`, everything else maps to
`DOWN`. A more sophisticated bridge could treat `WARN` as still `UP`; this
demo intentionally keeps the mapping simple and documents the trade-off
here instead.

## Empty-response defaults

The specification distinguishes "no procedures of this kind are expected or
installed" (always `UP`) from "procedures are expected but not yet
installed" (readiness/startup default `DOWN`, liveness still `UP`), and
allows the readiness/startup default to be overridden via MicroProfile
Config properties (`mp.health.default.readiness.empty.response` /
`mp.health.default.startup.empty.response`).

Our bridge cannot reliably distinguish "not expected" from "expected but not
installed" from the OSGi side (there's no registry of what tags are
*supposed* to exist), so it simplifies this to one rule per probe kind,
applied whenever there are zero matching Felix `HealthCheck` services for
the configured tag:

* liveness (`live`) → always `UP` (200).
* readiness (`ready`) → configurable via `defaultReadinessEmptyResponse`
  (default `DOWN` / 503, matching the spec's default).
* startup (`started`) → configurable via `defaultStartupEmptyResponse`
  (default `DOWN` / 503, matching the spec's default).

The combined `/health` endpoint evaluates liveness, readiness and startup
*separately* (so each kind's empty-response rule applies correctly) and
then merges the three results: overall status is the AND of the three, and
`checks` is the concatenation of their individual check lists.

## Package layout

* `MicroProfileHealthHandler` - the bridge, `implements HttpHandler`,
  registered once per probe path.
* `MicroProfileHealthConfiguration` - `@ObjectClassDefinition` configuration
  (context path, tag names, empty-response defaults, disable flag).
* `checks.DemoLivenessCheck` / `checks.DemoReadinessCheck` - trivial always-OK
  Felix `HealthCheck` services tagged `live` / `ready` so the demo endpoints
  are not empty out of the box.

## Trying it out

See `MicroProfileHealthHandlerTest` for examples covering: empty-response
defaults (and their configurability), a registered passing/failing check,
the combined `/health` aggregation, HTTP status code mapping, and the
`Content-Type: application/json` header.
