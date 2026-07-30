package org.laeubi.osgi.jdkhttp.impl;

import com.sun.net.httpserver.Authenticator;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntime;
import org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntimeConstants;
import org.osgi.service.jdkhttp.runtime.dto.AuthenticatorDTO;
import org.osgi.service.jdkhttp.runtime.dto.DTOConstants;
import org.osgi.service.jdkhttp.runtime.dto.FailedAuthenticatorDTO;
import org.osgi.service.jdkhttp.runtime.dto.FailedFilterDTO;
import org.osgi.service.jdkhttp.runtime.dto.FailedHandlerDTO;
import org.osgi.service.jdkhttp.runtime.dto.FailedResourceDTO;
import org.osgi.service.jdkhttp.runtime.dto.FilterDTO;
import org.osgi.service.jdkhttp.runtime.dto.HandlerDTO;
import org.osgi.service.jdkhttp.runtime.dto.RequestInfoDTO;
import org.osgi.service.jdkhttp.runtime.dto.ResourceDTO;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
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
    private ServiceTracker<Object, ServiceReference<Object>> resourceTracker;
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
    // Resource state  (guarded by resourceLock; context path bookkeeping is
    // shared with handlers and guarded by handlerLock)
    // -----------------------------------------------------------------------

    private final Object resourceLock = new Object();

    /** context path → the resource ref currently bound to it. */
    private final Map<String, ServiceReference<Object>> resourcePathToRef = new HashMap<>();
    /** serviceRef → the HttpContexts created for its patterns. */
    private final Map<ServiceReference<Object>, List<HttpContext>> refToResourceContexts = new HashMap<>();
    /** serviceRef → ResourceDTO. */
    private final Map<ServiceReference<Object>, ResourceDTO> refToResourceDTO = new HashMap<>();
    /** serviceRef → FailedResourceDTO. */
    private final Map<ServiceReference<Object>, FailedResourceDTO> refToFailedResourceDTO = new HashMap<>();

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
        try {
            org.osgi.framework.Filter resourceFilter = context.createFilter(
                    "(" + JdkHttpWhiteboardConstants.JDK_HTTP_RESOURCE_PATTERN + "=*)");
            resourceTracker = new ServiceTracker<>(context, resourceFilter, resourceCustomizer());
        } catch (InvalidSyntaxException e) {
            // JDK_HTTP_RESOURCE_PATTERN is a constant; the filter is always valid.
            throw new IllegalStateException(e);
        }

        httpServer.start();

        // Register runtime service with endpoint information.
        InetSocketAddress bound = (InetSocketAddress) httpServer.getAddress();
        String endpoint = "http://" + bound.getHostString() + ":" + bound.getPort();
        Hashtable<String, Object> runtimeProps = new Hashtable<>();
        runtimeProps.put(JdkHttpServerRuntimeConstants.JDK_HTTP_ENDPOINT, new String[]{endpoint});

        JdkHttpServerRuntimeImpl runtime = new JdkHttpServerRuntimeImpl(this);
        runtimeRegistration = context.registerService(
                JdkHttpServerRuntime.class, runtime, runtimeProps);

        // Open trackers *after* the server is running so that service
        // callbacks can safely create contexts immediately.
        handlerTracker.open();
        filterTracker.open();
        authenticatorTracker.open();
        resourceTracker.open();
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
        if (resourceTracker != null) {
            resourceTracker.close();
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

    public ResourceDTO[] getResourceDTOs() {
        synchronized (resourceLock) {
            return refToResourceDTO.values().toArray(new ResourceDTO[0]);
        }
    }

    public FailedResourceDTO[] getFailedResourceDTOs() {
        synchronized (resourceLock) {
            return refToFailedResourceDTO.values().toArray(new FailedResourceDTO[0]);
        }
    }

    /**
     * Calculates how a request to the given path would be processed: the
     * handler or resource context whose path is the longest matching prefix,
     * together with the filters and authenticator applied to that context.
     */
    public RequestInfoDTO calculateRequestInfoDTO(String path) {
        RequestInfoDTO dto = new RequestInfoDTO();
        dto.path = path;

        String bestHandlerPath = null;
        HandlerDTO matchedHandler = null;
        synchronized (handlerLock) {
            for (HandlerDTO h : refToHandlerDTO.values()) {
                if (matchesPrefix(h.contextPath, path)
                        && (bestHandlerPath == null || h.contextPath.length() > bestHandlerPath.length())) {
                    bestHandlerPath = h.contextPath;
                    matchedHandler = h;
                }
            }
        }

        String bestResourcePath = null;
        ResourceDTO matchedResource = null;
        synchronized (resourceLock) {
            for (ResourceDTO r : refToResourceDTO.values()) {
                for (String pattern : r.patterns) {
                    if (matchesPrefix(pattern, path)
                            && (bestResourcePath == null || pattern.length() > bestResourcePath.length())) {
                        bestResourcePath = pattern;
                        matchedResource = r;
                    }
                }
            }
        }

        String matchedContextPath;
        if (bestResourcePath != null
                && (bestHandlerPath == null || bestResourcePath.length() > bestHandlerPath.length())) {
            dto.resourceDTO = matchedResource;
            matchedContextPath = bestResourcePath;
        } else if (bestHandlerPath != null) {
            dto.handlerDTO = matchedHandler;
            matchedContextPath = bestHandlerPath;
        } else {
            matchedContextPath = null;
        }

        List<FilterDTO> matchedFilters = new ArrayList<>();
        if (matchedContextPath != null) {
            synchronized (filterLock) {
                for (Map.Entry<ServiceReference<Filter>, FilterEntry> e : filterEntries.entrySet()) {
                    if (e.getValue().matchesPath(matchedContextPath)) {
                        matchedFilters.add(refToFilterDTO.get(e.getKey()));
                    }
                }
            }
            synchronized (authLock) {
                for (Map.Entry<ServiceReference<Authenticator>, AuthenticatorEntry> e : authEntries.entrySet()) {
                    if (e.getValue().matchesPath(matchedContextPath)) {
                        dto.authenticatorDTO = refToAuthDTO.get(e.getKey());
                        break;
                    }
                }
            }
        }
        dto.filterDTOs = matchedFilters.toArray(new FilterDTO[0]);

        return dto;
    }

    /**
     * Returns {@code true} if {@code requestPath} is served by a context
     * registered at {@code contextPath} (i.e. {@code contextPath} is
     * {@code "/"}, an exact match, or a proper path prefix of the request).
     */
    private static boolean matchesPrefix(String contextPath, String requestPath) {
        if ("/".equals(contextPath)) {
            return true;
        }
        return requestPath.equals(contextPath) || requestPath.startsWith(contextPath + "/");
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
            dto.failureReason = DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
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
            dto.failureReason = DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
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
            dto.failureReason = DTOConstants.FAILURE_REASON_EXCEPTION_ON_INIT;
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
        dto.failureReason = DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
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
    // Resource tracker customizer
    // -----------------------------------------------------------------------

    private ServiceTrackerCustomizer<Object, ServiceReference<Object>> resourceCustomizer() {
        return new ServiceTrackerCustomizer<>() {
            @Override
            public ServiceReference<Object> addingService(ServiceReference<Object> ref) {
                onResourceAdded(ref);
                return ref;
            }

            @Override
            public void modifiedService(ServiceReference<Object> ref,
                                        ServiceReference<Object> tracked) {
                synchronized (resourceLock) {
                    onResourceRemovedLocked(ref);
                    onResourceAddedLocked(ref);
                }
            }

            @Override
            public void removedService(ServiceReference<Object> ref,
                                       ServiceReference<Object> tracked) {
                synchronized (resourceLock) {
                    onResourceRemovedLocked(ref);
                }
            }
        };
    }

    private void onResourceAdded(ServiceReference<Object> ref) {
        synchronized (resourceLock) {
            onResourceAddedLocked(ref);
        }
    }

    private void onResourceAddedLocked(ServiceReference<Object> ref) {
        String[] patterns = extractPatterns(
                ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_RESOURCE_PATTERN));
        String prefix = (String) ref.getProperty(JdkHttpWhiteboardConstants.JDK_HTTP_RESOURCE_PREFIX);

        boolean validPatterns = patterns != null && patterns.length > 0
                && Arrays.stream(patterns).allMatch(p -> p != null && p.startsWith("/"));
        boolean validPrefix = prefix != null && ("/".equals(prefix) || !prefix.endsWith("/"));

        if (!validPatterns || !validPrefix) {
            FailedResourceDTO dto = new FailedResourceDTO();
            dto.serviceId = serviceId(ref);
            dto.patterns = patterns != null ? patterns : new String[0];
            dto.prefix = prefix;
            dto.failureReason = DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
            refToFailedResourceDTO.put(ref, dto);
            return;
        }

        // Simple first-registered-wins collision check against both handler
        // context paths and other resource patterns (no ranking-based
        // shadow/promote logic, to keep this whiteboard lightweight).
        for (String pattern : patterns) {
            if (pathToRefs.containsKey(pattern) || resourcePathToRef.containsKey(pattern)) {
                FailedResourceDTO dto = new FailedResourceDTO();
                dto.serviceId = serviceId(ref);
                dto.patterns = patterns;
                dto.prefix = prefix;
                dto.failureReason = DTOConstants.FAILURE_REASON_SHADOWED_BY_OTHER_SERVICE;
                refToFailedResourceDTO.put(ref, dto);
                return;
            }
        }

        Object service = context.getService(ref);
        if (service == null) {
            FailedResourceDTO dto = new FailedResourceDTO();
            dto.serviceId = serviceId(ref);
            dto.patterns = patterns;
            dto.prefix = prefix;
            dto.failureReason = DTOConstants.FAILURE_REASON_SERVICE_NOT_GETTABLE;
            refToFailedResourceDTO.put(ref, dto);
            return;
        }

        Bundle bundle = ref.getBundle();
        HttpHandler resourceHandler = createResourceHandler(bundle, prefix);
        List<HttpContext> contexts = new ArrayList<>();
        try {
            for (String pattern : patterns) {
                HttpContext httpContext = httpServer.createContext(pattern, resourceHandler);
                contexts.add(httpContext);
                applyFiltersToContext(httpContext, pattern);
                applyAuthenticatorToContext(httpContext, pattern);
            }
        } catch (Exception e) {
            for (HttpContext httpContext : contexts) {
                try {
                    httpServer.removeContext(httpContext);
                } catch (Exception ignored) {
                }
            }
            context.ungetService(ref);
            FailedResourceDTO dto = new FailedResourceDTO();
            dto.serviceId = serviceId(ref);
            dto.patterns = patterns;
            dto.prefix = prefix;
            dto.failureReason = DTOConstants.FAILURE_REASON_EXCEPTION_ON_INIT;
            refToFailedResourceDTO.put(ref, dto);
            return;
        }

        for (String pattern : patterns) {
            resourcePathToRef.put(pattern, ref);
        }
        refToResourceContexts.put(ref, contexts);

        ResourceDTO dto = new ResourceDTO();
        dto.serviceId = serviceId(ref);
        dto.patterns = patterns;
        dto.prefix = prefix;
        refToResourceDTO.put(ref, dto);
    }

    private void onResourceRemovedLocked(ServiceReference<Object> ref) {
        refToFailedResourceDTO.remove(ref);
        ResourceDTO removed = refToResourceDTO.remove(ref);

        List<HttpContext> contexts = refToResourceContexts.remove(ref);
        if (contexts != null) {
            for (HttpContext httpContext : contexts) {
                try {
                    httpServer.removeContext(httpContext);
                } catch (Exception ignored) {
                }
            }
        }

        if (removed != null) {
            for (String pattern : removed.patterns) {
                resourcePathToRef.remove(pattern, ref);
            }
            context.ungetService(ref);
        }
    }

    /**
     * Creates an {@link HttpHandler} that serves entries from the given
     * bundle below {@code prefix}, defaulting an empty/{@code "/"} relative
     * path to {@code index.html} and responding {@code 404} when no matching
     * entry exists.
     */
    private static HttpHandler createResourceHandler(Bundle bundle, String prefix) {
        return exchange -> {
            String requestPath = exchange.getRequestURI().getPath();
            String contextPath = exchange.getHttpContext().getPath();
            String relative = requestPath.substring(contextPath.length());
            if (relative.isEmpty() || "/".equals(relative)) {
                relative = "/index.html";
            }
            if (!relative.startsWith("/")) {
                relative = "/" + relative;
            }

            java.net.URL entry = bundle.getEntry(prefix + relative);
            if (entry == null) {
                byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                return;
            }

            String contentType = URLConnection.guessContentTypeFromName(entry.getPath());
            exchange.getResponseHeaders().set("Content-Type",
                    contentType != null ? contentType : "application/octet-stream");

            try (InputStream in = entry.openStream()) {
                byte[] body = in.readAllBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        };
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
                dto.failureReason = DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
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
                dto.failureReason = DTOConstants.FAILURE_REASON_VALIDATION_FAILED;
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
