package com.github.lechandonga.featurestorejava.error;

/**
 * Thrown when a workspace creation request conflicts with existing state
 * (for example the name is already taken).
 */
public class WorkspaceConflictException extends RuntimeException {

    private final String workspace;

    public WorkspaceConflictException(String workspace, String message) {
        super(message);
        this.workspace = workspace;
    }

    public String getWorkspace() {
        return workspace;
    }
}
