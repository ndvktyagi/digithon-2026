package com.picopilot.common.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateException extends PiCopilotException {
    public InvalidStateException(String message) {
        super(message, HttpStatus.CONFLICT, "INVALID_STATE");
    }
}
