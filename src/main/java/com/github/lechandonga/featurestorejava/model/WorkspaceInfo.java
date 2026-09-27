package com.github.lechandonga.featurestorejava.model;

/**
 * Metadata describing one workspace.
 *
 * @param name      workspace name (the default workspace is {@code "default"})
 * @param createdAt creation time (epoch millis)
 * @param limits    capacity limits configured at creation
 */
public record WorkspaceInfo(String name, long createdAt, WorkspaceLimits limits) {
}
