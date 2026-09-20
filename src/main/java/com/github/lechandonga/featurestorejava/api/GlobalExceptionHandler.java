package com.github.lechandonga.featurestorejava.api;

import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.exception.FeatureStoreException;
import com.github.lechandonga.featurestorejava.exception.WriteRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to distinguishable HTTP error responses carrying the conflict/reject reason. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public record ErrorResponse(String error, String reason, Object message, long timestamp) {
    }

    @ExceptionHandler(DefinitionConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(DefinitionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DEFINITION_CONFLICT", ex.getReason().name(),
                        ex.getMessage(), System.currentTimeMillis()));
    }

    @ExceptionHandler(WriteRejectedException.class)
    public ResponseEntity<ErrorResponse> rejected(WriteRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("WRITE_REJECTED", "VALIDATION_FAILED",
                        ex.getViolations(), System.currentTimeMillis()));
    }

    @ExceptionHandler(FeatureStoreException.class)
    public ResponseEntity<ErrorResponse> other(FeatureStoreException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("FEATURE_STORE_ERROR", "BAD_REQUEST",
                        ex.getMessage(), System.currentTimeMillis()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> illegal(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", "ILLEGAL_ARGUMENT",
                        ex.getMessage(), System.currentTimeMillis()));
    }
}
