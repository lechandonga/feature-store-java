package com.github.lechandonga.featurestorejava.model;

/**
 * One (entity, asOfTime) target for batch historical reads / consistency checks.
 */
public record EntityAtPoint(String entity, long asOfTime) {
}
