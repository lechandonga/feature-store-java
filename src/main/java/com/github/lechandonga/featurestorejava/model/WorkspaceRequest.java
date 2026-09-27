package com.github.lechandonga.featurestorejava.model;

/**
 * Request body for creating a workspace. Both limits are optional
 * (null = unlimited) and must be positive when present.
 */
public record WorkspaceRequest(
        String name,
        Long maxFeatureSets,
        Long maxPoints,
        String description
) {
}
