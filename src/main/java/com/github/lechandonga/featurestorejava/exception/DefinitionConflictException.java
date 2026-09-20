package com.github.lechandonga.featurestorejava.exception;

import com.github.lechandonga.featurestorejava.domain.ConflictReason;

/** A feature definition was rejected, with a distinguishable conflict reason. */
public class DefinitionConflictException extends FeatureStoreException {
    private final ConflictReason reason;

    public DefinitionConflictException(ConflictReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ConflictReason getReason() {
        return reason;
    }
}
