package org.laeubi.osgi.jdkhttp.test;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that verify basic {@code HttpHandler} registration, routing, and
 * deregistration behaviour of the whiteboard.
 */
class WhiteboardHandlerTest extends AbstractWhiteboardTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static HttpHandler textHandler(String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        };
    }

    private static Dictionary<String, Object> handlerProps(String path) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, path);
        return props;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void handlerRespondsToRequests() throws Exception {
        context.registerService(HttpHandler.class, textHandler("Hello, World!"), handlerProps("/hello"));

        HttpResponse<String> response = get("/hello");

        assertEquals(200, response.statusCode());
        assertEquals("Hello, World!", response.body());
    }

    @Test
    void handlerIsRemovedAfterServiceUnregistration() throws Exception {
        ServiceRegistration<HttpHandler> reg =
                context.registerService(HttpHandler.class, textHandler("up"), handlerProps("/api"));

        assertEquals(200, get("/api").statusCode());

        reg.unregister();

        // After removal the server should no longer route to "/api".
        // The JDK server returns a 404 when no context exists for the path.
        assertEquals(404, get("/api").statusCode());
    }

    @Test
    void multipleHandlersOnDifferentPaths() throws Exception {
        context.registerService(HttpHandler.class, textHandler("one"), handlerProps("/one"));
        context.registerService(HttpHandler.class, textHandler("two"), handlerProps("/two"));

        assertEquals("one", get("/one").body());
        assertEquals("two", get("/two").body());
    }

    @Test
    void handlerWithoutContextPathIsRejected() throws Exception {
        // Register a handler without the required property.
        Dictionary<String, Object> props = new Hashtable<>();
        context.registerService(HttpHandler.class, textHandler("bad"), props);

        // A request to any path should not find the bad handler.
        // The exact status depends on whether another default handler exists.
        // We only assert that the whiteboard reports a failed registration.
        var dto = whiteboard.getRuntimeServiceId(); // ensure whiteboard is alive
        var failed = whiteboard.getFailedHandlerDTOs();
        assertEquals(1, failed.length);
        assertEquals(
                org.osgi.service.jdkhttp.runtime.dto.FailedHandlerDTO.FAILURE_REASON_INVALID_CONTEXT_PATH,
                failed[0].failureReason);
    }

    @Test
    void higherRankedHandlerWinsForSamePath() throws Exception {
        Dictionary<String, Object> lowProps = new Hashtable<>();
        lowProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, "/ranked");
        lowProps.put(org.osgi.framework.Constants.SERVICE_RANKING, 10);

        Dictionary<String, Object> highProps = new Hashtable<>();
        highProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, "/ranked");
        highProps.put(org.osgi.framework.Constants.SERVICE_RANKING, 100);

        context.registerService(HttpHandler.class, textHandler("low"),  lowProps);
        context.registerService(HttpHandler.class, textHandler("high"), highProps);

        // The high-ranked handler should serve requests.
        assertEquals("high", get("/ranked").body());

        // One handler must be in the failed list as shadowed.
        var failed = whiteboard.getFailedHandlerDTOs();
        assertEquals(1, failed.length);
        assertEquals(
                org.osgi.service.jdkhttp.runtime.dto.FailedHandlerDTO.FAILURE_REASON_SHADOWED_BY_OTHER_HANDLER,
                failed[0].failureReason);
    }

    @Test
    void shadowedHandlerPromotedAfterWinnerIsRemoved() throws Exception {
        Dictionary<String, Object> lowProps = new Hashtable<>();
        lowProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, "/promoted");
        lowProps.put(org.osgi.framework.Constants.SERVICE_RANKING, 10);

        Dictionary<String, Object> highProps = new Hashtable<>();
        highProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, "/promoted");
        highProps.put(org.osgi.framework.Constants.SERVICE_RANKING, 100);

        context.registerService(HttpHandler.class, textHandler("low"),  lowProps);
        ServiceRegistration<HttpHandler> highReg =
                context.registerService(HttpHandler.class, textHandler("high"), highProps);

        assertEquals("high", get("/promoted").body());

        // Remove the winner; the previously-shadowed handler should take over.
        highReg.unregister();
        assertEquals("low", get("/promoted").body());

        // No more failed registrations.
        assertEquals(0, whiteboard.getFailedHandlerDTOs().length);
    }

    @Test
    void handlerResponseBodyIsCorrect() throws Exception {
        String body = "Detailed response with UTF-8: ñ";
        context.registerService(HttpHandler.class,
                exchange -> {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                },
                handlerProps("/utf8"));

        HttpResponse<String> response = get("/utf8");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ñ"));
    }

    @Test
    void handlerCanReturn404() throws Exception {
        context.registerService(HttpHandler.class,
                exchange -> exchange.sendResponseHeaders(404, -1),
                handlerProps("/notfound"));

        assertEquals(404, get("/notfound").statusCode());
    }
}
