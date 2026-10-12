package com.rhythm.los.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.getStatus(), e.getCode(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(f -> f.getField(), f -> String.valueOf(f.getDefaultMessage()), (a, b) -> a, LinkedHashMap::new));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid", fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraint(ConstraintViolationException e) {
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), null);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<Map<String, Object>> denied(RuntimeException e) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role is not allowed to do this", null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> stale(ObjectOptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "STALE_UPDATE", "The application was changed by someone else. Reload and try again.", null);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(Exception e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "The file is larger than 10 MB", null);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request body is missing or not valid JSON", null);
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badParam(Exception e) {
        String msg = e instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m
                ? "Invalid value for '" + m.getName() + "'"
                : e instanceof org.springframework.web.bind.MissingServletRequestParameterException p
                ? "Missing parameter '" + p.getParameterName() + "'"
                : "A required part of the request is missing";
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", msg, null);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(Exception e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such endpoint", null);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> method(Exception e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("Unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error. It has been logged.", null);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus s, String code, String msg, Object fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", s.value());
        m.put("code", code);
        m.put("message", msg);
        if (fields != null) m.put("fields", fields);
        return ResponseEntity.status(s).body(m);
    }
}
