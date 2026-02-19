package com.example.ssmdemo.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(OrderNotFoundException e) {
        log.warn("Order not found: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of(
                "error", "NOT_FOUND",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransition(InvalidStateTransitionException e) {
        log.warn("Invalid state transition: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", "INVALID_TRANSITION",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", e.getMessage(),
                "timestamp", LocalDateTime.now().toString()
            ));
    }
}
