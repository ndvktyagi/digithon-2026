package com.picopilot.common.exception;

import com.picopilot.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PiCopilotException.class)
    public ResponseEntity<ErrorResponse> handlePiCopilotException(
            PiCopilotException ex, HttpServletRequest request) {
        log.error("PI Copilot error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ErrorResponse response = buildErrorResponse(ex.getStatus(), ex.getErrorCode(),
            ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.FORBIDDEN,
            "ACCESS_DENIED", "Access denied", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage,
                (a, b) -> a));
        ErrorResponse response = buildErrorResponse(HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR", "Validation failed: " + errors, request.getRequestURI());
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        ErrorResponse response = buildErrorResponse(HttpStatus.NOT_FOUND,
            "NOT_FOUND", "No endpoint " + request.getMethod() + " " + request.getRequestURI(),
            request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        ErrorResponse response = buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR", "An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.internalServerError().body(response);
    }

    private ErrorResponse buildErrorResponse(HttpStatus status, String code,
                                              String message, String path) {
        return ErrorResponse.builder()
            .status(status.value())
            .error(code)
            .message(message)
            .path(path)
            .timestamp(Instant.now())
            .correlationId(MDC.get("correlationId"))
            .build();
    }
}