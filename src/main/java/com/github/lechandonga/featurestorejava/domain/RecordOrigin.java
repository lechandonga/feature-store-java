package com.github.lechandonga.featurestorejava.domain;

/** How a stored feature record entered the store. Drives explainability of corrections. */
public enum RecordOrigin {
    INGEST, BACKFILL, RECOMPUTE
}
