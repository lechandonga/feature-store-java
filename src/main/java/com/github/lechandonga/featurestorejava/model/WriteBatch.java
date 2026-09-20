package com.github.lechandonga.featurestorejava.model;

import java.util.List;

/**
 * An atomic write batch: either every row commits or none does.
 *
 * @param featureSet target feature set name
 * @param version    feature set version the writer coded against
 * @param rows       rows in the batch
 */
public record WriteBatch(String featureSet, int version, List<WriteRow> rows) {
}
