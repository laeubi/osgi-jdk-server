package org.laeubi.osgi.jdkhttp.test;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;

import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that verify {@code Authenticator} service registration, selection,
 * and invocation: exactly one authenticator (the matching one with the
 * highest service ranking, lowest {@code service.id} as tie-break) must be
 * applied to a given context.
 */
class WhiteboardAuthenticatorTest extends AbstractWhiteboardTest {

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

    /** An authenticator that always succeeds, tagging the request as {@code name}. */
    private static Authenticator namedAuthenticator(String name) {
        return new Authenticator() {
            @Override
            public Result authenticate(HttpExchange exchange) {
                exchange.getResponseHeaders().add("X-Auth", name);
                return new Success(new HttpPrincipal(name, "realm"));
            }
        };
    }

    private static Dictionary<String, Object> handlerProps(String path) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH, path);
        return props;
    }

    private static Dictionary<String, Object> authProps(int ranking, String... patterns) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_AUTHENTICATOR_PATTERN,
                patterns.length == 1 ? patterns[0] : patterns);
        props.put(Constants.SERVICE_RANKING, ranking);
        return props;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void highestRankingAuthenticatorIsSelected() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/secure"));
        // Register out of ranking order to make sure the highest ranking
        // (not registration order) determines the selected authenticator.
        context.registerService(Authenticator.class, namedAuthenticator("low"), authProps(0, "/secure"));
        context.registerService(Authenticator.class, namedAuthenticator("high"), authProps(10, "/secure"));
        context.registerService(Authenticator.class, namedAuthenticator("mid"), authProps(5, "/secure"));

        HttpResponse<String> response = get("/secure");

        assertEquals("high", response.headers().firstValue("X-Auth").orElse(null));
    }

    @Test
    void equalRankingAuthenticatorTieBreaksOnLowestServiceId() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/tie"));
        // Same ranking (default 0): the one with the lowest service.id
        // (i.e. registered first) must be selected.
        context.registerService(Authenticator.class, namedAuthenticator("first"), authProps(0, "/tie"));
        context.registerService(Authenticator.class, namedAuthenticator("second"), authProps(0, "/tie"));

        HttpResponse<String> response = get("/tie");

        assertEquals("first", response.headers().firstValue("X-Auth").orElse(null));
    }

    @Test
    void nextBestAuthenticatorIsSelectedAfterRemoval() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/fallback"));
        context.registerService(Authenticator.class, namedAuthenticator("low"), authProps(0, "/fallback"));
        ServiceRegistration<Authenticator> highReg = context.registerService(
                Authenticator.class, namedAuthenticator("high"), authProps(10, "/fallback"));

        assertEquals("high", get("/fallback").headers().firstValue("X-Auth").orElse(null));

        highReg.unregister();

        assertEquals("low", get("/fallback").headers().firstValue("X-Auth").orElse(null));
    }

    @Test
    void authenticatorIsNotAppliedToNonMatchingContext() throws Exception {
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/a"));
        context.registerService(HttpHandler.class, echoHandler(), handlerProps("/b"));
        context.registerService(Authenticator.class, namedAuthenticator("only-a"), authProps(0, "/a"));

        assertEquals("only-a", get("/a").headers().firstValue("X-Auth").orElse(null));
        assertNull(get("/b").headers().firstValue("X-Auth").orElse(null));
    }
}
