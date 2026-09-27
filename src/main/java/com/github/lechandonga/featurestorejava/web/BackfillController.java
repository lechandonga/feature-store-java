package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.BackfillRequest;
import com.github.lechandonga.featurestorejava.model.BackfillResult;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backfill / recompute endpoint.
 */
@RestController
@RequestMapping("/api/backfill")
public class BackfillController {

    private final BackfillService backfills;

    public BackfillController(BackfillService backfills) {
        this.backfills = backfills;
    }

    @PostMapping
    public BackfillResult backfill(@RequestBody BackfillRequest request,
                                   @RequestParam(required = false) String workspace) {
        return backfills.backfill(workspace, request);
    }
}
