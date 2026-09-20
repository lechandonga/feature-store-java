package com.github.lechandonga.featurestorejava.domain;

/**
 * Policy defining what a read returns when no visible feature value exists.
 * DEFAULT      -> return the per-version registered default value (status DEFAULTED)
 * NULL         -> return null with explicit status MISSING
 * ERROR        -> do not fabricate a value; return status MISSING_ERROR for the caller to handle
 */
public enum MissingPolicy {
    DEFAULT, NULL, ERROR
}
