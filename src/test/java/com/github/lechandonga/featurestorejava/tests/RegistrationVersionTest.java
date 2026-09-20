package com.github.lechandonga.featurestorejava.tests;

import com.github.lechandonga.featurestorejava.error.DefinitionConflictException;
import com.github.lechandonga.featurestorejava.error.NotFoundException;
import com.github.lechandonga.featurestorejava.model.ConflictReason;
import com.github.lechandonga.featurestorejava.model.FeatureSetDef;
import com.github.lechandonga.featurestorejava.model.MissingPolicy;
import com.github.lechandonga.featurestorejava.model.RegisterRequest;
import com.github.lechandonga.featurestorejava.model.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.lechandonga.featurestorejava.tests.StoreFixture.feature;
import static com.github.lechandonga.featurestorejava.tests.StoreFixture.sampleFeatures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version evolution: compatible changes accepted, incompatible ones rejected
 * with distinguishable conflict reasons, nothing partially registered.
 */
class RegistrationVersionTest {

    private final StoreFixture fx = new StoreFixture();

    private FeatureSetDef register(String name, List<com.github.lechandonga.featurestorejava.model.FeatureDef> feats) {
        return fx.registry.register(new RegisterRequest(name, "uid", null, feats, "demo"));
    }

    @Test
    void registersVersionOneAndFetchesIt() {
        FeatureSetDef v1 = register("user", sampleFeatures());
        assertEquals(1, v1.version());
        assertEquals("uid", v1.entityKey());
        assertEquals(2, v1.features().size());
        assertEquals(v1, fx.registry.requireVersion("user", 1));
        assertEquals(v1, fx.registry.requireVersion("user", null));
    }

    @Test
    void duplicateRegistrationIsRejectedWithAlreadyExists() {
        register("user", sampleFeatures());
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> register("user", sampleFeatures()));
        assertTrue(ex.getConflicts().stream()
                .anyMatch(c -> c.reason() == ConflictReason.ALREADY_EXISTS));
        // state unchanged: still only v1
        assertEquals(1, fx.registry.requireVersion("user", null).version());
    }

    @Test
    void addingAFeatureCreatesNextMonotonicVersion() {
        register("user", sampleFeatures());
        var features = List.of(
                feature("age", ValueType.LONG, true, 0L, MissingPolicy.DEFAULT),
                feature("score", ValueType.DOUBLE, false, null, MissingPolicy.NULL),
                feature("tier", ValueType.STRING, false, "bronze", MissingPolicy.DEFAULT));
        FeatureSetDef v2 = fx.registry.evolve("user", features, null);
        assertEquals(2, v2.version());
        assertEquals(3, v2.features().size());
        // v1 remains exactly as registered
        assertEquals(2, fx.registry.requireVersion("user", 1).features().size());
    }

    @Test
    void evolvingUnknownSetReportsNotFound() {
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> fx.registry.evolve("ghost", sampleFeatures(), null));
        assertEquals(ConflictReason.FEATURE_SET_NOT_FOUND, ex.getConflicts().get(0).reason());
    }

    @Test
    void changingTypeDefaultPolicyAndRenameAreAllReportedTogether() {
        register("user", sampleFeatures());
        var incompatible = List.of(
                // age: LONG -> DOUBLE (type), default 0 -> 9 (default), required true->false
                feature("age", ValueType.DOUBLE, false, 9L, MissingPolicy.NULL),
                // same-length replacement at score's position -> rename/reorder
                feature("other", ValueType.STRING, false, null, MissingPolicy.NULL));
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> fx.registry.evolve("user", incompatible, null));
        List<ConflictReason> reasons = ex.getConflicts().stream().map(c -> c.reason()).toList();
        assertTrue(reasons.contains(ConflictReason.FEATURE_TYPE_CHANGED), "type: " + reasons);
        assertTrue(reasons.contains(ConflictReason.DEFAULT_VALUE_CHANGED), "default: " + reasons);
        assertTrue(reasons.contains(ConflictReason.REQUIRED_FLAG_CHANGED), "required: " + reasons);
        assertTrue(reasons.contains(ConflictReason.MISSING_POLICY_CHANGED), "policy: " + reasons);
        assertTrue(reasons.contains(ConflictReason.FEATURE_RENAMED_OR_REORDERED), "rename: " + reasons);
        // no v2 created
        assertThrows(NotFoundException.class, () -> fx.registry.requireVersion("user", 2));
    }

    @Test
    void removingAnExistingFeatureIsRejected() {
        register("user", sampleFeatures());
        var shorter = List.of(
                feature("age", ValueType.LONG, true, 0L, MissingPolicy.DEFAULT));
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> fx.registry.evolve("user", shorter, null));
        assertTrue(ex.getConflicts().stream()
                .anyMatch(c -> c.reason() == ConflictReason.FEATURE_REMOVED
                        && "score".equals(c.feature())));
        assertThrows(NotFoundException.class, () -> fx.registry.requireVersion("user", 2));
    }

    @Test
    void ttlMayLengthenButNeverShorten() {
        fx.registry.register(new RegisterRequest("user", "uid", 1000L, sampleFeatures(), "d"));
        var same = sampleFeatures();
        // lengthen ok
        assertEquals(2, fx.registry.evolve("user", same, 2000L).version());
        // shorten rejected
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> fx.registry.evolve("user", same, 500L));
        assertTrue(ex.getConflicts().stream()
                .anyMatch(c -> c.reason() == ConflictReason.TTL_SHORTENED));
    }

    @Test
    void duplicateFeatureNameWithinOneDefinitionIsRejected() {
        var dup = List.of(
                feature("age", ValueType.LONG, true, 0L, MissingPolicy.DEFAULT),
                feature("age", ValueType.STRING, false, null, MissingPolicy.NULL));
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> register("user", dup));
        assertTrue(ex.getConflicts().stream()
                .anyMatch(c -> c.reason() == ConflictReason.DUPLICATE_FEATURE_NAME));
    }

    @Test
    void defaultValueTypeIsValidated() {
        var bad = List.of(feature("age", ValueType.LONG, true, "oops", MissingPolicy.DEFAULT));
        DefinitionConflictException ex = assertThrows(DefinitionConflictException.class,
                () -> register("user", bad));
        assertTrue(ex.getConflicts().stream()
                .anyMatch(c -> c.reason() == ConflictReason.INVALID_DEFINITION));
    }
}
