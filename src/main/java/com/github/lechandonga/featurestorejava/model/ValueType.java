package com.github.lechandonga.featurestorejava.model;

/**
 * Feature value types supported by the feature store.
 *
 * <p>Values arrive as JSON-decoded Java objects, so the integral types
 * ({@link Integer}, {@link Long}) are both accepted for LONG and any
 * {@link Number} is accepted for DOUBLE (numeric promotion). A non-integral
 * number is never accepted as LONG, and strings/booleans never cross types.
 */
public enum ValueType {
    STRING,
    LONG,
    DOUBLE,
    BOOLEAN;

    /**
     * Check whether a raw JSON-derived value matches this declared type.
     *
     * @param value non-null value to check
     * @return true if the value is type-compatible
     */
    public boolean matches(Object value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case STRING -> value instanceof String;
            case LONG -> value instanceof Long || value instanceof Integer;
            // accept integral numbers too, e.g. a JSON 1 written into a DOUBLE slot
            case DOUBLE -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    /**
     * Normalize a JSON-decoded value to the canonical Java type for this slot
     * (LONG -> {@link Long}, DOUBLE -> {@link Double}).
     */
    public Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        return switch (this) {
            case LONG -> ((Number) value).longValue();
            case DOUBLE -> ((Number) value).doubleValue();
            default -> value;
        };
    }

    /**
     * Whether this type is numeric (LONG / DOUBLE).
     */
    public boolean isNumeric() {
        return this == LONG || this == DOUBLE;
    }
}
