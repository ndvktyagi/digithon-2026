package com.picopilot.common.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends PiCopilotException {
    public ValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }
}
