package org.laeubi.osgi.jdkhttp.impl;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntime;
import org.osgi.service.jdkhttp.runtime.dto.AuthenticatorDTO;
import org.osgi.service.jdkhttp.runtime.dto.FailedAuthenticatorDTO;
import org.osgi.service.jdkhttp.runtime.dto.FailedFilterDTO;
import org.osgi.service.jdkhttp.runtime.dto.FailedHandlerDTO;
import org.osgi.service.jdkhttp.runtime.dto.FilterDTO;
import org.osgi.service.jdkhttp.runtime.dto.HandlerDTO;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Core implementation of the JDK HttpServer Whiteboard.
 *
 * <p>
 * Create an instance, call {@link #start()}, and the whiteboard will
 * automatically track {@link HttpHandler}, {@link Filter} and
 * {@link Authenticator} services registered in the given
 * {@link BundleContext} and deploy them to an underlying JDK
 * {@link HttpServer}.
 * </p>
 *
 * <p>
 * A {@link JdkHttpServerRuntime} service is registered in the OSGi service
 * registry while the whiteboard is running.
 * </p>
 */
public class JdkHttpServerWhiteboard {

    /**
     * Comparator that orders service references from highest-ranking to
     * lowest-ranking. For equal rankings the lower service id wins (registered
     * first).
     */
    private static final Comparator<ServiceReference<?>> RANKING_COMPARATOR =
            Comparator.comparingInt((ServiceReference<?> ref) -> serviceRanking(ref))
                      .reversed()
                      .thenComparingLong(JdkHttpServerWhiteboard::serviceId);

    // -----------------------------------------------------------------------
    // Infrastructure
    // -----------------------------------------------------------------------

    private final BundleContext context;
    private final HttpServer httpServer;

    private ServiceTracker<HttpHandler, ServiceReference<HttpHandler>> handlerTracker;
    private ServiceTracker<Filter, ServiceReference<Filter>> filterTracker;
    private ServiceTracker<Authenticator, ServiceReference<Authenticator>> authenticatorTracker;
    private volatile ServiceRegistration<JdkHttpServerRuntime> runtimeRegistration;

    // -----------------------------------------------------------------------
    // Handler state  (guarded by handlerLock)
    // -----------------------------------------------------------------------

    private final Object handlerLock = new Object();

    /** contextPath → ordered list of competing refs (highest-ranking first). */
    private final Map<String, List<ServiceReference<HttpHandler>>> pathToRefs = new HashMap<>();
    /** serviceRef → active HttpContext (present only for the winning handler). */
    private final Map<ServiceReference<HttpHandler>, HttpContext> refToContext = new HashMap<>();
    /** serviceRef → HandlerDTO (present only for active handlers). */
    private final Map<ServiceReference<HttpHandler>, HandlerDTO> refToHandlerDTO = new HashMap<>();
    /** serviceRef → FailedHandlerDTO (present for invalid / shadowed handlers). */
    private final Map<ServiceReference<HttpHandler>, FailedHandlerDTO> refToFailedHandlerDTO = new HashMap<>();
    /** Track which handler refs have been obtained via getService(). */
    private final List<ServiceReference<HttpHandler>> obtainedHandlers = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Filter state  (guarded by filterLock)
    // -----------------------------------------------------------------------

    private final Object filterLock = new Object();

    /** serviceRef → (Filter instance, patterns). */
    private final Map<ServiceReference<Filter>, FilterEntry> filterEntries = new HashMap<>();
    /** serviceRef → FilterDTO. */
    private final Map<ServiceReference<Filter>, FilterDTO> refToFilterDTO = new HashMap<>();
    /** serviceRef → FailedFilterDTO. */
    private final Map<ServiceReference<Filter>, FailedFilterDTO> refToFailedFilterDTO = new HashMap<>();

    // -----------------------------------------------------------------------
    // Authenticator state  (guarded by authLock)
    // -----------------------------------------------------------------------

    private final Object authLock = new Object();

    /** serviceRef → (Authenticator instance, patterns). */
    private final Map<ServiceReference<Authenticator>, AuthenticatorEntry> authEntries = new HashMap<>();
    /** serviceRef → AuthenticatorDTO. */
    private final Map<ServiceReference<Authenticator>, AuthenticatorDTO> refToAuthDTO = new HashMap<>();
    /** serviceRef → FailedAuthenticatorDTO. */
    private final Map<ServiceReference<Authenticator>, FailedAuthenticatorDTO> refToFailedAuthDTO = new HashMap<>();

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /**
     * Creates a new whiteboard backed by an HTTP server bound to the given
     * host and port.
     *
     * <p>
     * Use port {@code 0} to let the OS pick a free port; call
     * {@link #getPort()} after {@link #start()} to retrieve the actual port.
     * </p>
     *
     * @param context the {@link BundleContext} used to track services.
     * @param host    the host/address to bind to (e.g. {@code "localhost"}).
     * @param port    the port to bind to ({@code 0} for OS-assigned).
     * @throws IOException if the underlying server socket cannot be created.
     */
    public JdkHttpServerWhiteboard(BundleContext context, String host, int port)
            throws IOException {
        this.context = context;
        httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        // Use virtual threads (Java 21) for request handling.
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Starts the underlying HTTP server, opens the service trackers, and
     * registers the {@link JdkHttpServerRuntime} service.
     */
    public void start() {
        handlerTracker = new ServiceTracker<>(context, HttpHandler.class, handlerCustomizer());
        filterTracker  = new ServiceTracker<>(context, Filter.class, filterCustomizer());
        authenticatorTracker = new ServiceTracker<>(context, Authenticator.class, authCustomizer());

        httpServer.start();

        // Register runtime service with endpoint information.
        InetSocketAddress bound = (InetSocketAddress) httpServer.getAddress();
        String endpoint = "http://" + bound.getHostString() + ":" + bound.getPort();
        Hashtable<String, Object> runtimeProps = new Hashtable<>();
        runtimeProps.put(JdkHttpWhiteboardConstants.JDK_HTTP_ENDPOINT, new String[]{endpoint});

        JdkHttpServerRuntimeImpl runtime = new JdkHttpServerRuntimeImpl(this);
        runtimeRegistration = context.registerService(
                JdkHttpServerRuntime.class, runtime, runtimeProps);

        // Open trackers *after* the server is running so that service
        // callbacks can safely create contexts immediately.
        handlerTracker.open();
        filterTracker.open();
        authenticatorTracker.open();
    }

    /**
     * Stops all trackers, unregisters the runtime service, and shuts down the
     * HTTP server.
     */
    public void stop() {
        if (handlerTracker != null) {
            handlerTracker.close();
        }
        if (filterTracker != null) {
            filterTracker.close();
        }
        if (authenticatorTracker != null) {
            authenticatorTracker.close();
        }
        ServiceRegistration<JdkHttpServerRuntime> reg = runtimeRegistration;
        if (reg != null) {
            runtimeRegistration = null;
            try {
                reg.unregister();
            } catch (IllegalStateException ignored) {
            }
        }
        httpServer.stop(0);
    }

    // -----------------------------------------------------------------------
    // Public query methods (used by JdkHttpServerRuntimeImpl)
    // -----------------------------------------------------------------------

    /** Returns the actual port the server is listening on. */
    public int getPort() {
        return ((InetSocketAddress) httpServer.getAddress()).getPort();
    }

    /** Returns the endpoint URLs of this server. */
    public String[] getEndpoints() {
        InetSocketAddress addr = (InetSocketAddress) httpServer.getAddress();
        return new String[]{"http://" + addr.getHostString() + ":" + addr.getPort()};
    }

    /** Returns the {@code service.id} of the runtime service, or {@code -1}. */
    public long getRuntimeServiceId() {
        ServiceRegistration<JdkHttpServerRuntime> reg = runtimeRegistration;
        if (reg != null) {
            return serviceId(reg.getReference());
        }
        return -1L;
    }

    public HandlerDTO[] getHandlerDTOs() {
        synchronized (handlerLock) {
            return refToHandlerDTO.values().toArray(new HandlerDTO[0]);
        }
    }

    public FailedHandlerDTO[] getFailedHandlerDTOs() {
        synchronized (handlerLock) {
            return refToFailedHandlerDTO.values().toArray(new FailedHandlerDTO[0]);
        }
    }

    public FilterDTO[] getFilterDTOs() {
        synchronized (filterLock) {
            return refToFilterDTO.values().toArray(new FilterDTO[0]);
        }
    }

    public FailedFilterDTO[] getFailedFilterDTOs() {
        synchronized (filterLock) {
            return refToFailedFilterDTO.values().toArray(new FailedFilterDTO[0]);
        }
    }

    public AuthenticatorDTO[] getAuthenticatorDTOs() {
        synchronized (authLock) {
            return refToAuthDTO.values().toArray(new AuthenticatorDTO[0]);
        }
    }

    public FailedAuthenticatorDTO[] getFailedAuthenticatorDTOs() {
        synchronized (authLock) {
            return refToFailedAuthDTO.values().toArray(new FailedAuthenticatorDTO[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Handler tracker customizer
    // -----------------------------------------------------------------------

    private ServiceTrackerCustomizer<HttpHandler, ServiceReference<HttpHandler>> handlerCustomizer() {
        return new ServiceTrackerCustomizer<>() {
            @Override
            public ServiceReference<HttpHandler> addingService(ServiceReference<HttpHandler> ref) {
                onHandlerAdded(ref);
                // Always return non-null so removedService is always called.
                return ref;
            }

            @Override
            public void modifiedService(ServiceReference<HttpHandler> ref,
                                        ServiceReference<HttpHandler> tracked) {
                synchronized (handlerLock) {
                    onHandlerRemovedLocked(ref);
                    onHandlerAddedLocked(ref);
                }
            }

            @Override
            public void removedService(ServiceReference<HttpHandler> ref,
                                       ServiceReference<HttpHandler> tracked) {
                synchronized (handlerLock) {
                    onHandlerRemovedLocked(ref);
                }
            }
        };
    }

    private void onHandlerAdded(ServiceReference<HttpHandler> ref) {
        synchronized (handlerLock) {
            onHandlerAddedLocked(ref);
        }
    }

    private void onHandlerAddedLocked(ServiceReference<HttpHandler> ref) {
        String contextPath = (String) ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_PATH);

        if (contextPath == null || contextPath.isEmpty() || !contextPath.startsWith("/")) {
            FailedHandlerDTO dto = new FailedHandlerDTO();
            dto.serviceId = serviceId(ref);
            dto.contextPath = contextPath;
            dto.failureReason = FailedHandlerDTO.FAILURE_REASON_INVALID_CONTEXT_PATH;
            refToFailedHandlerDTO.put(ref, dto);
            return;
        }

        List<ServiceReference<HttpHandler>> refs =
                pathToRefs.computeIfAbsent(contextPath, k -> new ArrayList<>());
        refs.add(ref);
        refs.sort(RANKING_COMPARATOR);

        if (refs.get(0) == ref) {
            // This ref is now the top-ranked candidate.
            // If there was a previously active handler, demote it.
            if (refs.size() > 1) {
                ServiceReference<HttpHandler> previous = refs.get(1);
                if (refToContext.containsKey(previous)) {
                    demoteHandlerLocked(previous, contextPath);
                }
            }
            activateHandlerLocked(ref, contextPath);
        } else {
            // Shadowed by an already-active higher-ranked handler.
            FailedHandlerDTO dto = new FailedHandlerDTO();
            dto.serviceId = serviceId(ref);
            dto.contextPath = contextPath;
            dto.contextName = (String) ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_NAME);
            dto.failureReason = FailedHandlerDTO.FAILURE_REASON_SHADOWED_BY_OTHER_HANDLER;
            refToFailedHandlerDTO.put(ref, dto);
        }
    }

    private void activateHandlerLocked(ServiceReference<HttpHandler> ref, String contextPath) {
        HttpHandler handler = context.getService(ref);
        obtainedHandlers.add(ref);
        try {
            HttpContext httpContext = httpServer.createContext(contextPath, handler);
            refToContext.put(ref, httpContext);

            HandlerDTO dto = new HandlerDTO();
            dto.serviceId = serviceId(ref);
            dto.contextPath = contextPath;
            dto.contextName = (String) ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_CONTEXT_NAME);
            refToHandlerDTO.put(ref, dto);

            // Apply any already-registered filters and authenticators.
            applyFiltersToContext(httpContext, contextPath);
            applyAuthenticatorToContext(httpContext, contextPath);

        } catch (Exception e) {
            // Context creation failed.
            if (obtainedHandlers.remove(ref)) {
                context.ungetService(ref);
            }
            List<ServiceReference<HttpHandler>> refs = pathToRefs.get(contextPath);
            if (refs != null) {
                refs.remove(ref);
            }
            FailedHandlerDTO dto = new FailedHandlerDTO();
            dto.serviceId = serviceId(ref);
            dto.contextPath = contextPath;
            dto.failureReason = FailedHandlerDTO.FAILURE_REASON_EXCEPTION_ON_INIT;
            refToFailedHandlerDTO.put(ref, dto);
        }
    }

    private void demoteHandlerLocked(ServiceReference<HttpHandler> ref, String contextPath) {
        HttpContext httpContext = refToContext.remove(ref);
        if (httpContext != null) {
            try {
                httpServer.removeContext(httpContext);
            } catch (Exception ignored) {
            }
        }
        HandlerDTO old = refToHandlerDTO.remove(ref);
        if (obtainedHandlers.remove(ref)) {
            context.ungetService(ref);
        }
        FailedHandlerDTO dto = new FailedHandlerDTO();
        dto.serviceId = old != null ? old.serviceId : serviceId(ref);
        dto.contextPath = contextPath;
        dto.contextName = old != null ? old.contextName : null;
        dto.failureReason = FailedHandlerDTO.FAILURE_REASON_SHADOWED_BY_OTHER_HANDLER;
        refToFailedHandlerDTO.put(ref, dto);
    }

    private void onHandlerRemovedLocked(ServiceReference<HttpHandler> ref) {
        // Determine context path from stored DTOs (handles modifiedService correctly).
        String contextPath = null;
        HandlerDTO active = refToHandlerDTO.get(ref);
        FailedHandlerDTO failed = refToFailedHandlerDTO.get(ref);
        if (active != null) {
            contextPath = active.contextPath;
        } else if (failed != null) {
            contextPath = failed.contextPath;
        }

        refToHandlerDTO.remove(ref);
        refToFailedHandlerDTO.remove(ref);

        boolean wasActive = refToContext.containsKey(ref);
        if (wasActive) {
            HttpContext ctx = refToContext.remove(ref);
            try {
                httpServer.removeContext(ctx);
            } catch (Exception ignored) {
            }
        }

        if (obtainedHandlers.remove(ref)) {
            context.ungetService(ref);
        }

        if (contextPath != null) {
            List<ServiceReference<HttpHandler>> refs = pathToRefs.get(contextPath);
            if (refs != null) {
                refs.remove(ref);
                // If this was the active handler, promote the next candidate.
                if (wasActive && !refs.isEmpty()) {
                    ServiceReference<HttpHandler> next = refs.get(0);
                    // Remove its failed entry so activateHandlerLocked can add an active one.
                    refToFailedHandlerDTO.remove(next);
                    activateHandlerLocked(next, contextPath);
                }
                if (refs.isEmpty()) {
                    pathToRefs.remove(contextPath);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Filter tracker customizer
    // -----------------------------------------------------------------------

    private ServiceTrackerCustomizer<Filter, ServiceReference<Filter>> filterCustomizer() {
        return new ServiceTrackerCustomizer<>() {
            @Override
            public ServiceReference<Filter> addingService(ServiceReference<Filter> ref) {
                onFilterAdded(ref);
                return ref;
            }

            @Override
            public void modifiedService(ServiceReference<Filter> ref,
                                        ServiceReference<Filter> tracked) {
                onFilterRemoved(ref);
                onFilterAdded(ref);
            }

            @Override
            public void removedService(ServiceReference<Filter> ref,
                                       ServiceReference<Filter> tracked) {
                onFilterRemoved(ref);
            }
        };
    }

    private void onFilterAdded(ServiceReference<Filter> ref) {
        String[] patterns = extractPatterns(
                ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_FILTER_PATTERN));

        if (patterns == null || patterns.length == 0) {
            synchronized (filterLock) {
                FailedFilterDTO dto = new FailedFilterDTO();
                dto.serviceId = serviceId(ref);
                dto.failureReason = FailedHandlerDTO.FAILURE_REASON_INVALID_CONTEXT_PATH;
                refToFailedFilterDTO.put(ref, dto);
            }
            return;
        }

        Filter filter = context.getService(ref);
        if (filter == null) {
            return;
        }

        FilterEntry entry = new FilterEntry(filter, patterns);

        synchronized (filterLock) {
            filterEntries.put(ref, entry);

            FilterDTO dto = new FilterDTO();
            dto.serviceId = serviceId(ref);
            dto.patterns = patterns;
            dto.filterName = (String) ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_FILTER_NAME);
            refToFilterDTO.put(ref, dto);
        }

        // Add the filter to all matching active contexts.
        synchronized (handlerLock) {
            for (Map.Entry<ServiceReference<HttpHandler>, HttpContext> e : refToContext.entrySet()) {
                String path = refToHandlerDTO.containsKey(e.getKey())
                        ? refToHandlerDTO.get(e.getKey()).contextPath : null;
                if (path != null && entry.matchesPath(path)) {
                    e.getValue().getFilters().add(filter);
                }
            }
        }
    }

    private void onFilterRemoved(ServiceReference<Filter> ref) {
        FilterEntry entry;
        synchronized (filterLock) {
            entry = filterEntries.remove(ref);
            refToFilterDTO.remove(ref);
            refToFailedFilterDTO.remove(ref);
        }
        if (entry == null) {
            context.ungetService(ref);
            return;
        }

        // Remove the filter from all active contexts.
        synchronized (handlerLock) {
            for (Map.Entry<ServiceReference<HttpHandler>, HttpContext> e : refToContext.entrySet()) {
                e.getValue().getFilters().remove(entry.filter());
            }
        }
        context.ungetService(ref);
    }

    // -----------------------------------------------------------------------
    // Authenticator tracker customizer
    // -----------------------------------------------------------------------

    private ServiceTrackerCustomizer<Authenticator, ServiceReference<Authenticator>> authCustomizer() {
        return new ServiceTrackerCustomizer<>() {
            @Override
            public ServiceReference<Authenticator> addingService(ServiceReference<Authenticator> ref) {
                onAuthAdded(ref);
                return ref;
            }

            @Override
            public void modifiedService(ServiceReference<Authenticator> ref,
                                        ServiceReference<Authenticator> tracked) {
                onAuthRemoved(ref);
                onAuthAdded(ref);
            }

            @Override
            public void removedService(ServiceReference<Authenticator> ref,
                                       ServiceReference<Authenticator> tracked) {
                onAuthRemoved(ref);
            }
        };
    }

    private void onAuthAdded(ServiceReference<Authenticator> ref) {
        String[] patterns = extractPatterns(
                ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_AUTHENTICATOR_PATTERN));

        if (patterns == null || patterns.length == 0) {
            synchronized (authLock) {
                FailedAuthenticatorDTO dto = new FailedAuthenticatorDTO();
                dto.serviceId = serviceId(ref);
                dto.failureReason = FailedHandlerDTO.FAILURE_REASON_INVALID_CONTEXT_PATH;
                refToFailedAuthDTO.put(ref, dto);
            }
            return;
        }

        Authenticator auth = context.getService(ref);
        if (auth == null) {
            return;
        }

        AuthenticatorEntry entry = new AuthenticatorEntry(auth, patterns);

        synchronized (authLock) {
            authEntries.put(ref, entry);

            AuthenticatorDTO dto = new AuthenticatorDTO();
            dto.serviceId = serviceId(ref);
            dto.patterns = patterns;
            dto.realm = (String) ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_AUTHENTICATOR_REALM);
            refToAuthDTO.put(ref, dto);
        }

        // Apply the authenticator to all matching active contexts.
        synchronized (handlerLock) {
            for (Map.Entry<ServiceReference<HttpHandler>, HttpContext> e : refToContext.entrySet()) {
                String path = refToHandlerDTO.containsKey(e.getKey())
                        ? refToHandlerDTO.get(e.getKey()).contextPath : null;
                if (path != null && entry.matchesPath(path)) {
                    e.getValue().setAuthenticator(auth);
                }
            }
        }
    }

    private void onAuthRemoved(ServiceReference<Authenticator> ref) {
        AuthenticatorEntry entry;
        synchronized (authLock) {
            entry = authEntries.remove(ref);
            refToAuthDTO.remove(ref);
            refToFailedAuthDTO.remove(ref);
        }
        if (entry == null) {
            context.ungetService(ref);
            return;
        }

        // Remove the authenticator from all active contexts it was applied to.
        synchronized (handlerLock) {
            for (Map.Entry<ServiceReference<HttpHandler>, HttpContext> e : refToContext.entrySet()) {
                String path = refToHandlerDTO.containsKey(e.getKey())
                        ? refToHandlerDTO.get(e.getKey()).contextPath : null;
                if (path != null && entry.matchesPath(path)) {
                    e.getValue().setAuthenticator(null);
                }
            }
        }
        context.ungetService(ref);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Apply all registered filters to a newly created context. */
    private void applyFiltersToContext(HttpContext httpContext, String contextPath) {
        synchronized (filterLock) {
            for (FilterEntry fe : filterEntries.values()) {
                if (fe.matchesPath(contextPath)) {
                    httpContext.getFilters().add(fe.filter());
                }
            }
        }
    }

    /** Apply the first matching authenticator to a newly created context. */
    private void applyAuthenticatorToContext(HttpContext httpContext, String contextPath) {
        synchronized (authLock) {
            for (AuthenticatorEntry ae : authEntries.values()) {
                if (ae.matchesPath(contextPath)) {
                    httpContext.setAuthenticator(ae.authenticator());
                    break; // Only one authenticator per context.
                }
            }
        }
    }

    /**
     * Extracts the pattern(s) from a service property value which may be a
     * {@code String} or {@code String[]}.
     */
    static String[] extractPatterns(Object value) {
        if (value instanceof String s) {
            return new String[]{s};
        }
        if (value instanceof String[] arr) {
            return arr;
        }
        return null;
    }

    private static int serviceRanking(ServiceReference<?> ref) {
        Object r = ref.getProperty(Constants.SERVICE_RANKING);
        return r instanceof Integer i ? i : 0;
    }

    private static long serviceId(ServiceReference<?> ref) {
        Object id = ref.getProperty(Constants.SERVICE_ID);
        return id instanceof Long l ? l : 0L;
    }

    // -----------------------------------------------------------------------
    // Inner records
    // -----------------------------------------------------------------------

    /**
     * Holds a registered {@link Filter} and its configured path patterns.
     */
    record FilterEntry(Filter filter, String[] patterns) {
        boolean matchesPath(String contextPath) {
            return matchesAny(patterns, contextPath);
        }
    }

    /**
     * Holds a registered {@link Authenticator} and its configured path
     * patterns.
     */
    record AuthenticatorEntry(Authenticator authenticator, String[] patterns) {
        boolean matchesPath(String contextPath) {
            return matchesAny(patterns, contextPath);
        }
    }

    /**
     * Returns {@code true} if {@code contextPath} matches at least one of the
     * given patterns.
     *
     * <p>
     * Pattern rules:
     * </p>
     * <ul>
     * <li>{@code *} matches any context path.</li>
     * <li>A pattern ending with {@code /*} matches the path prefix (e.g.
     * {@code /api/*} matches {@code /api} and {@code /api/users}).</li>
     * <li>Otherwise the pattern must be an exact match.</li>
     * </ul>
     */
    static boolean matchesAny(String[] patterns, String contextPath) {
        for (String pattern : patterns) {
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                if (contextPath.equals(prefix) || contextPath.startsWith(prefix + "/")) {
                    return true;
                }
            } else if (pattern.equals(contextPath)) {
                return true;
            }
        }
        return false;
    }
}
