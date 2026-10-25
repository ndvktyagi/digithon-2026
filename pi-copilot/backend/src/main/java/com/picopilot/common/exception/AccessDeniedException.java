package com.picopilot.common.exception;

import org.springframework.http.HttpStatus;

public class AccessDeniedException extends PiCopilotException {
    public AccessDeniedException(String message) {
        super(message, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }
}
