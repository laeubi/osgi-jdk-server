package org.osgi.service.jdkhttp.runtime.dto;

import org.osgi.dto.DTO;

/**
 * Represents the state of an active {@code HttpHandler} registration.
 */
public class HandlerDTO extends DTO {

    /** The service id of the handler service. */
    public long serviceId;

    /** The context path at which the handler is registered. */
    public String contextPath;

    /**
     * The human-readable context name, or {@code null} if none was specified.
     */
    public String contextName;
}
