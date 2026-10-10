package com.cdcplatform.api;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> badRequest(Exception e) { return error(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(Exception e) { return error(HttpStatus.CONFLICT, e.getMessage()); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> responseStatus(ResponseStatusException e) { return error(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> serverError(Exception e, HttpServletRequest request, HttpServletResponse response) {
        // An SSE request may fail during async redispatch after its response has
        // already been committed. Do not try to serialize a JSON error into the
        // text/event-stream response; that creates a second converter failure.
        if (response.isCommitted() || "text/event-stream".equalsIgnoreCase(response.getContentType())
                || "/api/runtime/events".equals(request.getRequestURI()))
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        return error(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("timestamp", Instant.now(), "status", status.value(), "error", message == null ? status.getReasonPhrase() : message));
    }
}
