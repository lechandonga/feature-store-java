package com.github.lechandonga.featurestorejava.web;

import com.github.lechandonga.featurestorejava.model.EntityAtPoint;
import com.github.lechandonga.featurestorejava.model.OfflineResult;
import com.github.lechandonga.featurestorejava.model.OnlineResult;
import com.github.lechandonga.featurestorejava.service.ConsistencyService;
import com.github.lechandonga.featurestorejava.service.ReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Online, offline point-in-time and consistency-check endpoints.
 */
@RestController
@RequestMapping("/api/read")
public class ReadController {

    private final ReadService reads;
    private final ConsistencyService consistency;

    public ReadController(ReadService reads, ConsistencyService consistency) {
        this.reads = reads;
        this.consistency = consistency;
    }

    @GetMapping("/online/{featureSet}/entities/{entity}")
    public OnlineResult online(@PathVariable String featureSet,
                               @PathVariable String entity,
                               @RequestParam(required = false) Integer version,
                               @RequestParam(required = false) Long asOf) {
        return reads.online(featureSet, version, entity, asOf);
    }

    @PostMapping("/offline/{featureSet}")
    public List<OfflineResult> offline(@PathVariable String featureSet,
                                       @RequestParam(required = false) Integer version,
                                       @RequestBody List<EntityAtPoint> targets) {
        return reads.offline(featureSet, version, targets);
    }

    @PostMapping("/consistency/{featureSet}")
    public Object check(@PathVariable String featureSet,
                        @RequestParam(required = false) Integer version,
                        @RequestBody List<EntityAtPoint> targets) {
        return consistency.check(featureSet, version, targets);
    }
}
