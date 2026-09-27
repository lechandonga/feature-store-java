package com.github.lechandonga.featurestorejava.error;

/**
 * Thrown when an operation names a workspace that does not exist.
 * Distinct from a missing feature set <em>within</em> an existing workspace.
 */
public class WorkspaceNotFoundException extends RuntimeException {

    private final String workspace;

    public WorkspaceNotFoundException(String workspace) {
        super("workspace '" + workspace + "' does not exist; create it before use");
        this.workspace = workspace;
    }

    public String getWorkspace() {
        return workspace;
    }
}
