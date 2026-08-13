# osgi-jdk-server
Preliminary Implementation Prototype for OSGi JDK Server Specification

## Modules

This is a multi-module Maven project with the following modules:

- `runtime` -
  the current implementation of the OSGi JDK HttpServer Whiteboard specification and its API.
- `demo` -
  demo module showcasing use-cases of the new specification.
  Currently contains `felix-healthcheck-jdk`, a port of Apache Felix
  Health Check's Servlet-based HTTP endpoint to `com.sun.net.httpserver.HttpHandler`
  (see `demo/README.md` for details).

Build all modules from the repository root with:

```
mvn clean install
```
