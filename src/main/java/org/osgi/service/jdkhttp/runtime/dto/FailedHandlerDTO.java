package org.osgi.service.jdkhttp.runtime.dto;

/**
 * Represents a {@code HttpHandler} service that failed to register.
 */
public class FailedHandlerDTO extends HandlerDTO {

    /**
     * The reason the handler failed to register.
     *
     * @see #FAILURE_REASON_UNKNOWN
     * @see #FAILURE_REASON_INVALID_CONTEXT_PATH
     * @see #FAILURE_REASON_SHADOWED_BY_OTHER_HANDLER
     * @see #FAILURE_REASON_EXCEPTION_ON_INIT
     */
    public int failureReason;

    /** The failure reason is unknown. */
    public static final int FAILURE_REASON_UNKNOWN = 0;

    /**
     * The handler did not provide a valid
     * {@link org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants#JDK_HTTP_CONTEXT_PATH}
     * property.
     */
    public static final int FAILURE_REASON_INVALID_CONTEXT_PATH = 1;

    /**
     * Another handler with a higher {@code service.ranking} is already
     * registered at the same context path.
     */
    public static final int FAILURE_REASON_SHADOWED_BY_OTHER_HANDLER = 2;

    /** An exception occurred while initialising the handler context. */
    public static final int FAILURE_REASON_EXCEPTION_ON_INIT = 3;
}
