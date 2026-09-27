package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.error.BatchRejectedException;
import com.github.lechandonga.featurestorejava.error.CapacityExceededException;
import com.github.lechandonga.featurestorejava.error.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.error.NotFoundException;
import com.github.lechandonga.featurestorejava.error.WorkspaceConflictException;
import com.github.lechandonga.featurestorejava.error.WorkspaceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates domain failures into structured HTTP responses.
 *
 * <ul>
 *   <li>definition conflicts / duplicate workspace -> 409;</li>
 *   <li>rejected batches -> 422 with every row-level rejection, including
 *       {@code CROSS_WORKSPACE_ACCESS} (nothing applied);</li>
 *   <li>workspace capacity exceeded -> 422 with dimension/limit/usage/basis
 *       and the offending feature set/entity/time point;</li>
 *   <li>unknown workspace / feature set / version / point -> 404;</li>
 *   <li>invalid workspace name or limit -> 400.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DefinitionConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(DefinitionConflictException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "DEFINITION_CONFLICT");
        body.put("workspace", ex.getWorkspace());
        body.put("featureSet", ex.getFeatureSet());
        body.put("message", ex.getMessage());
        body.put("conflicts", ex.getConflicts().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", c.reason().name());
            m.put("feature", c.feature());
            m.put("detail", c.detail());
            return m;
        }).toList());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(WorkspaceConflictException.class)
    public ResponseEntity<Map<String, Object>> workspaceConflict(WorkspaceConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "WORKSPACE_CONFLICT",
                "workspace", ex.getWorkspace() == null ? "" : ex.getWorkspace(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }

    @ExceptionHandler(BatchRejectedException.class)
    public ResponseEntity<Map<String, Object>> rejected(BatchRejectedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BATCH_REJECTED");
        body.put("workspace", ex.getWorkspace());
        body.put("featureSet", ex.getFeatureSet());
        body.put("version", ex.getVersion());
        body.put("message", ex.getMessage());
        body.put("rejections", ex.getRejections().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r.evidence());
            m.put("rowIndex", r.rowIndex());
            m.put("entity", r.entity());
            m.put("eventTime", r.eventTime());
            m.put("reason", r.reason().name());
            m.put("detail", r.detail());
            return m;
        }).toList());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(CapacityExceededException.class)
    public ResponseEntity<Map<String, Object>> capacity(CapacityExceededException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CAPACITY_EXCEEDED");
        body.put("workspace", ex.getWorkspace());
        body.put("featureSet", ex.getFeatureSet());
        body.put("version", ex.getVersion());
        body.put("message", ex.getMessage());
        body.putAll(ex.getContext());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(WorkspaceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> workspaceNotFound(WorkspaceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "WORKSPACE_NOT_FOUND",
                "workspace", ex.getWorkspace() == null ? "" : ex.getWorkspace(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage() == null ? "" : ex.getMessage()));
    }
}
