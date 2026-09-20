package com.github.lechandonga.featurestorejava.tests;

import static org.junit.jupiter.api.Assertions.*;

import com.github.lechandonga.featurestorejava.domain.ConflictReason;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.ValueType;
import com.github.lechandonga.featurestorejava.exception.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.registry.FeatureRegistry;
import org.junit.jupiter.api.Test;

class RegistryVersionTest {

    private final FeatureRegistry registry = new FeatureRegistry();

    @Test
    void registersFirstVersionAndAutoIncrements() {
        var v1 = registry.register("price", null, ValueType.DOUBLE, MissingPolicy.DEFAULT,
                0.0, "item price", 0L);
        var v2 = registry.evolve("price", 1.0, "item price", null);
        assertEquals(1, v1.version());
        assertEquals(2, v2.version());
        assertEquals(ValueType.DOUBLE, v2.valueType());
        assertEquals(1.0, v2.defaultValue());
    }

    @Test
    void rejectsDuplicateExplicitVersionWithDistinctReason() {
        registry.register("price", 5, ValueType.DOUBLE, MissingPolicy.DEFAULT, 0.0, null, 0L);
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> registry.register("price", 5, ValueType.DOUBLE, MissingPolicy.DEFAULT, 9.9, null, 0L));
        assertEquals(ConflictReason.VERSION_ALREADY_EXISTS, ex.getReason());
    }

    @Test
    void rejectsIncompatibleTypeChange() {
        registry.register("price", null, ValueType.DOUBLE, MissingPolicy.DEFAULT, 0.0, null, 0L);
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> registry.register("price", null, ValueType.STRING, MissingPolicy.DEFAULT, "0", null, 0L));
        assertEquals(ConflictReason.TYPE_CHANGED, ex.getReason());
    }

    @Test
    void rejectsMissingPolicyChange() {
        registry.register("price", null, ValueType.DOUBLE, MissingPolicy.DEFAULT, 0.0, null, 0L);
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> registry.register("price", null, ValueType.DOUBLE, MissingPolicy.ERROR, null, null, 0L));
        assertEquals(ConflictReason.MISSING_POLICY_CHANGED, ex.getReason());
    }

    @Test
    void rejectsDefaultValueTypeMismatch() {
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> registry.register("count", null, ValueType.INT, MissingPolicy.DEFAULT, "n/a", null, 0L));
        assertEquals(ConflictReason.DEFAULT_VALUE_TYPE_MISMATCH, ex.getReason());

        registry.register("flag", null, ValueType.BOOLEAN, MissingPolicy.NULL, null, null, 0L);
        DefinitionConflictException ex2 = assertThrows(DefinitionConflictException.class,
                () -> registry.evolve("flag", "true", null, null));
        assertEquals(ConflictReason.DEFAULT_VALUE_TYPE_MISMATCH, ex2.getReason());
    }

    @Test
    void evolveOnUnknownFeatureReportsFeatureNotFound() {
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> registry.evolve("ghost", 1, null, null));
        assertEquals(ConflictReason.FEATURE_NOT_FOUND, ex.getReason());
    }
}
