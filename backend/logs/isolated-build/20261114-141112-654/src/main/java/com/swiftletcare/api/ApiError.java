package com.swiftletcare.api;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiError {
  public static class Failure extends RuntimeException {
    public final int status;
    public final String code;

    public Failure(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  public static Failure bad(String message) {
    return new Failure(400, "VALIDATION_ERROR", message);
  }

  public static Failure missing() {
    return new Failure(404, "RESOURCE_NOT_FOUND", "Resource not found");
  }

  public static Failure conflict() {
    return new Failure(409, "DUPLICATE_EVENT", "Same key has different content");
  }

  public static Map<String, Object> body(String code, String message, String path) {
    return Map.of(
        "code", code, "message", message, "timestamp", Instant.now().toString(), "path", path);
  }

  @ExceptionHandler(Failure.class)
  ResponseEntity<?> failure(Failure e, HttpServletRequest r) {
    return ResponseEntity.status(e.status).body(body(e.code, e.getMessage(), r.getRequestURI()));
  }

  @ExceptionHandler({
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    return ResponseEntity.badRequest()
        .body(body("VALIDATION_ERROR", "Invalid request payload or parameters", r.getRequestURI()));
  }

  @ExceptionHandler({
    DataAccessException.class,
    org.springframework.transaction.TransactionException.class
  })
  ResponseEntity<?> db(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(503)
        .body(body("DATABASE_UNAVAILABLE", "Database operation failed", r.getRequestURI()));
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> notFound(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(404)
        .body(body("RESOURCE_NOT_FOUND", "Resource not found", r.getRequestURI()));
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<?> method(Exception e, HttpServletRequest r) {
    return ResponseEntity.status(405)
        .body(body("METHOD_NOT_ALLOWED", "Method not allowed", r.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest r) {
    org.slf4j.LoggerFactory.getLogger(ApiError.class)
        .error("Request failed ({})", e.getClass().getSimpleName());
    return ResponseEntity.status(500)
        .body(body("INTERNAL_ERROR", "Request could not be completed", r.getRequestURI()));
  }
}
