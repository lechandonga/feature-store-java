package com.github.lechandonga.featurestorejava.model;

/**
 * Request body for creating a workspace.
 *
 * @param name   workspace name; must be non-blank and unique
 * @param limits optional per-workspace capacity limits
 */
public record CreateWorkspaceRequest(String name, WorkspaceLimits limits) {
}
