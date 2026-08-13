# osgi-jdk-server
Preliminary Implementation Prototype for OSGi JDK Server Specification

## Modules

This is a multi-module Maven project with the following modules:

- `runtime` -
  the current implementation of the OSGi JDK HttpServer Whiteboard specification and its API.
- `demo` -
  demo module showcasing use-cases of the new specification.
  Currently contains:
  - `felix-healthcheck-jdk`, a port of Apache Felix
    Health Check's Servlet-based HTTP endpoint to `com.sun.net.httpserver.HttpHandler`.
  - `mp-health-jdk`, the reverse: it exposes Apache Felix Health Check via a
    MicroProfile Health compatible wire format/endpoints (`/health`,
    `/health/live`, `/health/ready`, `/health/started`), so the OSGi backend
    looks like a native MicroProfile Health server from the outside.

  See `demo/README.md` for details on both.

Build all modules from the repository root with:

```
mvn clean install
```
