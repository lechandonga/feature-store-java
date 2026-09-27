package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteResult;
import com.github.lechandonga.featurestorejava.service.WriteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Atomic batch ingestion endpoint.
 */
@RestController
@RequestMapping("/api/write")
public class WriteController {

    private final WriteService writes;

    public WriteController(WriteService writes) {
        this.writes = writes;
    }

    @PostMapping
    public WriteResult write(
            @RequestHeader(value = "X-Workspace", required = false) String workspace,
            @RequestBody WriteBatch batch) {
        return writes.submit(workspace, batch);
    }
}
