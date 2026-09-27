package com.github.lechandonga.featurestorejava.workspace;

import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.StoredPoint;

import java.util.List;
import java.util.Map;

/**
 * Persisted image of one workspace.
 *
 * @param name          workspace name
 * @param createdAt     creation time (epoch millis)
 * @param limits        capacity limits; null fields mean unlimited
 * @param definitions   every registered feature set version
 * @param points        every persisted historical point (writes + corrections)
 * @param correlations  backfill correlation id -> produced point ids
 */
public record WorkspaceSnapshot(
        String name,
        Long createdAt,
        com.github.lechandonga.featurestorejava.model.WorkspaceLimits limits,
        List<FeatureSetDef> definitions,
        List<StoredPoint> points,
        Map<String, List<String>> correlations
) {
}
