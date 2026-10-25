package com.picopilot.common.exception;

import org.springframework.http.HttpStatus;

public class EncryptionException extends PiCopilotException {
    public EncryptionException(String message, Throwable cause) {
        super(message, cause, HttpStatus.INTERNAL_SERVER_ERROR, "ENCRYPTION_ERROR");
    }
}
