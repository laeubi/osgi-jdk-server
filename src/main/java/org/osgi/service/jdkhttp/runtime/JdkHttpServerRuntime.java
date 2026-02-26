package org.osgi.service.jdkhttp.runtime;

import org.osgi.service.jdkhttp.runtime.dto.JdkHttpServerRuntimeDTO;

/**
 * Service interface for the JDK HttpServer Whiteboard runtime.
 *
 * <p>
 * An implementation of the JDK HttpServer Whiteboard Specification registers a
 * service with this interface. Clients can use this service to obtain a
 * snapshot of the current runtime state via {@link #getRuntimeDTO()}.
 * </p>
 *
 * <p>
 * The service is registered with the following properties:
 * </p>
 * <ul>
 * <li>{@link org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants#JDK_HTTP_ENDPOINT}
 * – the endpoints the server is listening on.</li>
 * </ul>
 */
public interface JdkHttpServerRuntime {

    /**
     * Returns a {@link JdkHttpServerRuntimeDTO} representing the current state
     * of the whiteboard implementation.
     *
     * @return a snapshot of the runtime state; never {@code null}.
     */
    JdkHttpServerRuntimeDTO getRuntimeDTO();
}
