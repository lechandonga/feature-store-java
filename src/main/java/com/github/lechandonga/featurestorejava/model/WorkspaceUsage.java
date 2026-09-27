package com.github.lechandonga.featurestorejava.model;

/**
 * Live usage of one workspace against its configured capacity limits.
 *
 * @param name            workspace name
 * @param featureSetCount distinct feature set names currently registered
 * @param maxFeatureSets  configured limit (null = unlimited)
 * @param pointCount      total stored offline points
 * @param maxPoints       configured limit (null = unlimited)
 * @param createdAt       creation wall-clock time
 */
public record WorkspaceUsage(
        String name,
        int featureSetCount,
        Long maxFeatureSets,
        long pointCount,
        Long maxPoints,
        long createdAt
) {
}
