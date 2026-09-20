package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.domain.ValueType;

/** Runtime type validation and normalization for JSON-deserialized feature values. */
public final class ValueTypeSupport {

    private ValueTypeSupport() {
    }

    /** Whether the value can be accepted as the given type. Booleans/Strings must not be coerced. */
    public static boolean matches(Object value, ValueType type) {
        if (value == null) {
            return false;
        }
        return switch (type) {
            case INT -> value instanceof Integer || value instanceof Long;
            case DOUBLE -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String;
        };
    }

    /** Normalize to canonical Java type (Integer / Double / Boolean / String). */
    public static Object normalize(Object value, ValueType type) {
        return switch (type) {
            case INT -> ((Number) value).intValue();
            case DOUBLE -> ((Number) value).doubleValue();
            case BOOLEAN -> value;
            case STRING -> value;
        };
    }
}
