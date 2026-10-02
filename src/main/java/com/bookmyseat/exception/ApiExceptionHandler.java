package com.bookmyseat.exception;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.bookmyseat.config.DatabaseHealthProbe;
import com.bookmyseat.dto.ErrorResponse;

import io.micrometer.core.instrument.MeterRegistry;

/** Turns exceptions into clean JSON responses. Domain outcomes are 4xx; real faults are the only 5xx. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final MeterRegistry meterRegistry;
    private final DatabaseHealthProbe dbProbe;

    public ApiExceptionHandler(MeterRegistry meterRegistry, DatabaseHealthProbe dbProbe) {
        this.meterRegistry = meterRegistry;
        this.dbProbe = dbProbe;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorResponse(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_failed", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_request", "Malformed JSON body or wrong field type"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("invalid_request", "Invalid value for '" + e.getName() + "'"));
    }

    /**
     * Could not get a database connection in time (pool exhausted by a burst) or a transient DB error.
     * With a healthy database this is OVERLOAD: answer 429 + Retry-After (a clean 4xx "try again"),
     * never a 500. If the database is actually down, say so honestly with 503.
     */
    @ExceptionHandler({CannotCreateTransactionException.class,
            CannotGetJdbcConnectionException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ErrorResponse> handleOverload(Exception e) {
        if (!dbProbe.isUp()) {
            meterRegistry.counter("bookmyseat.requests.shed", "reason", "database_down").increment();
            log.error("Database unavailable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(new ErrorResponse("database_unavailable", "The database is unavailable, retry shortly"));
        }
        meterRegistry.counter("bookmyseat.requests.shed", "reason", "overload").increment();
        log.debug("Shedding load: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorResponse("server_busy", "The service is busy, retry shortly"));
    }

    /** Safety net: every other failure becomes a logged, well-formed JSON error instead of a stack-trace page. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) throws Exception {
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e; // Spring Security's own handlers deal with these (401/403)
        }
        if (e instanceof org.springframework.web.ErrorResponse springError) {
            // Standard MVC errors such as 404 unknown path or 405 wrong method keep their status.
            HttpStatusCode status = springError.getStatusCode();
            return ResponseEntity.status(status)
                    .body(new ErrorResponse("http_" + status.value(), "Request could not be processed"));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "Unexpected server error"));
    }
}
