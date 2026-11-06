package com.readygym.server.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

/**
 * Errors use the same {"error":{"code","message"}} shape as the DeepSeek API, so the app's existing
 * error parsing works unchanged when it talks to this server instead.
 */
@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception e) {
        // When the capped stream throws BodyTooLarge, Jackson/Spring wraps it in this exception,
        // so the size limit has to be honoured here too (the filter only catches the raw throw).
        if (causedByBodyTooLarge(e)) {
            return body(HttpStatus.PAYLOAD_TOO_LARGE, "request_too_large", "内容太长了，请缩短后重试");
        }
        return body(HttpStatus.BAD_REQUEST, "invalid_request", "请求格式不正确");
    }

    /** Catch-all: one stable 500 shape, never a stack trace in the response. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {
        // Spring's own HTTP exceptions (404 for a missing static resource, 405, 415, ...) already
        // carry the right status; keep it instead of turning those requests into 500s.
        if (e instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode().value()).build();
        }
        log.error("Unhandled server error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "服务器开小差了，请稍后再试");
    }

    private static boolean causedByBodyTooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RequestBodyLimitFilter.BodyTooLarge) return true;
            if (t == t.getCause()) break;   // defensive against a self-referential cause
        }
        return false;
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
