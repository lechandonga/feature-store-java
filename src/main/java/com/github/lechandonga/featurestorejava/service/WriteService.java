package com.github.lechandonga.featurestorejava.service;

import com.github.lechandonga.featurestorejava.model.WriteBatch;
import com.github.lechandonga.featurestorejava.model.WriteResult;

/**
 * Atomic, schema-validated feature ingestion.
 */
public class WriteService {

    public WriteService(StoreState state, FeatureRegistry registry, AuditLog audit, TimeSource time) {
        // implemented later
    }

    public WriteResult submit(WriteBatch batch) {
        return null; // implemented later
    }
}
