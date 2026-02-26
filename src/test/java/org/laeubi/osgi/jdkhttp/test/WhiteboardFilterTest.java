package org.laeubi.osgi.jdkhttp.test;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that verify {@code Filter} service registration and invocation.
 */
class WhiteboardFilterTest extends AbstractWhiteboardTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static HttpHandler echoHandler() {
        return exchange -> {
            String response = "ok";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    /**
     * A filter that adds a custom {@code X-Filtered} response header so tests
     * can verify the filter was invoked.
     */
    private static Filter headerFilter(String value) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                exchange.getResponseHeaders().add("X-Filtered", value);
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return "Test header filter";
            }
        };
    }

    private static Dictionary<String, Object> handlerProps(String path) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, path);
        return props;
    }

    private static Dictionary<String, Object> filterProps(String... patterns) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_FILTER_PATTERN,
                patterns.length == 1 ? patterns[0] : patterns);
        return props;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void filterIsInvokedForMatchingPath() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/filtered"));
        context.registerService(Filter.class, headerFilter("yes"), filterProps("/filtered"));

        HttpResponse<String> response = get("/filtered");
        assertEquals(200, response.statusCode());
        assertEquals("yes", response.headers().firstValue("X-Filtered").orElse(null));
    }

    @Test
    void filterIsNotInvokedForNonMatchingPath() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/a"));
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/b"));
        // Filter targets only /a
        context.registerService(Filter.class, headerFilter("filtered"), filterProps("/a"));

        HttpResponse<String> ra = get("/a");
        HttpResponse<String> rb = get("/b");

        assertEquals("filtered", ra.headers().firstValue("X-Filtered").orElse(null));
        assertNull(rb.headers().firstValue("X-Filtered").orElse(null));
    }

    @Test
    void wildcardFilterAppliesToAllContexts() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/x"));
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/y"));
        context.registerService(Filter.class, headerFilter("all"), filterProps("*"));

        assertEquals("all", get("/x").headers().firstValue("X-Filtered").orElse(null));
        assertEquals("all", get("/y").headers().firstValue("X-Filtered").orElse(null));
    }

    @Test
    void filterIsAppliedToContextCreatedAfterFilterRegistration() throws Exception {
        // Register filter BEFORE the handler.
        context.registerService(Filter.class, headerFilter("early"), filterProps("/late"));
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/late"));

        HttpResponse<String> response = get("/late");
        assertEquals("early", response.headers().firstValue("X-Filtered").orElse(null));
    }

    @Test
    void filterIsRemovedAfterServiceUnregistration() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/removable"));

        ServiceRegistration<Filter> reg =
                context.registerService(Filter.class, headerFilter("present"), filterProps("/removable"));

        assertEquals("present", get("/removable").headers().firstValue("X-Filtered").orElse(null));

        reg.unregister();

        assertNull(get("/removable").headers().firstValue("X-Filtered").orElse(null));
    }

    @Test
    void filterWithoutPatternIsRejected() throws Exception {
        // Register a filter with no pattern property.
        Dictionary<String, Object> props = new Hashtable<>();
        context.registerService(Filter.class, headerFilter("bad"), props);

        assertEquals(1, whiteboard.getFailedFilterDTOs().length);
        assertEquals(0, whiteboard.getFilterDTOs().length);
    }

    @Test
    void filterNameIsExposedInDTO() throws Exception {
        Dictionary<String, Object> props = filterProps("/named");
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_FILTER_NAME, "myFilter");
        context.registerService(Filter.class, headerFilter("n"), props);

        var dtos = whiteboard.getFilterDTOs();
        assertEquals(1, dtos.length);
        assertEquals("myFilter", dtos[0].filterName);
    }
}
