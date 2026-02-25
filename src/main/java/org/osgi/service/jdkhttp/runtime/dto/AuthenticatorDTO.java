package org.osgi.service.jdkhttp.runtime.dto;

import org.osgi.dto.DTO;

/**
 * Represents the state of an active {@code Authenticator} registration.
 */
public class AuthenticatorDTO extends DTO {

    /** The service id of the authenticator service. */
    public long serviceId;

    /** The context path patterns to which the authenticator is applied. */
    public String[] patterns;

    /**
     * The authentication realm, or {@code null} if none was specified.
     */
    public String realm;
}
