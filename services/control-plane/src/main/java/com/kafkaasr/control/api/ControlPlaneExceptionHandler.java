package com.kafkaasr.control.api;

import com.kafkaasr.control.service.ControlPlaneException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

@RestControllerAdvice
public class ControlPlaneExceptionHandler {

    @ExceptionHandler(ControlPlaneException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleControlPlaneException(ControlPlaneException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ControlPlaneErrorResponse(
                        exception.code(),
                        exception.getMessage(),
                        exception.tenantId()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleBindException(WebExchangeBindException exception) {
        String message = exception.getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ControlPlaneErrorResponse("INVALID_MESSAGE", message, ""));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ControlPlaneErrorResponse("INVALID_MESSAGE", exception.getMessage(), ""));
    }
}
