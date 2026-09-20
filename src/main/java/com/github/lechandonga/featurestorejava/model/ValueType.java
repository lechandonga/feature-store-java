package com.github.lechandonga.featurestorejava.model;

/**
 * Feature value types supported by the feature store.
 */
public enum ValueType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN;

    /**
     * Check whether a raw JSON-derived value matches this declared type.
     * @return true if the value is type-compatible
     */
    public boolean matches(Object value) {
        return false; // implemented in step 2
    }

    /**
     * Whether numeric promotion (integer into double slot) is allowed.
     */
    public boolean isNumeric() {
        return false; // implemented in step 2
    }
}
