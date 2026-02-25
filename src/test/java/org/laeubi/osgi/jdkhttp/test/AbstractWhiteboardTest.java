package org.laeubi.osgi.jdkhttp.test;

import org.apache.felix.framework.Felix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.laeubi.osgi.jdkhttp.impl.JdkHttpServerWhiteboard;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for whiteboard tests.
 *
 * <p>
 * Sets up an embedded Apache Felix OSGi framework and a
 * {@link JdkHttpServerWhiteboard} bound to a random free port.  Subclasses
 * have access to {@link #context} (the system bundle context) and
 * {@link #whiteboard} (the running whiteboard instance).
 * </p>
 */
abstract class AbstractWhiteboardTest {

    /** The OSGi bundle context exposed to subclasses. */
    protected BundleContext context;

    /** The running whiteboard instance exposed to subclasses. */
    protected JdkHttpServerWhiteboard whiteboard;

    /** A pre-configured HTTP client for making test requests. */
    protected HttpClient http;

    private Felix felix;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> config = new HashMap<>();
        // Suppress Felix logging during tests.
        config.put("felix.log.level", "1");
        // Use a temporary cache directory.
        config.put("org.osgi.framework.storage", System.getProperty("java.io.tmpdir")
                + "/felix-cache-" + System.nanoTime());
        config.put("org.osgi.framework.storage.clean", "onFirstInit");

        felix = new Felix(config);
        felix.init();
        felix.start();

        context = felix.getBundleContext();

        // Bind to a random free port (0 → OS picks one).
        whiteboard = new JdkHttpServerWhiteboard(context, "localhost", 0);
        whiteboard.start();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (whiteboard != null) {
            whiteboard.stop();
        }
        if (felix != null) {
            felix.stop();
            felix.waitForStop(5_000);
        }
    }

    /** Returns the base URL of the running server, e.g. {@code http://localhost:54321}. */
    protected String baseUrl() {
        return "http://localhost:" + whiteboard.getPort();
    }

    /**
     * Sends a GET request to the given path and returns the response.
     *
     * @throws IOException          on I/O errors.
     * @throws InterruptedException if the thread is interrupted.
     */
    protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
