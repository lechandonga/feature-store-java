package com.github.lechandonga.featurestorejava.exception;

import java.util.List;
import com.github.lechandonga.featurestorejava.domain.WriteViolation;

/** A write/backfill batch was rejected as a whole; no row in the batch took effect. */
public class WriteRejectedException extends FeatureStoreException {
    private final transient List<WriteViolation> violations;

    public WriteRejectedException(List<WriteViolation> violations) {
        super("write rejected with " + violations.size() + " violation(s)");
        this.violations = List.copyOf(violations);
    }

    public List<WriteViolation> getViolations() {
        return violations;
    }
}
