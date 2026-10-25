package com.picopilot.common.exception;

import org.springframework.http.HttpStatus;

public class ConnectorException extends PiCopilotException {
    public ConnectorException(String message, Throwable cause) {
        super(message, cause, HttpStatus.BAD_GATEWAY, "CONNECTOR_ERROR");
    }
}
