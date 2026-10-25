package com.picopilot.common.exception;

import org.springframework.http.HttpStatus;

public class ScanException extends PiCopilotException {
    public ScanException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "SCAN_ERROR");
    }
}
