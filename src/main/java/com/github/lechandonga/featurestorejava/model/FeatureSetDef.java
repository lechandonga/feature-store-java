package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * An immutable, versioned definition of a feature set.
 *
 * <p>Versions are monotonic. Within one version, semantics, defaults and
 * missing rules never change, which makes historical reads reproducible.
 *
 * @param name       feature set name
 * @param version    positive integer version, monotonically increasing
 * @param entityKey  field used as the entity identifier in every row
 * @param ttlMillis  optional freshness window; null means no expiry
 * @param features   ordered list of feature definitions
 * @param createdAt  registration time (epoch millis)
 */
public record FeatureSetDef(
        String name,
        int version,
        String entityKey,
        Long ttlMillis,
        List<FeatureDef> features,
        long createdAt
) {
}
