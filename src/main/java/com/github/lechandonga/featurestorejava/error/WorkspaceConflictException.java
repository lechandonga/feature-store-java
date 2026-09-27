package com.github.lechandonga.featurestorejava.error;

/**
 * Thrown when creating a workspace whose name is already taken.
 */
public class WorkspaceConflictException extends RuntimeException {

    private final String workspace;

    public WorkspaceConflictException(String workspace) {
        super("workspace '" + workspace + "' already exists");
        this.workspace = workspace;
    }

    public String getWorkspace() {
        return workspace;
    }
}
