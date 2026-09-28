package com.github.highcumontoa.springrequesttracingjava.web;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the trace id visible on the failure path: the error response body echoes
 * the id so a failed request can always be looked up in the logs.
 */
@RestControllerAdvice
public class TraceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TraceExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex) {
        String traceId = TraceContext.currentTraceId();
        log.error("request failed with exception: {}", ex.toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("traceId", traceId != null ? traceId : "none");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
