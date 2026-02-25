package org.osgi.service.jdkhttp.runtime.dto;

/**
 * Represents an {@code Authenticator} service that failed to register.
 */
public class FailedAuthenticatorDTO extends AuthenticatorDTO {

    /**
     * The reason the authenticator failed to register.
     *
     * @see FailedHandlerDTO#FAILURE_REASON_UNKNOWN
     * @see FailedHandlerDTO#FAILURE_REASON_INVALID_CONTEXT_PATH
     * @see FailedHandlerDTO#FAILURE_REASON_EXCEPTION_ON_INIT
     */
    public int failureReason;
}
