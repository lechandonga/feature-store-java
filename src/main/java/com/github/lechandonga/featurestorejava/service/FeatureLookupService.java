package com.github.lechandonga.featurestorejava.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import com.github.lechandonga.featurestorejava.domain.FeatureDefinition;
import com.github.lechandonga.featurestorejava.domain.FeatureRecord;
import com.github.lechandonga.featurestorejava.domain.MissingPolicy;
import com.github.lechandonga.featurestorejava.domain.RecordOrigin;
import com.github.lechandonga.featurestorejava.domain.ValueStatus;
import com.github.lechandonga.featurestorejava.storage.FeatureStorage;
import org.springframework.stereotype.Service;

/**
 * Single point-in-time lookup implementation shared by online and offline serving.
 *
 * <p>Visibility rules:
 * <ul>
 *   <li>{@link LookupMode#AS_OF} (offline training): a record is visible only when its
 *       eventTime &le; asOf AND it had entered the store by asOf ({@code ingestTs &le; asOf})
 *       AND its revision had been committed by asOf ({@code revisionTs &le; asOf}).
 *       Consequently future data, late writes and backfill corrections committed after asOf
 *       are invisible — no label leakage, and results are bit-for-bit reproducible.</li>
 *   <li>{@link LookupMode#ONLINE}: every committed revision is visible (asOf = read time);</li>
 * </ul>
 * In both modes the record with the greatest eventTime is used; if multiple revisions of that
 * eventTime exist, the latest committed revision wins. Online and offline therefore produce
 * identical results when called at the same time point with the same version.
 *
 * <p>Freshness: a visible value older than the feature's ttl is still returned but marked
 * EXPIRED, rather than silently replacing it with a default.
 */
@Service
public class FeatureLookupService {

    public enum LookupMode {
        /** Offline training: strict visibility cutoff at asOf. */
        AS_OF,
        /** Online serving: all committed revisions visible. */
        ONLINE
    }

    private final FeatureStorage storage;
    private final DecisionLogger decisionLogger;

    public FeatureLookupService(FeatureStorage storage, DecisionLogger decisionLogger) {
        this.storage = storage;
        this.decisionLogger = decisionLogger;
    }

    public FeatureValue lookup(FeatureDefinition def, String entityId, long asOfMs, LookupMode mode) {
        return lookup(def, entityId, asOfMs, mode, null);
    }

    /**
     * Lookup bound to a storage snapshot handle. Pass the same handle for every feature of one
     * read request so the response cannot straddle a backfill/recompute commit.
     */
    public FeatureValue lookup(FeatureDefinition def, String entityId, long asOfMs, LookupMode mode,
                               Object snapshotHandle) {
        List<FeatureRecord> all = snapshotHandle == null
                ? storage.scan(def.name(), def.version(), entityId)
                : storage.scan(snapshotHandle, def.name(), def.version(), entityId);

        Optional<FeatureRecord> chosen = all.stream()
                .filter(r -> r.eventTsMs() <= asOfMs)
                .filter(r -> mode == LookupMode.ONLINE || (r.ingestTsMs() <= asOfMs && r.revisionTsMs() <= asOfMs))
                .max(Comparator.comparingLong(FeatureRecord::eventTsMs)
                        .thenComparingLong(FeatureRecord::revisionTsMs)
                        .thenComparingInt(r -> originRank(r.origin())));

        if (chosen.isEmpty()) {
            return missing(def, entityId, asOfMs, mode,
                    "no record with eventTs <= asOf" + (mode == LookupMode.AS_OF
                            ? " that was ingested and committed by asOf" : ""));
        }

        FeatureRecord r = chosen.get();
        boolean expired = def.ttlMs() > 0 && asOfMs - r.eventTsMs() > def.ttlMs();
        if (expired) {
            // Stale values are reported explicitly, never silently defaulted.
            FeatureValue expiredValue = new FeatureValue(def.name(), def.version(), entityId, asOfMs,
                    r.value(), ValueStatus.EXPIRED, r.eventTsMs(), r.ingestTsMs(), r.revisionTsMs(),
                    r.origin(), "latest visible value age " + (asOfMs - r.eventTsMs())
                            + "ms exceeds ttl " + def.ttlMs() + "ms; value kept, marked EXPIRED");
            decisionLogger.log("lookup", def.name(), def.version(), entityId, r.eventTsMs(), asOfMs,
                    "EXPIRED", "mode=" + mode + " age exceeds ttl, value not silently replaced");
            return expiredValue;
        }

        String basis = "mode=" + mode + " visibleByCutoff record eventTs=" + r.eventTsMs()
                + " ingestTs=" + r.ingestTsMs() + " revisionTs=" + r.revisionTsMs()
                + " origin=" + r.origin() + " (latest eventTs; latest revision wins)";
        decisionLogger.log("lookup", def.name(), def.version(), entityId, r.eventTsMs(), asOfMs,
                "RECORDED", basis);
        return new FeatureValue(def.name(), def.version(), entityId, asOfMs, r.value(),
                ValueStatus.RECORDED, r.eventTsMs(), r.ingestTsMs(), r.revisionTsMs(), r.origin(), basis);
    }

    private FeatureValue missing(FeatureDefinition def, String entityId, long asOfMs,
                                 LookupMode mode, String why) {
        String basis = "mode=" + mode + " no visible record: " + why
                + "; missingPolicy=" + def.missingPolicy();
        return switch (def.missingPolicy()) {
            case DEFAULT -> {
                decisionLogger.log("lookup", def.name(), def.version(), entityId, null, asOfMs,
                        "DEFAULTED", basis);
                yield new FeatureValue(def.name(), def.version(), entityId, asOfMs, def.defaultValue(),
                        ValueStatus.DEFAULTED, null, null, null, null, basis + "; returned registered default");
            }
            case NULL -> {
                decisionLogger.log("lookup", def.name(), def.version(), entityId, null, asOfMs,
                        "MISSING", basis);
                yield new FeatureValue(def.name(), def.version(), entityId, asOfMs, null,
                        ValueStatus.MISSING, null, null, null, null, basis + "; null returned explicitly");
            }
            case ERROR -> {
                decisionLogger.log("lookup", def.name(), def.version(), entityId, null, asOfMs,
                        "MISSING_ERROR", basis);
                yield new FeatureValue(def.name(), def.version(), entityId, asOfMs, null,
                        ValueStatus.MISSING_ERROR, null, null, null, null,
                        basis + "; no value fabricated, caller must handle MISSING_ERROR");
            }
        };
    }

    private static int originRank(RecordOrigin origin) {
        return switch (origin) {
            case INGEST -> 0;
            case BACKFILL -> 1;
            case RECOMPUTE -> 2;
        };
    }
}
