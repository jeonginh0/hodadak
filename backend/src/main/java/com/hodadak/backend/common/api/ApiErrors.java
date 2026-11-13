package com.hodadak.backend.common.api;

import com.hodadak.backend.common.domain.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class ApiErrors {
    public record FieldError(String field, String message) {}
    public record ErrorBody(String code, String message, String traceId) {}
    public record ValidationBody(String code, String message, String traceId, List<FieldError> fields) {}

    private static String traceId() {
        String trace = MDC.get("traceId");
        return trace == null ? UUID.randomUUID().toString() : trace;
    }

    public static ErrorBody body(ErrorCode code) {
        return new ErrorBody(code.name(), code.message(), traceId());
    }

    public static void write(HttpServletResponse response, ErrorCode code, JsonMapper mapper) throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body(code));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException exception) {
        if (exception.code() == ErrorCode.VALIDATION_FAILED) return validation(List.of());
        return ResponseEntity.status(exception.code().status()).body(body(exception.code()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> invalid(MethodArgumentNotValidException exception) {
        return validation(exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadable() { return validation(List.of()); }

    private ResponseEntity<?> validation(List<FieldError> fields) {
        return ResponseEntity.badRequest().body(new ValidationBody("VALIDATION_FAILED",
                ErrorCode.VALIDATION_FAILED.message(), traceId(), fields));
    }
}
