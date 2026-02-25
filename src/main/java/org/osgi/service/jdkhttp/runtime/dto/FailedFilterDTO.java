package org.osgi.service.jdkhttp.runtime.dto;

/**
 * Represents a {@code Filter} service that failed to register.
 */
public class FailedFilterDTO extends FilterDTO {

    /**
     * The reason the filter failed to register.
     *
     * @see FailedHandlerDTO#FAILURE_REASON_UNKNOWN
     * @see FailedHandlerDTO#FAILURE_REASON_INVALID_CONTEXT_PATH
     * @see FailedHandlerDTO#FAILURE_REASON_EXCEPTION_ON_INIT
     */
    public int failureReason;
}
