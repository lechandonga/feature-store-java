package com.github.lechandonga.featurestorejava.model;

/**
 * Current usage of one workspace alongside its configured limits.
 *
 * @param workspace     workspace name
 * @param featureSets   number of registered feature sets
 * @param points        total number of persisted historical points
 * @param limits        configured capacity limits (may contain nulls)
 */
public record WorkspaceUsage(String workspace, int featureSets, long points,
                             WorkspaceLimits limits) {
}
