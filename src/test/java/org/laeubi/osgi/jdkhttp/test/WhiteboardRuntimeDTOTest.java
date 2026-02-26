package org.laeubi.osgi.jdkhttp.test;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceReference;
import org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntime;
import org.osgi.service.jdkhttp.runtime.dto.FailedHandlerDTO;
import org.osgi.service.jdkhttp.runtime.dto.JdkHttpServerRuntimeDTO;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that verify the content of the {@link JdkHttpServerRuntime} and the
 * DTOs it exposes.
 */
class WhiteboardRuntimeDTOTest extends AbstractWhiteboardTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static HttpHandler noopHandler() {
        return exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        };
    }

    private static Dictionary<String, Object> handlerProps(String path) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, path);
        return props;
    }

    private static Dictionary<String, Object> namedHandlerProps(String path, String name) {
        Dictionary<String, Object> props = handlerProps(path);
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_NAME, name);
        return props;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void runtimeServiceIsRegisteredInOsgi() {
        ServiceReference<JdkHttpServerRuntime> ref =
                context.getServiceReference(JdkHttpServerRuntime.class);
        assertNotNull(ref, "JdkHttpServerRuntime service must be registered");

        JdkHttpServerRuntime runtime = context.getService(ref);
        assertNotNull(runtime);
        context.ungetService(ref);
    }

    @Test
    void runtimeDTOHasEndpoint() {
        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertNotNull(dto.endpoints);
        assertEquals(1, dto.endpoints.length);
        assertTrue(dto.endpoints[0].startsWith("http://"),
                "Endpoint should be an HTTP URL, got: " + dto.endpoints[0]);
        assertTrue(dto.endpoints[0].contains(":"),
                "Endpoint should contain a port, got: " + dto.endpoints[0]);
    }

    @Test
    void runtimeDTOReflectsActiveHandlers() {
        context.registerService(HttpHandler.class, noopHandler(),
                namedHandlerProps("/api/v1", "apiV1"));

        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertEquals(1, dto.handlers.length);
        assertEquals("/api/v1", dto.handlers[0].contextPath);
        assertEquals("apiV1", dto.handlers[0].contextName);
        assertEquals(0, dto.failedHandlers.length);
    }

    @Test
    void runtimeDTOReflectsMultipleHandlers() {
        context.registerService(HttpHandler.class, noopHandler(), handlerProps("/one"));
        context.registerService(HttpHandler.class, noopHandler(), handlerProps("/two"));
        context.registerService(HttpHandler.class, noopHandler(), handlerProps("/three"));

        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertEquals(3, dto.handlers.length);
        assertEquals(0, dto.failedHandlers.length);
    }

    @Test
    void runtimeDTOShowsFailedHandlerOnInvalidPath() {
        Dictionary<String, Object> props = new Hashtable<>();
        // Missing JDK_HTTP_CONTEXT_PATH
        context.registerService(HttpHandler.class, noopHandler(), props);

        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertEquals(0, dto.handlers.length);
        assertEquals(1, dto.failedHandlers.length);
        assertEquals(FailedHandlerDTO.FAILURE_REASON_INVALID_CONTEXT_PATH,
                dto.failedHandlers[0].failureReason);
    }

    @Test
    void runtimeDTOShowsFailedHandlerWhenShadowed() {
        Dictionary<String, Object> lowProps = new Hashtable<>();
        lowProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, "/shadow");
        lowProps.put(org.osgi.framework.Constants.SERVICE_RANKING, 10);

        Dictionary<String, Object> highProps = new Hashtable<>();
        highProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, "/shadow");
        highProps.put(org.osgi.framework.Constants.SERVICE_RANKING, 100);

        context.registerService(HttpHandler.class, noopHandler(), lowProps);
        context.registerService(HttpHandler.class, noopHandler(), highProps);

        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertEquals(1, dto.handlers.length);
        assertEquals("/shadow", dto.handlers[0].contextPath);

        assertEquals(1, dto.failedHandlers.length);
        assertEquals(FailedHandlerDTO.FAILURE_REASON_SHADOWED_BY_OTHER_HANDLER,
                dto.failedHandlers[0].failureReason);
    }

    @Test
    void runtimeDTOReflectsFilters() {
        Filter filter = new Filter() {
            @Override
            public void doFilter(com.sun.net.httpserver.HttpExchange exchange, Chain chain) throws IOException {
                chain.doFilter(exchange);
            }
            @Override
            public String description() { return "test"; }
        };

        Dictionary<String, Object> filterProps = new Hashtable<>();
        filterProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_FILTER_PATTERN, "/api");
        filterProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_FILTER_NAME, "testFilter");
        context.registerService(Filter.class, filter, filterProps);

        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertEquals(1, dto.filters.length);
        assertEquals("testFilter", dto.filters[0].filterName);
        assertEquals(1, dto.filters[0].patterns.length);
        assertEquals("/api", dto.filters[0].patterns[0]);
        assertEquals(0, dto.failedFilters.length);
    }

    @Test
    void runtimeDTOUpdatesAfterHandlerUnregistration() {
        var reg = context.registerService(HttpHandler.class, noopHandler(), handlerProps("/temp"));
        assertEquals(1, getRuntimeDTO().handlers.length);

        reg.unregister();
        assertEquals(0, getRuntimeDTO().handlers.length);
    }

    @Test
    void runtimeServiceIdIsExposedInDTO() {
        JdkHttpServerRuntimeDTO dto = getRuntimeDTO();
        assertTrue(dto.serviceId > 0, "serviceId must be a positive service.id");
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private JdkHttpServerRuntimeDTO getRuntimeDTO() {
        ServiceReference<JdkHttpServerRuntime> ref =
                context.getServiceReference(JdkHttpServerRuntime.class);
        assertNotNull(ref);
        JdkHttpServerRuntime runtime = context.getService(ref);
        assertNotNull(runtime);
        try {
            return runtime.getRuntimeDTO();
        } finally {
            context.ungetService(ref);
        }
    }
}
