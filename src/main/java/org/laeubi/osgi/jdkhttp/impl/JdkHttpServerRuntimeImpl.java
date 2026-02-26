package org.laeubi.osgi.jdkhttp.impl;

import org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntime;
import org.osgi.service.jdkhttp.runtime.dto.JdkHttpServerRuntimeDTO;

/**
 * Implementation of {@link JdkHttpServerRuntime} that delegates to
 * {@link JdkHttpServerWhiteboard} for the current state.
 */
class JdkHttpServerRuntimeImpl implements JdkHttpServerRuntime {

    private final JdkHttpServerWhiteboard whiteboard;

    JdkHttpServerRuntimeImpl(JdkHttpServerWhiteboard whiteboard) {
        this.whiteboard = whiteboard;
    }

    @Override
    public JdkHttpServerRuntimeDTO getRuntimeDTO() {
        JdkHttpServerRuntimeDTO dto = new JdkHttpServerRuntimeDTO();
        dto.serviceId            = whiteboard.getRuntimeServiceId();
        dto.endpoints            = whiteboard.getEndpoints();
        dto.handlers             = whiteboard.getHandlerDTOs();
        dto.filters              = whiteboard.getFilterDTOs();
        dto.authenticators       = whiteboard.getAuthenticatorDTOs();
        dto.failedHandlers       = whiteboard.getFailedHandlerDTOs();
        dto.failedFilters        = whiteboard.getFailedFilterDTOs();
        dto.failedAuthenticators = whiteboard.getFailedAuthenticatorDTOs();
        return dto;
    }
}
