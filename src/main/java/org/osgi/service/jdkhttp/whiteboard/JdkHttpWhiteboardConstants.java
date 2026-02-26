package org.osgi.service.jdkhttp.whiteboard;

/**
 * Defines the service property names for the JDK HttpServer Whiteboard
 * Specification.
 *
 * <p>
 * Handlers, filters, and authenticators are registered as OSGi services with
 * these properties to configure how the whiteboard implementation picks them
 * up.
 * </p>
 */
public final class JdkHttpWhiteboardConstants {

    // -----------------------------------------------------------------------
    // Handler properties
    // -----------------------------------------------------------------------

    /**
     * Service property for the context path of an {@code HttpHandler} service.
     * <p>
     * The value must be a {@code String} starting with {@code /}. This property
     * is <em>required</em> for a handler to be registered.
     * </p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_CONTEXT_PATH = "osgi.http.jdk.context.path";

    /**
     * Optional service property for a human-readable name of an
     * {@code HttpHandler} context.
     * <p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_CONTEXT_NAME = "osgi.http.jdk.context.name";

    // -----------------------------------------------------------------------
    // Filter properties
    // -----------------------------------------------------------------------

    /**
     * Service property for the context path patterns of a {@code Filter}
     * service.
     * <p>
     * The value is a {@code String} or {@code String[]} of context path
     * patterns. Use {@code *} to match all contexts.
     * </p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_FILTER_PATTERN = "osgi.http.jdk.filter.pattern";

    /**
     * Optional service property for a human-readable name of a {@code Filter}
     * service.
     * <p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_FILTER_NAME = "osgi.http.jdk.filter.name";

    // -----------------------------------------------------------------------
    // Authenticator properties
    // -----------------------------------------------------------------------

    /**
     * Service property for the context path patterns of an
     * {@code Authenticator} service.
     * <p>
     * The value is a {@code String} or {@code String[]} of context path
     * patterns.
     * </p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_AUTHENTICATOR_PATTERN = "osgi.http.jdk.authenticator.pattern";

    /**
     * Optional service property for the authentication realm of an
     * {@code Authenticator} service.
     * <p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_AUTHENTICATOR_REALM = "osgi.http.jdk.authenticator.realm";

    // -----------------------------------------------------------------------
    // Shared / targeting properties
    // -----------------------------------------------------------------------

    /**
     * Optional service property used to target a specific whiteboard
     * implementation.
     * <p>
     * The value is an LDAP filter expression that is matched against the
     * properties of the {@code JdkHttpServerRuntime} service.
     * </p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_WHITEBOARD_TARGET = "osgi.http.jdk.target";

    // -----------------------------------------------------------------------
    // Runtime properties
    // -----------------------------------------------------------------------

    /**
     * Service property of the {@code JdkHttpServerRuntime} service that lists
     * the endpoints where the server is listening.
     * <p>
     * The value is a {@code String[]} of endpoint URLs, e.g.
     * {@code "http://localhost:8080"}.
     * </p>
     * Value: {@value}
     */
    public static final String JDK_HTTP_ENDPOINT = "osgi.http.jdk.endpoint";

    // -----------------------------------------------------------------------
    // Capability / requirement constants
    // -----------------------------------------------------------------------

    /**
     * The name of the OSGi implementation capability provided by this
     * whiteboard.
     * Value: {@value}
     */
    public static final String JDK_HTTP_WHITEBOARD_IMPLEMENTATION = "osgi.http.jdk";

    /**
     * The version of the JDK HttpServer Whiteboard Specification implemented
     * by this bundle.
     * Value: {@value}
     */
    public static final String JDK_HTTP_WHITEBOARD_SPECIFICATION_VERSION = "1.0";

    private JdkHttpWhiteboardConstants() {
    }
}
