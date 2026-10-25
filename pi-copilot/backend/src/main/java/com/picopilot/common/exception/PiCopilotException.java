package com.picopilot.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class PiCopilotException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String correlationId;

    public PiCopilotException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.correlationId = null;
    }

    public PiCopilotException(String message, HttpStatus status, String errorCode, String correlationId) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.correlationId = correlationId;
    }

    public PiCopilotException(String message, Throwable cause, HttpStatus status, String errorCode) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.correlationId = null;
    }
}