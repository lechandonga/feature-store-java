package com.github.lechandonga.featurestorejava.model;

/**
 * A workspace (namespace) isolating feature set definitions, points,
 * corrections, audit and observability statistics from other workspaces.
 *
 * @param name           unique workspace name; {@code "default"} always exists
 *                       and holds all pre-workspace-era data
 * @param maxFeatureSets capacity limit on distinct feature set names
 *                       (null = unlimited)
 * @param maxPoints      capacity limit on total stored offline points
 *                       (null = unlimited)
 * @param createdAt      creation wall-clock time
 * @param description    human-readable purpose
 */
public record WorkspaceDef(
        String name,
        Long maxFeatureSets,
        Long maxPoints,
        long createdAt,
        String description
) {
}
