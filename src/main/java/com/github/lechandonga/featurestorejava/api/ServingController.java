package com.github.lechandonga.featurestorejava.api;

import java.util.List;
import com.github.lechandonga.featurestorejava.service.ConsistencyReport;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.Observation;
import com.github.lechandonga.featurestorejava.service.OfflineServingService;
import com.github.lechandonga.featurestorejava.service.OnlineServingService;
import org.springframework.web.bind.annotation.*;

/** Online read, point-in-time offline read and consistency check endpoints. */
@RestController
@RequestMapping("/api/serving")
public class ServingController {

    private final OnlineServingService online;
    private final OfflineServingService offline;
    private final ConsistencyService consistency;

    public ServingController(OnlineServingService online, OfflineServingService offline,
                             ConsistencyService consistency) {
        this.online = online;
        this.offline = offline;
        this.consistency = consistency;
    }

    public record OnlineRequest(String entityId, List<String> features, List<Integer> versions) {
    }

    public record OfflineRequest(String entityId, Long asOf, List<String> features, List<Integer> versions) {
    }

    public record ObservationDto(String entityId, Long asOf, List<String> features, List<Integer> versions) {
    }

    @PostMapping("/online")
    public Object online(@RequestBody OnlineRequest request) {
        return online.read(request.entityId(), request.features(), request.versions());
    }

    @PostMapping("/offline")
    public Object offline(@RequestBody OfflineRequest request) {
        return offline.historical(new Observation(request.entityId(), request.asOf(),
                request.features(), request.versions()));
    }

    @PostMapping("/consistency")
    public ConsistencyReport consistency(@RequestBody ObservationDto request) {
        return consistency.check(new Observation(request.entityId(), request.asOf(),
                request.features(), request.versions()));
    }
}
