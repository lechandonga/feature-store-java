package com.github.lechandonga.featurestorejava.api;

import java.util.List;
import com.github.lechandonga.featurestorejava.exception.WriteRejectedException;
import com.github.lechandonga.featurestorejava.service.BackfillService;
import com.github.lechandonga.featurestorejava.service.IngestService;
import com.github.lechandonga.featurestorejava.service.RowInput;
import com.github.lechandonga.featurestorejava.service.WriteResult;
import org.springframework.web.bind.annotation.*;

/** Live write and backfill endpoints. */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestService ingestService;
    private final BackfillService backfillService;

    public IngestController(IngestService ingestService, BackfillService backfillService) {
        this.ingestService = ingestService;
        this.backfillService = backfillService;
    }

    public record Row(String feature, Integer version, String entityId, Long eventTs, Object value,
                      Long ingestTs) {
    }

    public record WriteRequest(String batchId, List<Row> rows) {
    }

    private List<RowInput> map(WriteRequest request) {
        return request.rows().stream()
                .map(r -> new RowInput(r.feature(), r.version(), r.entityId(), r.eventTs(), r.value(),
                        r.ingestTs()))
                .toList();
    }

    private WriteResult orThrow(WriteResult result) {
        if (!result.accepted()) {
            throw new WriteRejectedException(result.violations());
        }
        return result;
    }

    @PostMapping("/write")
    public WriteResult write(@RequestBody WriteRequest request) {
        return orThrow(ingestService.write(request.batchId(), map(request)));
    }

    @PostMapping("/backfill")
    public WriteResult backfill(@RequestBody WriteRequest request) {
        return orThrow(backfillService.backfill(request.batchId(), map(request)));
    }

    @PostMapping("/recompute")
    public WriteResult recompute(@RequestBody WriteRequest request) {
        return orThrow(backfillService.recompute(request.batchId(), map(request)));
    }
}
