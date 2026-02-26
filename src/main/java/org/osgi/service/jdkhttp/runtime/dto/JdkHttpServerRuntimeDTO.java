package org.osgi.service.jdkhttp.runtime.dto;

import org.osgi.dto.DTO;

/**
 * Represents the full runtime state of a JDK HttpServer Whiteboard
 * implementation.
 *
 * <p>
 * Obtained from {@link org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntime#getRuntimeDTO()}.
 * </p>
 */
public class JdkHttpServerRuntimeDTO extends DTO {

    /** The {@code service.id} of the {@code JdkHttpServerRuntime} service. */
    public long serviceId;

    /**
     * The endpoints where the HTTP server is listening, e.g.
     * {@code "http://localhost:8080"}.
     */
    public String[] endpoints;

    /** The currently active handler registrations. */
    public HandlerDTO[] handlers;

    /** The currently active filter registrations. */
    public FilterDTO[] filters;

    /** The currently active authenticator registrations. */
    public AuthenticatorDTO[] authenticators;

    /** Handlers that failed to register. */
    public FailedHandlerDTO[] failedHandlers;

    /** Filters that failed to register. */
    public FailedFilterDTO[] failedFilters;

    /** Authenticators that failed to register. */
    public FailedAuthenticatorDTO[] failedAuthenticators;
}
