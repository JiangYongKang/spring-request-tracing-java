package com.github.highcumontoa.springrequesttracingjava.web;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Records an error checkpoint while the trace id is still bound. */
@RestControllerAdvice
public class TraceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TraceExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex) {
        TraceContext.event("controller-error", ex.toString());
        log.warn("request failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("error");
    }
}
