package com.kafkaasr.orchestrator.api;

import com.kafkaasr.orchestrator.service.SessionControlException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

@RestControllerAdvice
public class SessionExceptionHandler {

    @ExceptionHandler(SessionControlException.class)
    public ResponseEntity<SessionErrorResponse> handleControlException(SessionControlException exception) {
        return ResponseEntity.status(exception.status())
                .body(new SessionErrorResponse(
                        exception.code(),
                        exception.getMessage(),
                        exception.sessionId()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<SessionErrorResponse> handleBindException(WebExchangeBindException exception) {
        String message = exception.getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new SessionErrorResponse("INVALID_MESSAGE", message, ""));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<SessionErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new SessionErrorResponse("INVALID_MESSAGE", exception.getMessage(), ""));
    }
}
