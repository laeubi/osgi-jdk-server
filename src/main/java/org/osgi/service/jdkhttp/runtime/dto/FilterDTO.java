package org.osgi.service.jdkhttp.runtime.dto;

import org.osgi.dto.DTO;

/**
 * Represents the state of an active {@code Filter} registration.
 */
public class FilterDTO extends DTO {

    /** The service id of the filter service. */
    public long serviceId;

    /** The context path patterns to which the filter is applied. */
    public String[] patterns;

    /**
     * The human-readable filter name, or {@code null} if none was specified.
     */
    public String filterName;
}
